package com.example.client.compat.dh;

import com.example.client.compat.FarFieldGate;
import com.example.client.compat.FarFieldWindow;
import com.example.vulkan.NativeVulkan;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 远场驱动器：<b>接管 DH 已经网格化好的 LOD 顶点缓冲</b>，转成我方顶点格式后送进 TLAS。
 *
 * <h2>它替换掉了什么（重构说明，别回退）</h2>
 * <p>旧实现是「读 DH 的粗层数据 → 自己网格化」：读 {@code DhTerrainSnapshot}、
 * 一列一列铺顶面与侧墙、自建图集（{@code DhLodMesher} / {@code DhLodBlockAtlas}）。
 * 实机对照后判定这条路画质上打不过 DH 自己的网格化器（台阶、方形断块、空洞俱全），
 * 用户要求推倒重写、照 Radiance（Vista）的形态做：<b>DH 怎么处理远场，我们就怎么处理
 * —— 直接吃 DH 的网格</b>。</p>
 *
 * <h2>上传粒度：per-section 增量（10-01 重构，别退回"整包替换"）</h2>
 * <p>旧实现把当轮所有 section <b>合并成一份</b> {@code byte[]}、统一原点、
 * 一次 {@code uploadDhLodMesh} 整包替换。问题有三条：</p>
 * <ol>
 *   <li>DH 视距拉大后几何量按面积涨，每次刷新都要重新分配+转换几十上百 MB；</li>
 *   <li>单份网格在 C++ 侧只有<b>一个</b> spatialKey ⇒ 一次上传等于整代 BLAS 重建；</li>
 *   <li>于是只能靠"内容指纹没变就跳过"省事，任一节变了都要全量重来。</li>
 * </ol>
 * <p>现在照 voxy 通路的形态改成<b>每节一个 pseudo-section</b>：DH 的每个
 * {@code section.pos} 对应一个独立 spatialKey、独立 BLAS 与 TLAS 实例，
 * 只上传<b>新增/变化</b>的节、撤下<b>不再启用</b>的节（{@code uploadDhLodSection} /
 * {@code removeDhLodSection}）。C++ 侧的有界增量重建（{@code maxSectionUpdates}）
 * 因此能真正生效。</p>
 *
 * <h2>画哪些节：交给 DH 自己</h2>
 * <p>不在本类做任何距离分档 / 环形窗口筛选。{@link DhLodMeshSink#renderCut()}
 * 返回「已捕获 ∩ DH 本轮启用清单 − 内洞」，那份清单来自 DH 自己的
 * {@code LodQuadTree}（见 {@code DhRenderListHookMixin}）。</p>
 *
 * <h2>内洞（保留）</h2>
 * <pre>
 *   内洞 = FarFieldWindow.seamSections() × 16 格   （与原版加载区边界重合 ⇒ 无真空带）
 * </pre>
 * <p>与 {@code FarFieldGate} 的 seam 读同一个函数，保证「近场不画的地方远场接着画」。</p>
 *
 * <h2>节流</h2>
 * <ol>
 *   <li><b>视图发布</b>：每帧做（很便宜）。</li>
 *   <li><b>增量同步</b>：每 {@link DhFlags#LOD_REFRESH_FRAMES} 帧、或玩家移动超过
 *       {@link DhFlags#LOD_MOVE_THRESHOLD} 格、或场上还没有网格时才做。</li>
 * </ol>
 *
 * <h2>线程</h2>
 * <p>{@link #tick()}（因此 {@link #tickInner()}）在渲染线程上跑：发布视图、算差集
 * （都是读暂存区的便宜操作），然后把<b>逐节转换 + JNI</b>整单丢给 {@link #WORKER}。</p>
 *
 * <p>{@link #JOB_IN_FLIGHT} 保证同一时刻只有一单在飞（旧的没落地就跳过本帧，
 * 避免排队堆积）；{@link #GENERATION} 让换世界/撤下时在飞的那一单自己作废。
 * 单线程 + 只允许一单在飞 ⇒ 任务按提交顺序落地 ⇒ C++ 的 version 判据不会被乱序穿透。</p>
 *
 * <p>本类不 import 任何 {@code com.seibel.distanthorizons.*}
 * （DH 类型只在 {@link DhLodMeshSink} 的调用方 mixin 里出现），因此可以被
 * {@link DhBridge}（隔离门面）安全引用。</p>
 */
public final class DhFarFieldUploader {
    private static final String TAG = "[DH-LODMESH]";

    private static volatile int uploadedQuads = -1;

    private static final Map<Long, Long> UPLOADED = new ConcurrentHashMap<>();

    private static long lastSyncFrame = Long.MIN_VALUE;
    private static double lastSyncX = Double.NaN;
    private static double lastSyncZ;

    private static int lastInnerBlocks = -1;
    private static boolean released;
    private static long frame;
    private static volatile long syncs;
    private static volatile long skippedUnchanged;
    private static volatile long uploadedSections;
    private static volatile long removedSections;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "DhLodFarField");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean JOB_IN_FLIGHT = new AtomicBoolean();
    private static final AtomicLong GENERATION = new AtomicLong();

    private DhFarFieldUploader() {
    }

    /** 渲染线程每帧调用一次（由 {@code DhBridge.tick()} 转进来）。 */
    public static void tick() {
        try {
            tickInner();
        } finally {
            FarFieldGate.onLodObservation("dh", uploadedQuads >= 0);
        }
    }

    private static void tickInner() {
        if (!DhFlags.LOD_MESH) {
            release();
            return;
        }
        frame++;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            release();
            return;
        }
        released = false;

        final double x = minecraft.player.getX();
        final double z = minecraft.player.getZ();
        final int playerBlockX = (int) Math.floor(x);
        final int playerBlockZ = (int) Math.floor(z);

        final int innerBlocks = FarFieldWindow.seamSections() * 16;

        if (innerBlocks != lastInnerBlocks) {
            lastInnerBlocks = innerBlocks;
            log("规划：视距=" + FarFieldWindow.renderDistanceChunks() + " 区块 seam="
                    + FarFieldWindow.seamSections()
                    + "section（" + innerBlocks + " 格，与 FarFieldGate 同源）"
                    + " ⇒ 内洞=" + innerBlocks + " 格；覆盖范围由 DH 自己的启用清单决定");
        }

        DhLodMeshSink.setView(playerBlockX, playerBlockZ, innerBlocks);

        final boolean first = lastSyncFrame == Long.MIN_VALUE;
        final boolean due = first || frame - lastSyncFrame >= DhFlags.LOD_REFRESH_FRAMES;
        final boolean movedEnough = Double.isNaN(lastSyncX)
                || Math.max(Math.abs(x - lastSyncX), Math.abs(z - lastSyncZ))
                >= DhFlags.LOD_MOVE_THRESHOLD;
        final boolean needData = uploadedQuads < 0;
        if (!(due || movedEnough || needData)) return;
        if (JOB_IN_FLIGHT.get()) return;

        if (DhLodMeshSink.enabledCount() == 0) {
            DhLodMeshSink.prune();
            return;
        }

        lastSyncFrame = frame;
        lastSyncX = x;
        lastSyncZ = z;

        sync();
    }


    private static void sync() {
        DhLodMeshSink.prune();
        final List<DhLodMeshSink.Section> desired = DhLodMeshSink.renderCut();

        final boolean textured = DhFlags.PUBLISH_TEXTURES && DhTextureHandles.isPublished();
        final long textureBit = textured ? 1L : 0L;

        final Map<Long, Long> wanted = new HashMap<>(desired.size() * 2);
        final List<DhLodMeshSink.Section> toUpload = new ArrayList<>();
        int quads = 0;
        for (DhLodMeshSink.Section section : desired) {
            final long version = section.serial * 2L + textureBit;
            wanted.put(section.pos, version);
            if (!Long.valueOf(version).equals(UPLOADED.get(section.pos))) {
                toUpload.add(section);
            }
            quads += section.quadCount();
        }

        final List<Long> toRemove = new ArrayList<>();
        for (Long pos : UPLOADED.keySet()) {
            if (!wanted.containsKey(pos)) toRemove.add(pos);
        }

        if (toUpload.isEmpty() && toRemove.isEmpty()) {
            skippedUnchanged++;
            return;
        }

        dispatch(wanted, toUpload, toRemove, quads, textured);
    }

    /**
     * 把这一单交给工作线程：逐节转换 + JNI 上传/移除（见类注释"线程"）。
     *
     * @param sections 渲染线程当场拍下的不可变快照；{@link DhLodMeshSink.Section} 一经入表
     *                 就不再修改，跨线程读是安全的。
     */
    private static void dispatch(Map<Long, Long> wanted, List<DhLodMeshSink.Section> sections,
                                 List<Long> toRemove, int quads, boolean textured) {
        final long generation = GENERATION.get();
        if (!JOB_IN_FLIGHT.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try {
                if (generation != GENERATION.get()) return;
                final long start = System.nanoTime();
                int uploaded = 0;
                long bytes = 0;
                for (DhLodMeshSink.Section section : sections) {
                    if (generation != GENERATION.get()) return;
                    final byte[] converted = convert(section, textured);
                    if (converted.length == 0) {
                        NativeVulkan.uploadDhLodSection(section.pos, wanted.get(section.pos),
                                section.minBlockX, section.minHeight, section.minBlockZ,
                                section.blockWidth, EMPTY_BYTES);
                    } else {
                        NativeVulkan.uploadDhLodSection(section.pos, wanted.get(section.pos),
                                section.minBlockX, section.minHeight, section.minBlockZ,
                                section.blockWidth, converted);
                    }
                    UPLOADED.put(section.pos, wanted.get(section.pos));
                    uploaded++;
                    bytes += converted.length;
                }
                int removed = 0;
                for (Long pos : toRemove) {
                    if (generation != GENERATION.get()) return;
                    NativeVulkan.removeDhLodSection(pos);
                    UPLOADED.remove(pos);
                    removed++;
                }
                uploadedSections += uploaded;
                removedSections += removed;
                syncDone(uploaded, removed, sections.size(), quads, bytes,
                        (System.nanoTime() - start) / 1_000_000L);
            } catch (Throwable failure) {
                log("转换/上传失败：" + failure);
            } finally {
                JOB_IN_FLIGHT.set(false);
            }
        });
    }

    private static void syncDone(int uploaded, int removed, int sectionCount, int quads, long bytes,
                                 long millis) {
        syncs++;
        uploadedQuads = quads;
        log("增量：上传 " + uploaded + " 节 / 撤下 " + removed + " 节"
                + "（本轮应画 " + sectionCount + " 节 quad=" + quads + " 转换字节=" + bytes
                + " 耗时=" + millis + "ms）"
                + " ｜ " + DhLodMeshSink.stats());
        if (uploaded + removed > 0) {
            log("层级 " + DhLodMeshSink.levelHistogram());
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * 把一个 DH section 的顶点流转换成我方 16B 布局。
     *
     * <p>DH → 我方：坐标是<b>相对该 section 最小角</b>的（原点就传 section 最小角）。
     * light 字节<b>原样拷贝</b>，不做 nibble 对调：DH 自己的编码就是
     * {@code (skyLight << 4) | blockLight}（见 DH 的
     * {@code assets/distanthorizons/shaders/terrain/blaze/vert.vsh}：
     * {@code skyLight = lights/16u}、{@code blockLight = mod(lights,16)}），
     * 与我方 byte 13 的约定逐位相同。</p>
     *
     * <p>字节 6-9 = {@code (tileId, normalIndex)}：DH 的每个 quad 自带
     * {@code textureTileId}（字节 14-15）与 {@code normalIndex}（字节 13），
     * rchit 用它们去 binding 49（DH 自己的 blockRatioAtlas）按方块取纹理
     * （对照 DH 的 {@code terrain/blaze/frag.fsh}：{@code tileId == 0} 才是无纹理）。</p>
     *
     * <p>{@code textureEnabled} 是那道门：DH 真图集没就绪时 C++ 会把 binding 49
     * 回落到<b>原版方块图集</b>，此时若顶点还带着 DH 的 tileId，采出来就是满屏错色。
     * 所以没就绪就写 {@code tileId = 0}（着色器走 {@code dhLodAlbedo} 的纯顶点色 +
     * DH 同款假纹理噪声）。</p>
     */
    private static byte[] convert(DhLodMeshSink.Section section, boolean textureEnabled) {
        final byte[] src = section.quads;
        final int limit = section.blockWidth + 1;

        int validQuads = 0;
        for (int q = 0; q + 64 <= src.length; q += 64) {
            boolean valid = true;
            for (int v = 0; v < 4; v++) {
                final int s = q + v * 16;
                if (readU16(src, s) > limit || readU16(src, s + 4) > limit) {
                    valid = false;
                    break;
                }
            }
            if (valid) validQuads++;
        }
        if (validQuads == 0) return EMPTY_BYTES;

        final byte[] out = new byte[validQuads * 64];
        int at = 0;
        for (int q = 0; q + 64 <= src.length; q += 64) {
            boolean valid = true;
            for (int v = 0; v < 4; v++) {
                final int s = q + v * 16;
                if (readU16(src, s) > limit || readU16(src, s + 4) > limit) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;

            for (int v = 0; v < 4; v++) {
                final int s = q + v * 16;
                final int d = at + v * 16;
                writeU16(out, d, readU16(src, s));
                writeU16(out, d + 2, readU16(src, s + 2));
                writeU16(out, d + 4, readU16(src, s + 4));
                final int tileId = textureEnabled ? readU16(src, s + 14) : 0;
                writeU16(out, d + 6, tileId);
                writeU16(out, d + 8, src[s + 13] & 0x0F);
                out[d + 10] = src[s + 8];
                out[d + 11] = src[s + 9];
                out[d + 12] = src[s + 10];
                out[d + 13] = src[s + 6];
                out[d + 14] = src[s + 12];
                out[d + 15] = 0;
            }
            at += 64;
        }
        return out;
    }
    /** 无符号小端 u16：{@code textureTileId} 值域可以到 65535，读成有符号会变负。 */
    private static int readU16(byte[] src, int index) {
        return (src[index] & 0xFF) | ((src[index + 1] & 0xFF) << 8);
    }

    private static void writeU16(byte[] dst, int index, int value) {
        dst[index] = (byte) (value & 0xFF);
        dst[index + 1] = (byte) ((value >> 8) & 0xFF);
    }


    /** 关开关 / 换世界 / 离开世界时撤下远场网格与暂存区。 */
    public static void reset() {
        release();
        DhLodMeshSink.clear();
        GENERATION.incrementAndGet();
        syncs = 0L;
        skippedUnchanged = 0L;
        uploadedSections = 0L;
        removedSections = 0L;
        uploadedQuads = -1;
        lastSyncFrame = Long.MIN_VALUE;
        lastSyncX = Double.NaN;
        lastSyncZ = 0.0;
        lastInnerBlocks = -1;
        frame = 0L;
    }

    private static void release() {
        if (released) return;
        released = true;
        DhLodMeshSink.clear();
        GENERATION.incrementAndGet();
        if (!UPLOADED.isEmpty()) {
            UPLOADED.clear();
            uploadedQuads = -1;
            NativeVulkan.clearDhLodSections();
        }
    }

    /** 供调试 HUD：一行摘要。 */
    public static String stats() {
        return "同步=" + syncs + " 跳过=" + skippedUnchanged
                + " 已上传节=" + UPLOADED.size()
                + "（累计上传=" + uploadedSections + " 撤下=" + removedSections + "）"
                + " 当前quad=" + (uploadedQuads < 0 ? "-" : uploadedQuads);
    }

    private static void log(String message) {
        System.err.println(TAG + " " + message);
    }
}
