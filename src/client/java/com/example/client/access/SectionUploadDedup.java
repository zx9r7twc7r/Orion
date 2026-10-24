package com.example.client.access;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.lwjgl.system.MemoryUtil;

/**
 * Section 几何上传的内容哈希去重（Java 侧，锁外）。
 *
 * <p>动机：native 的 {@code uploadChunk} 每次都要先取全局 {@code g_chunkMutex}、
 * 展开顶点、再比对，才发现内容没变。实测 20 个编译线程在这把锁上排队，
 * 走动时 {@code geometry} p90 达 45ms。这里在 JNI 之前用 64 位哈希挡掉
 * "内容完全相同"的重复上传，省掉整条 native 路径。
 *
 * <p><b>失效点（关键）</b>：凡是 native 会丢掉某个 Section 几何的地方，都必须同步
 * 让这里的哈希失效，否则重建时会被判为"未变"而永不更新（表现为某些区块不刷新）：
 * <ul>
 *     <li>{@code NativeVulkan.clearChunkLayers} —— 三处调用者
 *     （SectionCompilerMixin 的缺失层、FarFieldGate 的两条压制路径）；</li>
 *     <li>{@code NativeVulkan.clearWorldGeometry} —— 世界生命周期；</li>
 *     <li>世界对象更换（{@link #invalidateAll()}）。</li>
 * </ul>
 *
 * <p>开关：{@code -Drtx.sectionDedupOff=true} 关闭。
 */
public final class SectionUploadDedup {

    private static final boolean ENABLED =
            !Boolean.getBoolean("rtx.sectionDedupOff");

    private static final ConcurrentHashMap<Long, Long> HASHES =
            new ConcurrentHashMap<>();

    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();

    private SectionUploadDedup() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** section + layer → 稳定键（与 native 的 packSectionKey 布局一致）。 */
    public static long key(int sectionX, int sectionY, int sectionZ, int layerId) {
        return ((long) (layerId & 0xF) << 60)
                | ((long) (sectionY & 0xFFFF) << 44)
                | ((long) (sectionZ & 0x3FFFFF) << 22)
                | ((long) (sectionX & 0x3FFFFF));
    }

    /** FNV-1a 64：对顶点缓冲按 8 字节字推进。 */
    public static long hash(long address, int bytes) {
        long hash = 0xcbf29ce484222325L;

        final int words = bytes >>> 3;

        for (int i = 0; i < words; i++) {
            hash ^= MemoryUtil.memGetLong(address + ((long) i << 3));
            hash *= 0x100000001b3L;
        }

        return hash;
    }

    /**
     * true ⇒ 该 Section+layer 的内容与上次上传相同，可以跳过 JNI。
     */
    public static boolean shouldSkip(long key, long hash) {
        final long total = TOTAL.incrementAndGet();
        final Long previous = HASHES.get(key);

        if (previous != null && previous.longValue() == hash) {
            SKIPPED.incrementAndGet();
            report(total);
            return true;
        }

        return false;
    }

    public static void record(long key, long hash) {
        HASHES.put(key, hash);
    }

    /** native 已擦掉该 Section 的这些层 ⇒ 哈希必须同步失效。 */
    public static void invalidateSection(
            int sectionX,
            int sectionY,
            int sectionZ,
            int layerMask
    ) {
        if (!ENABLED) {
            return;
        }

        for (int layer = 0; layer < 3; layer++) {
            if ((layerMask & (1 << layer)) != 0) {
                HASHES.remove(key(sectionX, sectionY, sectionZ, layer));
            }
        }
    }

    /** 整世界几何被清（换世界/重载）⇒ 全清。 */
    public static void invalidateAll() {
        HASHES.clear();
    }

    private static void report(long total) {
        if ((total % 256L) != 0L) {
            return;
        }

        System.out.println(
                "[SC DEDUP] total=" + total
                        + " skipped=" + SKIPPED.get()
                        + " (缓存未变，未进入 native)"
        );
    }
}
