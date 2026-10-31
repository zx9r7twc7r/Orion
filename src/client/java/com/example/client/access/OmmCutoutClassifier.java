package com.example.client.access;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative 4-state opacity-micromap input for Minecraft CUTOUT quads.
 *
 * <p>Only micro-triangles whose complete pixel footprint is provably visible
 * or invisible are classified. Every other case remains UNKNOWN_OPAQUE, which
 * deliberately preserves the existing .rahit alpha test.</p>
 */
public final class OmmCutoutClassifier {
    public static final int SUBDIVISION_LEVEL = 4;
    public static final int MICRO_TRIANGLE_COUNT =
            1 << (SUBDIVISION_LEVEL * 2);
    public static final int BYTES_PER_TRIANGLE =
            (MICRO_TRIANGLE_COUNT * 2 + 7) >>> 3;

    private static final int FULLY_TRANSPARENT = 0;
    private static final int FULLY_OPAQUE = 1;
    private static final int UNKNOWN_OPAQUE = 3;
    public static final int ALPHA_CUTOFF = 26;
    private static final byte UNKNOWN_OPAQUE_BYTE = (byte) 0xFF;
    private static final int MAX_CACHE_ENTRIES = 16_384;

    private static final int MAX_SCRATCH_BYTES = 8 * 1024 * 1024;

    private static final Map<TriangleKey, TriangleResult> CACHE =
            new ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicInteger
            CACHE_INSERTIONS = new java.util.concurrent.atomic.AtomicInteger();

    private static final ThreadLocal<TriangleKey> KEY_SCRATCH =
            ThreadLocal.withInitial(TriangleKey::new);

    private static final ThreadLocal<ByteBuffer> OUTPUT_SCRATCH =
            new ThreadLocal<>();

    private OmmCutoutClassifier() {
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static Result buildForMinecraftQuads(
            ByteBuffer vertices,
            int vertexCount,
            int stride
    ) {
        if (vertices == null || !vertices.isDirect() || stride != 28 ||
                vertexCount <= 0 || (vertexCount & 3) != 0) {
            return Result.empty();
        }

        final int triangleCount = vertexCount / 2;
        final int byteCount = Math.multiplyExact(
                triangleCount,
                BYTES_PER_TRIANGLE
        );
        final boolean owned = byteCount > MAX_SCRATCH_BYTES;
        final ByteBuffer output;
        if (owned) {
            output = MemoryUtil.memAlloc(byteCount);
        } else {
            ByteBuffer scratch = OUTPUT_SCRATCH.get();
            if (scratch == null || scratch.capacity() < byteCount) {
                if (scratch != null) {
                    MemoryUtil.memFree(scratch);
                }
                scratch = MemoryUtil.memAlloc(byteCount);
                OUTPUT_SCRATCH.set(scratch);
            }
            output = scratch;
        }

        output.limit(byteCount);
        output.position(0);

        MemoryUtil.memSet(MemoryUtil.memAddress(output), 0xFF, byteCount);

        final long base = MemoryUtil.memAddress(vertices);
        int knownMicroTriangles = 0;

        for (int quad = 0; quad < vertexCount / 4; ++quad) {
            final long q = base + (long) quad * 4L * stride;
            final long v0Address = q;
            final long v1Address = q + stride;
            final long v2Address = q + 2L * stride;
            final long v3Address = q + 3L * stride;
            final boolean unitVertexAlpha =
                    (MemoryUtil.memGetByte(v0Address + 15L) & 0xFF) == 255 &&
                    (MemoryUtil.memGetByte(v1Address + 15L) & 0xFF) == 255 &&
                    (MemoryUtil.memGetByte(v2Address + 15L) & 0xFF) == 255 &&
                    (MemoryUtil.memGetByte(v3Address + 15L) & 0xFF) == 255;
            if (!unitVertexAlpha) {
                continue;
            }

            final float u0 = MemoryUtil.memGetFloat(v0Address + 16L);
            final float v0 = MemoryUtil.memGetFloat(v0Address + 20L);
            final float u1 = MemoryUtil.memGetFloat(v1Address + 16L);
            final float v1 = MemoryUtil.memGetFloat(v1Address + 20L);
            final float u2 = MemoryUtil.memGetFloat(v2Address + 16L);
            final float v2 = MemoryUtil.memGetFloat(v2Address + 20L);
            final float u3 = MemoryUtil.memGetFloat(v3Address + 16L);
            final float v3 = MemoryUtil.memGetFloat(v3Address + 20L);
            final int triangleBase = quad * 2;

            knownMicroTriangles += copyClassifiedTriangle(
                    output, triangleBase, u0, v0, u1, v1, u2, v2
            );
            knownMicroTriangles += copyClassifiedTriangle(
                    output, triangleBase + 1, u0, v0, u2, v2, u3, v3
            );
        }

        return new Result(
                output,
                triangleCount,
                knownMicroTriangles,
                owned
        );
    }

    private static int copyClassifiedTriangle(
            ByteBuffer destination,
            int destinationTriangle,
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2
    ) {
        final PBRAtlasStorage.OmmSpriteRegion region =
                PBRAtlasStorage.findOmmSpriteRegion(
                        (u0 + u1 + u2) / 3.0f,
                        (v0 + v1 + v2) / 3.0f
                );
        if (region == null || region.animated()) {
            return 0;
        }

        final TriangleKey key = KEY_SCRATCH.get();
        key.set(
                region,
                Float.floatToRawIntBits(u0),
                Float.floatToRawIntBits(v0),
                Float.floatToRawIntBits(u1),
                Float.floatToRawIntBits(v1),
                Float.floatToRawIntBits(u2),
                Float.floatToRawIntBits(v2)
        );
        TriangleResult result = CACHE.get(key);
        if (result == null) {
            result = classifyTriangle(region, u0, v0, u1, v1, u2, v2);
            if (CACHE.put(new TriangleKey(key), result) == null) {
                if (CACHE_INSERTIONS.incrementAndGet() > MAX_CACHE_ENTRIES) {
                    CACHE.clear();
                    CACHE_INSERTIONS.set(0);
                }
            }
        }

        if (result.knownMicroTriangles == 0) {
            return 0;
        }

        final int offset = destinationTriangle * BYTES_PER_TRIANGLE;
        destination.put(offset, result.data, 0, BYTES_PER_TRIANGLE);
        return result.knownMicroTriangles;
    }

    private static TriangleResult classifyTriangle(
            PBRAtlasStorage.OmmSpriteRegion region,
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2
    ) {
        final byte[] data = new byte[BYTES_PER_TRIANGLE];
        java.util.Arrays.fill(data, UNKNOWN_OPAQUE_BYTE);

        if (!region.containsTriangle(u0, v0, u1, v1, u2, v2)) {
            return new TriangleResult(data, 0);
        }

        final int dimension = 1 << SUBDIVISION_LEVEL;
        final float invDimension = 1.0f / dimension;
        final int[] known = {0};

        for (int i = 0; i < dimension; ++i) {
            for (int j = 0; i + j < dimension; ++j) {
                classifyMicroTriangle(
                        data, region, u0, v0, u1, v1, u2, v2,
                        i, j, i + 1, j, i, j + 1,
                        (i + 1.0f / 3.0f) * invDimension,
                        (j + 1.0f / 3.0f) * invDimension,
                        known
                );
                if (i + j < dimension - 1) {
                    classifyMicroTriangle(
                            data, region, u0, v0, u1, v1, u2, v2,
                            i + 1, j, i + 1, j + 1, i, j + 1,
                            (i + 2.0f / 3.0f) * invDimension,
                            (j + 2.0f / 3.0f) * invDimension,
                            known
                    );
                }
            }
        }

        return new TriangleResult(data, known[0]);
    }

    private static void classifyMicroTriangle(
            byte[] data,
            PBRAtlasStorage.OmmSpriteRegion region,
            float u0, float v0, float u1, float v1, float u2, float v2,
            int ia, int ib, int ja, int jb, int ka, int kb,
            float centroidB,
            float centroidC,
            int[] known
    ) {
        final float invDimension = 1.0f / (1 << SUBDIVISION_LEVEL);
        final int state = regionState(
                region,
                interpolate(u0, u1, u2, ia * invDimension, ib * invDimension),
                interpolate(v0, v1, v2, ia * invDimension, ib * invDimension),
                interpolate(u0, u1, u2, ja * invDimension, jb * invDimension),
                interpolate(v0, v1, v2, ja * invDimension, jb * invDimension),
                interpolate(u0, u1, u2, ka * invDimension, kb * invDimension),
                interpolate(v0, v1, v2, ka * invDimension, kb * invDimension)
        );
        if (state == FULLY_OPAQUE || state == FULLY_TRANSPARENT) {
            writeValue(data, barycentricsToOmmIndex(
                    centroidB,
                    centroidC,
                    SUBDIVISION_LEVEL
            ), state);
            ++known[0];
        }
    }

    private static int regionState(
            PBRAtlasStorage.OmmSpriteRegion region,
            float u0, float v0, float u1, float v1, float u2, float v2
    ) {
        final float minU = Math.min(u0, Math.min(u1, u2));
        final float maxU = Math.max(u0, Math.max(u1, u2));
        final float minV = Math.min(v0, Math.min(v1, v2));
        final float maxV = Math.max(v0, Math.max(v1, v2));
        return region.classifyRectangle(minU, minV, maxU, maxV, ALPHA_CUTOFF);
    }

    private static float interpolate(
            float a,
            float b,
            float c,
            float barycentricB,
            float barycentricC
    ) {
        return a + (b - a) * barycentricB + (c - a) * barycentricC;
    }

    private static void writeValue(byte[] data, int microIndex, int value) {
        final int bit = microIndex * 2;
        final int byteIndex = bit >>> 3;
        final int shift = bit & 7;
        final int current = data[byteIndex] & 0xFF;
        data[byteIndex] = (byte) (
                (current & ~(0x3 << shift)) |
                        ((value & 0x3) << shift)
        );
    }

    private static int barycentricsToOmmIndex(float u, float v, int level) {
        u = clamp01(u);
        v = clamp01(v);
        final int dimension = 1 << level;
        final float fu = u * dimension;
        final float fv = v * dimension;
        int iu = (int) fu;
        int iv = (int) fv;
        final float uf = fu - iu;
        final float vf = fv - iv;
        if (iu >= dimension) {
            iu = dimension - 1;
        }
        if (iv >= dimension) {
            iv = dimension - 1;
        }
        final int iuv = iu + iv;
        if (iuv >= dimension) {
            iu -= iuv - dimension + 1;
        }
        int iw = ~(iu + iv);
        if (uf + vf >= 1.0f && iuv < dimension - 1) {
            --iw;
        }
        int b0 = ~(iu ^ iw) & (dimension - 1);
        final int t = (iu ^ iv) & b0;
        int fold = t;
        fold ^= fold >>> 1;
        fold ^= fold >>> 2;
        fold ^= fold >>> 4;
        fold ^= fold >>> 8;
        int b1 = ((fold ^ iu) & ~b0) | t;
        b0 = interleaveBits(b0);
        b1 = interleaveBits(b1);
        return b0 | (b1 << 1);
    }

    private static int interleaveBits(int value) {
        value = (value | (value << 8)) & 0x00FF00FF;
        value = (value | (value << 4)) & 0x0F0F0F0F;
        value = (value | (value << 2)) & 0x33333333;
        return (value | (value << 1)) & 0x55555555;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public record Result(
            ByteBuffer data,
            int triangleCount,
            int knownMicroTriangles,
            boolean owned
    ) {
        public static Result empty() {
            return new Result(null, 0, 0, false);
        }

        public void free() {
            if (owned && data != null) {
                MemoryUtil.memFree(data);
            }
        }
    }

    // Mutable lookup key: reused per thread for CACHE.get without allocating.
    // Instances that are actually stored in CACHE are always fresh copies, so
    // the reuse below can never mutate an entry already in the map.
    private static final class TriangleKey {
        private PBRAtlasStorage.OmmSpriteRegion region;
        private int u0, v0, u1, v1, u2, v2;

        private TriangleKey() {
        }

        private TriangleKey(TriangleKey other) {
            this.region = other.region;
            this.u0 = other.u0;
            this.v0 = other.v0;
            this.u1 = other.u1;
            this.v1 = other.v1;
            this.u2 = other.u2;
            this.v2 = other.v2;
        }

        private void set(
                PBRAtlasStorage.OmmSpriteRegion region,
                int u0, int v0, int u1, int v1, int u2, int v2
        ) {
            this.region = region;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(region);
            hash = hash * 31 + u0;
            hash = hash * 31 + v0;
            hash = hash * 31 + u1;
            hash = hash * 31 + v1;
            hash = hash * 31 + u2;
            hash = hash * 31 + v2;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TriangleKey other)) {
                return false;
            }
            return u0 == other.u0 && v0 == other.v0 &&
                    u1 == other.u1 && v1 == other.v1 &&
                    u2 == other.u2 && v2 == other.v2 &&
                    region.equals(other.region);
        }
    }

    private record TriangleResult(byte[] data, int knownMicroTriangles) {
    }
}
