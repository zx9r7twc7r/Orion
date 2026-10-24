package com.example.client.compat;

import com.example.client.access.SectionUploadDedup;
import com.example.vulkan.NativeVulkan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远场接管闸门:原版 section 的编译结果只在近场 rd 环内上传给 Vulkan RT,
 * 更远的场由 voxy LOD 代理。每个编译过的 section 位置记 SUPPRESSED(远场
 * 编译,未上传)或 UPLOADED(已上传)。渲染线程每帧按切片推进状态表:进入
 * 缝内的 SUPPRESSED 标脏触发重编译上传;离开缝外的 UPLOADED 用零顶点
 * uploadChunk 从原生侧擦除,避免与 LOD 重叠 z-fight。
 *
 * <p>v6 起缝随 rd 动态(SEAM=rd 个 16 格 section),配合桥的 floor(rd/4)
 * LOD 缝环:rd=2~7 时 LOD 从 64 格环接管,rd=8~11 从 128 格环。接管只有
 * 在 LOD 确实供给非空网格后才启用,LOD 失效时原版渲染保持不变。</p>
 */
public final class FarFieldGate {
    /** LOD1 section 宽(格)。近场窗口与 LOD ring 边界都对齐这个网格。 */
    public static final int LOD_SECTION_BLOCKS = 64;
    // v6 起缝随 rd 动态(seamSections()/seamLodSections()),以下 v4/v5 固定
    // 缝常量已无调用点,按约定屏蔽保留,作为后续固定缝接口的选项。
    public static final int SEAM_SECTIONS = 12;
    public static final int DEMOTE_SECTIONS = 13;
    public static final int SEAM_LOD_SECTIONS = 3;
    public static final int SEAM_BLOCKS = 192;
    public static final int DEMOTE_BLOCKS = SEAM_BLOCKS + 32;
    public static final int MIN_RENDER_DISTANCE = 12;
    /** 状态表每帧只处理一个切片,大视距下渲染线程不被长表拖死。 */
    private static final int SLICE_COUNT = 8;
    private static final int MAX_TRANSITIONS_PER_FRAME = 8;
    /** 断供粘滞:LOD 连续空窗超过 2 秒(约 120 帧)才判定断供回退。 */
    private static final int EMPTY_GRACE_FRAMES = 120;
    /** ChunkSectionLayer: SOLID/CUTOUT/TRANSLUCENT,对应原生三个地形类。 */
    private static final int TERRAIN_LAYER_COUNT = 3;
    /** 上面三层的位掩码：交给 clearChunkLayers 一次擦完，不再逐层发 JNI。 */
    private static final int TERRAIN_LAYER_MASK =
            (1 << TERRAIN_LAYER_COUNT) - 1;

    private static final byte SUPPRESSED = 0;
    private static final byte UPLOADED = 1;
    /**
     * 曾是 UPLOADED，但这一轮在 ViewArea 里找不到它（第一次）。
     *
     * <p>为什么要一个中间态：确认"离开 ViewArea"要连续两次观测，避免 ViewArea 重建
     * （改视距 / 进出世界）时的一帧空窗把仍在渲染的 section 误擦成空洞。
     * 第二次仍是 MISSING_UPLOADED 才擦几何并销账（见 updateNearby）。</p>
     */
    private static final byte MISSING_UPLOADED = 2;

    private static final ConcurrentHashMap<Long, Byte> STATES = new ConcurrentHashMap<>();
    private static volatile boolean lodProviding;
    /** 上一个上报"非空 LOD"的提供者（voxy|dh|none），只用于日志与按提供者调参。 */
    private static volatile String lodProvider = "none";
    /** [FFG] 日志节流：每 120 次观测打一行。 */
    private static int observationLogCountdown = 1;
    private static int emptyStreak;
    private static int slice;
    /**
     * 一个观测窗口内实际发生的状态迁移数，随日志清零。
     *
     * <p>{@code restoreCover} 高 = 覆盖表在抖（远场一圈 section 被反复重编译再擦掉，
     * 属于病态）；{@code restoreNear} 高 = 玩家在穿越 64 格缝，正常；{@code erase}
     * 是近场几何被 LOD 接管。三个数放在同一行才能区分"玩家在走"和"覆盖表有问题"。</p>
     */
    private static int restoreNearTransitions;
    private static int restoreCoverTransitions;
    private static int eraseTransitions;
    /**
     * 取证（2026-09-26）：因"离开 ViewArea"而擦掉的原版地形 section 数。
     *
     * <p>这是判断"原版几何被永久留在 RT 里"这个泄漏**是否真实存在**的唯一直接证据：
     * 旧代码在这条路径上只销账不擦几何，所以从前它恒为 0。它现在一旦增长，就说明
     * 确实有 section 在"没有 LOD 覆盖"的状态下离开视距、其几何此前一直留在 RT 里。</p>
     */
    private static int orphanEraseTransitions;

    private FarFieldGate() {
    }

    /**
     * 任一远场提供者（voxy / DH）每帧报告"本轮是否真的供了非空 LOD 网格"。
     *
     * <p>P1-A（09-25）：本方法过去只由 voxy 桥调用，DH 从不上报 ⇒ 只装 DH 时
     * {@link #takeoverActive()} 恒 false，近场 section 永不被压制，远场 LOD 与近场
     * 真实几何同深度重复（移动时表现为"一层薄壳互相穿插"）。现在两边都上报。</p>
     *
     * <p>空窗需要连续 2 秒才回退（粘滞窗口），避免流式加载的瞬时空窗把近场打回来。</p>
     */
    public static void onLodObservation(String provider, boolean providing) {
        if (providing) {
            lodProviding = true;
            lodProvider = provider == null ? "none" : provider;
            emptyStreak = 0;
        } else if (lodProviding && ++emptyStreak >= EMPTY_GRACE_FRAMES) {
            lodProviding = false;
            lodProvider = "none";
        }
        if (--observationLogCountdown <= 0) {
            observationLogCountdown = 120;
            int restoreNear = restoreNearTransitions;
            int restoreCover = restoreCoverTransitions;
            int erases = eraseTransitions;
            int orphans = orphanEraseTransitions;
            restoreNearTransitions = 0;
            restoreCoverTransitions = 0;
            eraseTransitions = 0;
            orphanEraseTransitions = 0;
            log("provider=" + lodProvider
                    + " lodProviding=" + (lodProviding ? 1 : 0)
                    + " takeover=" + (takeoverActive() ? 1 : 0)
                    + " rd=" + FarFieldWindow.renderDistanceChunks()
                    + " seam=" + seamSections() + "section(" + FarFieldWindow.seamBlocks() + "格)"
                    + " suppressed=" + countSuppressed()
                    + " restoreNear=" + restoreNear
                    + " restoreCover=" + restoreCover
                    + " erase=" + erases
                    // 离开视距时被擦掉的原版地形数（旧代码恒为 0，因为那条路只销账不擦）。
                    + " orphanErase=" + orphans);
        }
    }

    private static int countSuppressed() {
        int count = 0;
        for (Byte state : STATES.values()) {
            if (state == SUPPRESSED) count++;
        }
        return count;
    }

    /** 世界切换/桥失效时立即回退,不等粘滞窗口。 */
    public static void onLodHardReset() {
        lodProviding = false;
        emptyStreak = 0;
        clearWorldStates("onLodHardReset");
    }

    /**
     * 2026-10-09 修「依次进多个存档后越来越卡」：把覆盖表整个清掉。
     *
     * <p><b>病根</b>：{@link #STATES} 的键是 section 坐标，而它**从不清**，于是表
     * 内容变成"所有进过的存档的 section 并集"；而 {@code updateNearby} 每帧都要
     * 把整张表走一遍（循环里的 {@code transitions < 8} 只限制深层处理，**迭代器
     * 不提前退出**）⇒ 每帧成本随"进过的存档数"线性上涨。这正是"换几个存档后
     * 变卡"的形状。</p>
     *
     * <p>顺带修一个正确性问题：键是纯坐标，同一个种子/坐标重进世界时，旧世界的
     * 残留记录会**错误地压制新世界同一位置**的几何。</p>
     *
     * <p>为什么清掉是安全的：世界切换时 native 侧 {@code clearWorldGeometry} 已经
     * 把全部几何与 BLAS 清空，覆盖表描述的正是那批几何的接管状态 ⇒ 两边同生共死。
     * 新世界的 section 会重新编译并按正常路径重新登记。</p>
     */
    public static void clearWorldStates(String reason) {
        final int before = STATES.size();

        if (before == 0) {
            return;
        }

        STATES.clear();
        // 一次性日志：这张表的大小就是"变卡"的量化指标，换世界后应当归零。
        System.out.println("[FFG] world states cleared (" + reason
                + "): size " + before + " -> 0");
    }

    /** 接管启用 = rd 足够大且 LOD 在供数据。 */
    public static boolean takeoverActive() {
        return lodProviding && lodWindowOpen();
    }

    /** 仅按 rd 判定 LOD 是否有展示窗口(与供给状态无关,避免上传死锁)。rd<2 时缝环无法对齐。 */
    public static boolean lodWindowOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.options != null && minecraft.options.renderDistance().get() >= 2;
    }

    /**
     * 动态缝(16 格 section 网格)：原版近场保留范围，必须与 LOD 让位范围严格相等。
     *
     * <p>v12 修正：原实现返回 {@code max(2, rd)} 个 16 格 section，即 rd×16 格；
     * 而 LOD 让位范围历史上是 {@code ceil(rd/4)} 个 64 格 block，只在 rd 为 4 的
     * 倍数时才刚好相等。rd=5 时原版只加载到 80 格、LOD 却让位 128 格，80~128 格
     * 之间出现空洞（原版 section 没有数据、LOD 又尚未铺设），表现为网格状断层。
     * 现两边统一由 {@link FarFieldWindow#lodRingSections()} 的 {@code floor(rd/4)}
     * 推导（floor 恒满足 seam &le; rd×16），数学上消除空洞。</p>
     */
    public static int seamSections() {
        return FarFieldWindow.seamSections();
    }

    /** 动态 LOD 缝环(64 格 section 网格)：ring &lt; floor(rd/4) 的 LOD 与近场重叠，过滤。 */
    public static int seamLodSections() {
        return FarFieldWindow.lodRingSections();
    }

    /**
     * 编译线程判定:接管启用、section 在近场窗口外、**且该位置真的被非空 LOD 网格覆盖**。
     *
     * <p>⭐ 2026-09-25 修正：原来只判 {@link #takeoverActive()}（全局）+ 到相机的
     * 距离，于是只要 voxy 在**任何地方**供了非空网格，64 格外的近场 section 就
     * 全部被压制——哪怕 voxy 在那个位置根本没有网格（数据还在 retry 队列里，
     * 实机 {@code retry=1670}）。结果是近场被压掉、LOD 那边是空的，两边都不画，
     * 表现为「空洞能看到横截面」，以及「64 格外看不到水面（近场水面被压掉、
     * LOD 还没有水）」。现在叠加逐位置覆盖判定，缺数据的位置保持近场渲染。</p>
     */
    public static boolean shouldSuppress(SectionPos pos) {
        if (!takeoverActive()) return false;
        if (!VoxyLodManager.coversSection(pos.x(), pos.z())) return false;
        Vec3 camera = VoxyCompat.cameraPosition(Minecraft.getInstance());
        if (camera == null) return false;
        return gridDistance(pos.x(), pos.z(), camera.x, camera.z) >= seamSections();
    }

    public static void recordState(SectionPos pos, boolean uploaded) {
        STATES.put(pos.asLong(), uploaded ? UPLOADED : SUPPRESSED);
    }

    /** 渲染线程:LevelRenderer.render 头部每帧调用。 */
    public static void updateNearby(CameraRenderState camera, ViewArea viewArea) {
        if (camera == null || viewArea == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        // =====================================================
        // 2026-10-10 修复「没装 voxy / DH、纯原版区块玩，内存与显存一直涨」
        //
        // 【病灶】本函数原先在 !takeoverActive() 时走一条"捷径"：把覆盖表整表删空
        // 就 return —— **不擦任何几何**。而全工程唯一会把 RT 里地形几何擦掉的代码，
        // 就在下面那个接管态分支里（clearChunkLayers）。于是没有 LOD 提供者时：
        //   · 进 ViewArea 的 section 正常上传几何（只进）；
        //   · 离开 ViewArea 的 section 走捷径，只销账不擦（不出）；
        // ⇒ RT 里的地形**只进不出**，随走过的路单调膨胀。
        // 实测（2026-10-10 一局，5 分 17 秒，纯原版区块）：
        //   meshSec 587 → 3114（×5.3）、blas 587 → 3202（×5.5）、
        //   meshMB 108 → 793MB（×7.4）、vramUsedMB 3248 → 7082（越过预算 6951），
        //   而 [LOD PERF] 那一行全程 `vanilla erased=0` —— 一次都没擦过。
        // 显存越过预算后驱动会把它回落到系统内存 ⇒ [RT MEM] 的 rssMB/privateMB
        // 跟着涨（3413→7388 / 7177→14629）⇒ 表现为"内存被占满 + 越玩越卡"。
        //
        // 【修法】不再分两条路：**"离开 ViewArea 就擦几何"在两种模式下都跑**，
        // 只在"仍在 ViewArea 内"的判断上区分（没有 LOD 时绝不能擦，因为那时仍是
        // 原版在画它）。表也不再每帧清空 —— 清空会让"连续两次不在 ViewArea"的
        // 两次确认永远凑不齐，等于把擦除逻辑废掉（见 VoxyLodManager 的配套修复）。
        // =====================================================
        final boolean takeover = takeoverActive();

        Vec3 cam = camera.pos;
        int target = slice;
        slice = (slice + 1) % SLICE_COUNT;
        int index = 0;
        int transitions = 0;
        Iterator<Map.Entry<Long, Byte>> it = STATES.entrySet().iterator();
        while (it.hasNext() && transitions < MAX_TRANSITIONS_PER_FRAME) {
            Map.Entry<Long, Byte> entry = it.next();
            if ((index++ % SLICE_COUNT) != target) continue;
            SectionPos pos = SectionPos.of(entry.getKey());
            byte state = entry.getValue();
            SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(pos.origin());
            if (section == null) {
                // ⭐ 2026-09-26 修复（用户报"很多 LOD 处冲突，反复"）：
                // 离开 ViewArea 的 section **必须把它在原生侧的几何擦掉**，不能只销账。
                // 证据链：uploadChunk 写的是持久 section 页（VulkanCore.cpp:29497 的零顶点
                // 分支才是移除语义：erase g_chunkMeshes + 让 BLAS 退休），而
                // ClientChunkCacheMixin.onDropChunk 只清光照（removeLightChunk），不清地形。
                // 所以这里原先的 `it.remove()` 会把那一片原版地形**永久留在 RT 里**：
                // 玩家走远后（当时该位置还没有 LOD 覆盖所以没被擦），voxy 稍后补齐数据、
                // LOD 正好画在同一个位置 ⇒ 两层几何叠加打架，且随走过的路累积。
                // 用"连续两次不在 ViewArea"确认，避开 ViewArea 重建时的一帧空窗误擦。
                if (state == MISSING_UPLOADED) {
                    // 2026-10-03 批量：一次 JNI 传完三层掩码。原先逐层发 3 次零顶点
                    // uploadChunk，而本函数单帧最多 8 次状态迁移 ⇒ 最多 24 次 JNI +
                    // 24 次 g_chunkMutex 取锁；现在最多 8 次。
                    NativeVulkan.clearChunkLayers(
                            pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);

                    // 2026-10-24：native 已擦掉该列几何 ⇒ 上传内容哈希必须失效，
                    // 否则重建时被判"未变"而永不恢复（区块不刷新/空洞）。
                    SectionUploadDedup.invalidateSection(
                            pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);
                    orphanEraseTransitions++;
                    it.remove();
                    transitions++;
                } else if (state == UPLOADED) {
                    // 第一次：只标记，等下一次确认。
                    entry.setValue(MISSING_UPLOADED);
                } else {
                    // SUPPRESSED：本来就没有几何，直接销账。
                    it.remove();
                }
                continue;
            }
            if (state == MISSING_UPLOADED) {
                // 又回到 ViewArea（重建/来回穿越）：撤销标记，否则下一帧会被误擦。
                entry.setValue(UPLOADED);
                state = UPLOADED;
            }

            // 2026-10-10：**没有 LOD 接管**时（没装 voxy / DH，纯原版区块）到此为止。
            // 上面"section == null"那一段（离开 ViewArea ⇒ 擦几何）两种模式共用，
            // 已经执行过了；这里只处理"**仍在 ViewArea 内**"的情形，而那一条在
            // 没有 LOD 时绝不能擦 —— 此时仍是原版在画它，擦了就是空洞。
            // 唯一要做的是把接管期残留的 SUPPRESSED 标脏送回原版重编译，
            // 与原 !takeoverActive() 捷径的行为保持一致（每帧限量，避免编译风暴）。
            if (!takeover) {
                if (state == SUPPRESSED) {
                    scheduleRecompile(minecraft, pos);
                    it.remove();
                    transitions++;
                }
                continue;
            }

            int distance = gridDistance(pos.x(), pos.z(), cam.x, cam.z);
            if (state == SUPPRESSED
                    && (distance < seamSections()
                    || !VoxyLodManager.coversSection(pos.x(), pos.z()))) {
                // 玩家靠近，**或 LOD 覆盖已经撤走**（数据被回收/换世界/网格变空）:
                // 标脏走原版异步重编译,onCompileReturn 会重新上传。
                // 后半条是 2026-09-25 新增：只有"靠近才恢复"时，voxy 撤回某个
                // 位置的覆盖会让那里永久留着空洞。
                if (distance < seamSections()) {
                    restoreNearTransitions++;
                } else {
                    restoreCoverTransitions++;
                }
                scheduleRecompile(minecraft, pos);
                it.remove();
                transitions++;
            } else if (state == UPLOADED && distance >= seamSections()
                    && VoxyLodManager.coversSection(pos.x(), pos.z())) {
                // 玩家离开, **且该位置确实被"已上传"的 LOD 覆盖**: 零顶点上传是原生侧
                // 权威的 section 移除数据。后半条是 09-25 补齐的对称项——此前只看距离,
                // 于是 LOD 还没铺到的位置也被擦掉,留下"空洞能看到横截面"。
                //
                // ⭐ 2026-09-26：阈值从 seamSections()+1 收紧到 seamSections()，消除"迟滞带"。
                // 原因（用户报"视距内区块反复和 LOD 冲突撞色"）：shouldSuppress 在
                // distance >= seamSections() 时就把新编译的 section 判为压制（不画），
                // 但这里原先要 distance >= seam+1 才擦**已经上传**的 —— 于是
                // `distance == seamSections()` 那一圈（16 格宽）变成"不擦也不恢复"：
                // 一个在无 LOD 覆盖时编译上传的 section（或相机后退后落到该圈的 section）
                // 会一直画着，而 LOD 从 seam 起就开始画 ⇒ 同位置两层打架。
                // 现在两个阈值一致：distance <= seam-1 归近场（LOD 侧由
                // VoxyLodManager.overlapsVanillaWindow 同源排除），distance >= seam 归 LOD。
                // 不会留空洞：擦除仍以 coversSection 为前提，LOD 没铺到就不擦。
                // 2026-10-03 批量：同上面那条"孤儿擦除"，三层一次传完。
                NativeVulkan.clearChunkLayers(
                        pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);

                // 2026-10-24：同上 —— 擦除后哈希必须失效。
                SectionUploadDedup.invalidateSection(
                        pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);
                entry.setValue(SUPPRESSED);
                eraseTransitions++;
                transitions++;
            }
        }
    }

    private static void scheduleRecompile(Minecraft minecraft, SectionPos pos) {
        // setSectionDirty 走主线程,与原版区块更新同路径。
        minecraft.execute(() -> {
            LevelExtractor extractor = minecraft.levelExtractor;
            if (extractor != null) {
                extractor.setSectionDirty(pos.x(), pos.y(), pos.z());
            }
        });
    }

    /**
     * 16 格 section 网格差,基准取相机所在的 64 格 LOD1 section 原点。
     * 相机在 LOD section 内的偏移被整除吸收,近场窗口边缘与 LOD ring
     * 边界严格对齐,缝内原版渲染、缝外由 LOD ring 无缝接管。
     */
    private static int gridDistance(int sectionX, int sectionZ, double cameraX, double cameraZ) {
        int baseX = Math.floorDiv((int) Math.floor(cameraX), LOD_SECTION_BLOCKS) * (LOD_SECTION_BLOCKS / 16);
        int baseZ = Math.floorDiv((int) Math.floor(cameraZ), LOD_SECTION_BLOCKS) * (LOD_SECTION_BLOCKS / 16);
        return Math.max(Math.abs(sectionX - baseX), Math.abs(sectionZ - baseZ));
    }

    /**
     * 仪器用：把状态表按"已上传 / 已擦除"分到两个集合（键是 {@code SectionPos.asLong()}）。
     *
     * <p>为什么需要它：用户报"视距与 LOD 之间是真空的"。要区分三种责任，必须把
     * <b>原版侧的两种状态</b>和 <b>LOD 侧实际上传了多少几何</b>并排看：
     * 只有"擦除了多少"（{@code suppressed}）、"还在 RT 里多少"（{@code uploaded}）
     * 和"LOD 交给原生侧多少 quad"（见 {@link VoxyLodManager#reportSeam}）三个数放在同一环上，
     * 才能判定是"擦掉没补"、"从未编译"还是"LOD 数据缺失"。</p>
     */
    static void collectStates(Set<Long> uploaded, Set<Long> suppressed) {
        for (Map.Entry<Long, Byte> entry : STATES.entrySet()) {
            (entry.getValue() == SUPPRESSED ? suppressed : uploaded).add(entry.getKey());
        }
    }

    private static void log(String message) {
        System.err.println("[FFG] " + message);
    }
}
