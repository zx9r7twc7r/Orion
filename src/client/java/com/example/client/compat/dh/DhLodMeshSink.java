package com.example.client.compat.dh;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接管 DH <b>已经网格化好的</b> LOD 顶点缓冲（照 Radiance 的形态：我们不生成几何，只搬运）。
 *
 * <h2>职责边界</h2>
 * <p>DH 每构建完一个 section 的顶点缓冲，就在 {@code LodBufferContainer
 * .tryMakeAndUploadBuffersAsync} 的 HEAD 处被 {@code DhLodBufferHookMixin} 拦下、
 * 调到这里。此刻那些 {@link ByteBuffer} 是<b>池化对象</b>，函数返回后就会被回收，
 * 所以这里必须<b>当场把字节拷进自己的 {@code byte[]}</b>，不能持有 buffer 引用。</p>
 *
 * <h2>画什么：DH 自己的清单（10-01 重构，之前的做法全部作废）</h2>
 * <p>本类曾经自造四道闸门（捕获半径 / 环形窗口 / 距离分档 {@code targetWidth} /
 * 尺寸上限），每一道都是猜测 DH 的选层意图，结果是实机「只有比较近的 LOD、
 * 完全没有 256 区块的感觉」。</p>
 * <p>现在唯一真源是 DH 自己：{@code LodQuadTree} 每 tick 在 {@code tryTick} →
 * {@code updateAllRenderSections} 里按「该距离该用的 detail level」<b>每柱启用一个节点</b>，
 * 那份清单由 {@code DhRenderListHookMixin} 每 tick 抄进 {@link #onEnabledSections}。
 * 于是：</p>
 * <ul>
 *   <li><b>画 {@code = 已捕获 ∩ 该清单}</b>（{@link #renderCut()}）——不再有距离分档、
 *       不再有环形窗口、不再有尺寸上限。</li>
 *   <li>只额外保留一条<b>内洞</b>：{@code AABB 完全落在内洞内}的 section 不画
 *       （那片原版真实几何已覆盖，同深度会打架）。{@link DhFarFieldUploader}
 *       每帧用 {@code FarFieldWindow.seamSections()} 发布它，与 {@code FarFieldGate} 同源。</li>
 * </ul>
 *
 * <h2>为什么 build 期也不再按距离拒收</h2>
 * <p>DH 只在"该 section 的 detail level 变了"时重建，走开之后再走回来 DH 不会为我们
 * 补建。所以凡是 DH 建过的都要收下（否则前方永久空洞）。RAM 由 {@link #MAX_STORED_BYTES}
 * 这道<b>字节上限</b>兜底，超限时优先淘汰"不在当前启用清单里"的（远者先），
 * 再不够才动启用清单里的（会打日志，那意味着预算真的太小）。</p>
 *
 * <h2>线程</h2>
 * <p>{@link #onBuilt} 跑在 DH 的缓冲构建线程上（可能多条），{@link #onEnabledSections}
 * 跑在客户端 tick 线程，{@link #setView} / {@link #renderCut} / {@link #prune} /
 * {@link #clear} 跑在渲染线程。共享状态只有 {@link ConcurrentHashMap}、
 * {@link LongOpenHashSet} 的几个原子量、以及一个 volatile 数组引用 ⇒ 无锁、无阻塞。</p>
 * <p>每个 {@link Section} 一旦入表就不再被修改（DH 重构建会<b>换一个新的 Section 对象</b>），
 * 所以渲染线程边读边收是安全的。</p>
 *
 * <h2>DH 顶点格式（16 字节/顶点，nativeOrder = 小端，已由反编译钉死）</h2>
 * <pre>
 *   +0..1  short x     相对 section 最小角（格）
 *   +2..3  short y     相对 level.getMinHeight()（格）
 *   +4..5  short z
 *   +6     u8  light   (skyLight &lt;&lt; 4) | blockLight   ← 高 4 位是 sky（DH 的 vert.vsh 钉死）
 *   +7     u8  microOffset（法线方向编码）
 *   +8/9/10/11  R/G/B/A
 *   +12    u8  irisBlockMaterialId（EDhApiBlockMaterial 0..15）
 *   +13    u8  normalIndex（面序 0..5）
 *   +14..15 short textureTileId
 * </pre>
 * <p>坐标语义由 {@code LodBufferContainer} 第 47 行证明：{@code minCornerBlockPos.y =
 * clientLevelWrapper.getMinHeight()}，即世界 y = minHeight + vy。</p>
 *
 * <h2>我方 16 字节格式（{@code VulkanCore.cpp} 的 {@code appendDhLodQuads} 契约）</h2>
 * <pre>
 *   +0..1  u16 x   相对该 section 最小角
 *   +2..3  u16 y
 *   +4..5  u16 z
 *   +6..7  u16 textureTileId（0 = 无纹理，走烘焙方块色；DH 自己的约定）
 *   +8..9  u16 normalIndex（面序 0..5）
 *   +10/11/12  R/G/B
 *   +13    u8  light = (skyLight &lt;&lt; 4) | blockLight   ← 与 DH 逐位相同，原样拷贝
 *   +14    u8  material
 *   +15    未用
 * </pre>
 *
 * <p>关闭：{@code -Dorion.dh.lodHook=false}（见 {@link DhFlags#LOD_HOOK}）。</p>
 */
public final class DhLodMeshSink {
    private static final String TAG = "[DH-SINK]";

    

    private static final long MAX_STORED_BYTES = 512L * 1024 * 1024;

    private static final float EVICT_TARGET_RATIO = 0.85f;

    private static final ConcurrentHashMap<Long, Section> SECTIONS = new ConcurrentHashMap<>();
    private static final AtomicLong STORED_BYTES = new AtomicLong();
    private static final AtomicLong SERIAL = new AtomicLong();
    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicLong KEPT = new AtomicLong();
    private static final AtomicLong EVICTED = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();

    private static final AtomicIntegerArray EMITTED_LEVELS = new AtomicIntegerArray(32);
    private static final AtomicIntegerArray BUILT_LEVELS = new AtomicIntegerArray(32);

    private static volatile long[] enabled = new long[0];

    private static volatile int centerX;
    private static volatile int centerZ;
    private static volatile int innerBlocks;
    private static volatile boolean viewReady;

    private DhLodMeshSink() {
    }

    /** 一个 section 的顶点字节快照（不可变）。 */
    public static final class Section {
        public final long pos;
        public final int blockWidth;
        public final int minBlockX;
        public final int minBlockZ;
        public final int minHeight;
        public final byte[] quads;
        

        public final long serial;

        Section(long pos, int blockWidth, int minBlockX, int minBlockZ, int minHeight,
                byte[] quads, long serial) {
            this.pos = pos;
            this.blockWidth = blockWidth;
            this.minBlockX = minBlockX;
            this.minBlockZ = minBlockZ;
            this.minHeight = minHeight;
            this.quads = quads;
            this.serial = serial;
        }

        public int quadCount() {
            return quads.length / 64;
        }
    }


    /**
     * 发布本轮视图（格，切比雪夫）。
     *
     * @param centerX     视图中心（玩家所在格），只用于超预算时的"远者先淘汰"
     * @param innerBlocks 内洞半径：AABB 完全落在其内的 section 不<b>画</b>（原版真实几何覆盖）
     */
    public static void setView(int centerX, int centerZ, int innerBlocks) {
        DhLodMeshSink.centerX = centerX;
        DhLodMeshSink.centerZ = centerZ;
        DhLodMeshSink.innerBlocks = Math.max(0, innerBlocks);
        viewReady = true;
    }

    /** DH 本 tick 启用的 section pos（客户端 tick 线程调用）。 */
    public static void onEnabledSections(long[] positions) {
        enabled = positions;
    }

    /**
     * 要不要每 tick 抄 DH 的启用清单。
     *
     * <p>{@code DhRenderListHookMixin} 在 {@code compat.dh.mixin} 子包，读不到
     * 包私有的 {@link DhFlags}，所以由这里代问。接管链关掉时返回 false，
     * 混入那边就不会每 tick 白拷一份上千长度的清单。</p>
     */
    public static boolean wantsEnabledSections() {
        return DhFlags.LOD_HOOK && DhFlags.LOD_MESH;
    }

    /** DH 本轮启用了多少节（0 = DH 还没 tick 过，此时不要据此撤下已上传的网格）。 */
    public static int enabledCount() {
        return enabled.length;
    }

    /**
     * 本轮该<b>画</b>的 section 集合：{@code 已捕获 ∩ DH 启用的}，再滤掉完全落在内洞里的。
     *
     * <p>DH 的启用清单每柱只有一个 detail level ⇒ 结果天然粗细不重叠、无空洞，
     * 不需要我们再做任何 cut。</p>
     */
    public static List<Section> renderCut() {
        for (int level = 0; level < 32; level++) EMITTED_LEVELS.set(level, 0);
        final long[] positions = enabled;
        final List<Section> out = new ArrayList<>(positions.length);
        for (long pos : positions) {
            final Section section = SECTIONS.get(pos);
            if (section == null) continue;
            if (insideInnerHole(section)) continue;
            final int level = Integer.numberOfTrailingZeros(section.blockWidth);
            if (level > 0 && level < 32) EMITTED_LEVELS.incrementAndGet(level);
            out.add(section);
        }
        return out;
    }

    /** AABB 完全落在内洞方框内 ⇒ 该处原版真实几何已覆盖，画了会同深度打架。 */
    private static boolean insideInnerHole(Section section) {
        if (!viewReady) return false;
        final int inner = innerBlocks;
        if (inner <= 0) return false;
        final int cx = centerX, cz = centerZ;
        final int minX = section.minBlockX, maxX = section.minBlockX + section.blockWidth;
        final int minZ = section.minBlockZ, maxZ = section.minBlockZ + section.blockWidth;
        return minX >= cx - inner && maxX <= cx + inner
                && minZ >= cz - inner && maxZ <= cz + inner;
    }

    /**
     * 超字节上限时<b>远者先淘汰</b>到 {@link #EVICT_TARGET_RATIO}。
     *
     * <p>淘汰顺序：先丢"不在本轮启用清单里"的（它们本来就不参与本帧 TLAS），
     * 再丢启用清单里离玩家最远的。丢启用清单里的会打一行日志——那说明预算真的太小。</p>
     */
    public static void prune() {
        if (STORED_BYTES.get() <= MAX_STORED_BYTES) return;
        final long target = (long) (MAX_STORED_BYTES * EVICT_TARGET_RATIO);
        final LongOpenHashSet live = new LongOpenHashSet(enabled);

        final List<Section> byPriority = new ArrayList<>(SECTIONS.values());
        byPriority.sort(Comparator
                .comparingInt((Section s) -> live.contains(s.pos) ? 1 : 0)
                .thenComparing(Comparator.comparingInt(DhLodMeshSink::distance).reversed()));

        long stored = STORED_BYTES.get();
        boolean droppedLive = false;
        for (Section section : byPriority) {
            if (stored <= target) break;
            if (live.contains(section.pos)) droppedLive = true;
            stored -= section.quads.length;
            EVICTED.incrementAndGet();
            remove(section.pos);
        }
        if (droppedLive) {
            log("暂存区超 " + (MAX_STORED_BYTES >> 20) + "MB，已开始淘汰<b>启用清单内</b>的节点"
                    + "（画面会出现空洞，请调小 DH 视距或调大预算）");
        }
    }

    /** 换世界 / 关开关：清空全部暂存与启用清单。 */
    public static void clear() {
        SECTIONS.clear();
        STORED_BYTES.set(0L);
        enabled = new long[0];
        viewReady = false;
    }


    /**
     * 由 {@code DhLodBufferHookMixin} 在 {@code tryMakeAndUploadBuffersAsync} 的 HEAD 调用。
     *
     * @param pos          DH 的 section 编码（{@code DhSectionPos} 位域）
     * @param blockWidth   该 section 的方块边长（{@code 2^detailLevel}）
     * @param originBlockX / originBlockZ  该 section 最小角的绝对方块坐标
     * @param minHeight    该 level 的最低方块 y（DH 顶点 y 的基准）
     */
    public static void onBuilt(long pos, int blockWidth, int originBlockX, int originBlockZ, int minHeight,
                               List<ByteBuffer> opaque, List<ByteBuffer> transparent) {
        if (!DhFlags.LOD_HOOK) return;
        BUILDS.incrementAndGet();
        if (blockWidth > 0) {
            final int level = Integer.numberOfTrailingZeros(blockWidth);
            if (level > 0 && level < 32) BUILT_LEVELS.incrementAndGet(level);
        }

        final int bytes = (int) Math.min(Integer.MAX_VALUE, sumBytes(opaque) + sumBytes(transparent));
        if (bytes <= 0) {
            remove(pos);
            return;
        }

        final Section previous = SECTIONS.get(pos);
        final int previousBytes = previous == null ? 0 : previous.quads.length;
        if (STORED_BYTES.get() - previousBytes + bytes > MAX_STORED_BYTES) {
            makeRoom(pos, previousBytes, bytes);
            if (STORED_BYTES.get() - previousBytes + bytes > MAX_STORED_BYTES) {
                DROPPED.incrementAndGet();
                return;
            }
        }

        final byte[] copy = copy(opaque, transparent);
        SECTIONS.put(pos, new Section(pos, blockWidth, originBlockX, originBlockZ, minHeight,
                copy, SERIAL.incrementAndGet()));
        if (previousBytes != 0) STORED_BYTES.addAndGet(-previousBytes);
        STORED_BYTES.addAndGet(copy.length);
        KEPT.incrementAndGet();
    }


    /**
     * 为即将入表的一节腾出空间：反复淘汰"不在启用清单里、且离玩家最远"的一个，
     * 直到有位置或再也腾不出来。
     */
    private static void makeRoom(long incomingPos, int previousBytes, int incomingBytes) {
        final LongOpenHashSet live = new LongOpenHashSet(enabled);
        while (STORED_BYTES.get() - previousBytes + incomingBytes > MAX_STORED_BYTES) {
            Section victim = null;
            int victimDistance = -1;
            for (Section section : SECTIONS.values()) {
                if (section.pos == incomingPos) continue;
                if (live.contains(section.pos)) continue;
                final int distance = distance(section);
                if (distance > victimDistance) {
                    victimDistance = distance;
                    victim = section;
                }
            }
            if (victim == null) return;
            EVICTED.incrementAndGet();
            remove(victim.pos);
        }
    }

    /** 到视图中心的切比雪夫距离（中心在 AABB 内为 0）。 */
    private static int distance(Section section) {
        return Math.max(
                axisGap(section.minBlockX, section.minBlockX + section.blockWidth, centerX),
                axisGap(section.minBlockZ, section.minBlockZ + section.blockWidth, centerZ));
    }

    private static int axisGap(int lo, int hi, int c) {
        if (c < lo) return lo - c;
        if (c > hi) return c - hi;
        return 0;
    }

    private static void remove(long pos) {
        final Section previous = SECTIONS.remove(pos);
        if (previous != null) {
            STORED_BYTES.addAndGet(-previous.quads.length);
        }
    }

    private static long sumBytes(List<ByteBuffer> buffers) {
        if (buffers == null || buffers.isEmpty()) return 0L;
        long total = 0L;
        for (int i = 0; i < buffers.size(); i++) {
            ByteBuffer buffer = buffers.get(i);
            if (buffer != null) total += buffer.remaining();
        }
        return total;
    }

    /** 把若干池化 buffer 的字节拷进一份新数组（不移动原 buffer 的 position）。 */
    private static byte[] copy(List<ByteBuffer> opaque, List<ByteBuffer> transparent) {
        final byte[] out = new byte[(int) (sumBytes(opaque) + sumBytes(transparent))];
        int at = 0;
        at = copyInto(opaque, out, at);
        at = copyInto(transparent, out, at);
        return out;
    }

    private static int copyInto(List<ByteBuffer> buffers, byte[] out, int at) {
        if (buffers == null) return at;
        for (int i = 0; i < buffers.size(); i++) {
            ByteBuffer buffer = buffers.get(i);
            if (buffer == null) continue;
            ByteBuffer view = buffer.duplicate();
            final int n = view.remaining();
            view.get(out, at, n);
            at += n;
        }
        return at;
    }

    /** 供调试 HUD / 日志。 */
    public static String stats() {
        return "构建=" + BUILDS.get() + " 启用=" + enabled.length
                + " 暂存=" + SECTIONS.size() + " 节/"
                + (STORED_BYTES.get() >> 20) + "MB"
                + "（淘汰=" + EVICTED.get() + " 丢弃=" + DROPPED.get() + "）";
    }

    /**
     * 三行 level 直方图：{@code DH建过=}（DH 全部构建）、{@code 暂存=}（当前留下的）、
     * {@code 发出=}（{@link #renderCut()} 真正交给渲染的那一轮）。
     *
     * <p>用途：判断"粗几何"到底是我们筛出来的还是 DH 本来就只生成了粗层。
     * 现在 {@code 发出} 应当逐级跟随 DH 的启用清单；若它明显小于 {@code 暂存}，
     * 说明 DH 自己就没启用那些节点（视距/DH 配置问题，不在我们这边）。</p>
     */
    public static String levelHistogram() {
        final StringBuilder built = new StringBuilder();
        final StringBuilder stored = new StringBuilder();
        final StringBuilder emitted = new StringBuilder();
        for (int level = 0; level < 32; level++) {
            final int b = BUILT_LEVELS.get(level);
            if (b > 0) built.append(built.isEmpty() ? "" : " ").append('d').append(level).append('=').append(b);
        }
        final int[] storedCounts = new int[32];
        for (Section section : SECTIONS.values()) {
            if (section.blockWidth <= 0) continue;
            final int level = Integer.numberOfTrailingZeros(section.blockWidth);
            if (level > 0 && level < 32) storedCounts[level]++;
        }
        for (int level = 0; level < 32; level++) {
            if (storedCounts[level] > 0) {
                stored.append(stored.isEmpty() ? "" : " ").append('d').append(level).append('=').append(storedCounts[level]);
            }
            final int e = EMITTED_LEVELS.get(level);
            if (e > 0) emitted.append(emitted.isEmpty() ? "" : " ").append('d').append(level).append('=').append(e);
        }
        return "DH建过=" + (built.isEmpty() ? "无" : built)
                + " ｜ 暂存=" + (stored.isEmpty() ? "无" : stored)
                + " ｜ 发出=" + (emitted.isEmpty() ? "无" : emitted);
    }

    private static void log(String message) {
        System.err.println(TAG + " " + message);
    }
}
