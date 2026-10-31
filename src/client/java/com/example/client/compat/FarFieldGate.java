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
    public static final int LOD_SECTION_BLOCKS = 64;
    public static final int SEAM_SECTIONS = 12;
    public static final int DEMOTE_SECTIONS = 13;
    public static final int SEAM_LOD_SECTIONS = 3;
    public static final int SEAM_BLOCKS = 192;
    public static final int DEMOTE_BLOCKS = SEAM_BLOCKS + 32;
    public static final int MIN_RENDER_DISTANCE = 12;
    private static final int SLICE_COUNT = 8;
    private static final int MAX_TRANSITIONS_PER_FRAME = 8;
    private static final int EMPTY_GRACE_FRAMES = 120;
    private static final int TERRAIN_LAYER_COUNT = 3;
    private static final int TERRAIN_LAYER_MASK =
            (1 << TERRAIN_LAYER_COUNT) - 1;

    private static final byte SUPPRESSED = 0;
    private static final byte UPLOADED = 1;
    

    private static final byte MISSING_UPLOADED = 2;

    private static final ConcurrentHashMap<Long, Byte> STATES = new ConcurrentHashMap<>();
    private static volatile boolean lodProviding;
    private static volatile String lodProvider = "none";
    private static int observationLogCountdown = 1;
    private static int emptyStreak;
    private static int slice;
    

    private static int restoreNearTransitions;
    private static int restoreCoverTransitions;
    private static int eraseTransitions;
    

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
                if (state == MISSING_UPLOADED) {
                    NativeVulkan.clearChunkLayers(
                            pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);

                    SectionUploadDedup.invalidateSection(
                            pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);
                    orphanEraseTransitions++;
                    it.remove();
                    transitions++;
                } else if (state == UPLOADED) {
                    entry.setValue(MISSING_UPLOADED);
                } else {
                    it.remove();
                }
                continue;
            }
            if (state == MISSING_UPLOADED) {
                entry.setValue(UPLOADED);
                state = UPLOADED;
            }

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
                NativeVulkan.clearChunkLayers(
                        pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);

                SectionUploadDedup.invalidateSection(
                        pos.x(), pos.y(), pos.z(), TERRAIN_LAYER_MASK);
                entry.setValue(SUPPRESSED);
                eraseTransitions++;
                transitions++;
            }
        }
    }

    private static void scheduleRecompile(Minecraft minecraft, SectionPos pos) {
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
