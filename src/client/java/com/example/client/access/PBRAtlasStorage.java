package com.example.client.access;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class PBRAtlasStorage {
    public static NativeImage globalNormalAtlas = null;
    public static NativeImage globalSpecularAtlas = null;

    // VPT-1.3: one RGB value per atlas sprite, expanded over that sprite's
    // atlas rectangle.  Section compilation reads this CPU image by mesh UV;
    // it is never uploaded as a Vulkan texture.
    public static NativeImage globalVptAlbedoAtlas = null;

    // OMM uses the original sprite alpha, never the PBR atlas.  Regions are
    // rebuilt on every block-atlas reload and are read-only while sections are
    // compiled on worker threads.
    private static volatile long ommRegionGeneration;

    // Spatial index over ommSpriteRegions: a uniform UV grid where each cell
    // holds, in list order, every region whose rectangle overlaps the cell.
    // A bucket scan visits a strict subset of the previous linear scan in the
    // same relative order, so the first containing region -- and therefore the
    // returned answer -- is identical to the full scan.
    private static final int REGION_GRID = 64;
    private static volatile List<OmmSpriteRegion>[] ommRegionGrid = emptyRegionGrid();

    @SuppressWarnings("unchecked")
    private static List<OmmSpriteRegion>[] emptyRegionGrid() {
        return new List[REGION_GRID * REGION_GRID];
    }
    private static final ThreadLocal<RegionLookup> LAST_OMM_REGION =
            ThreadLocal.withInitial(RegionLookup::new);

    // Bumped on every block-atlas reload. The bind hook in
    // VulkanRenderPassAtlasHijackMixin caches the last (epoch, view) pair, so
    // a reload still forces its push when the driver hands the replacement the
    // freed view's numeric value -- the one case a handle comparison alone
    // reads as "nothing changed".  Lives here rather than in the mixin because
    // a static member of a mixin is merged into the target class, leaving the
    // mixin's own copy untouched.
    private static final AtomicLong ATLAS_EPOCH = new AtomicLong();

    public static void bumpAtlasEpoch() {
        ATLAS_EPOCH.incrementAndGet();
    }

    public static long atlasEpoch() {
        return ATLAS_EPOCH.get();
    }

    public static void setOmmSpriteRegions(List<OmmSpriteRegion> regions) {
        final List<OmmSpriteRegion> copy = List.copyOf(regions);
        ommRegionGrid = buildRegionGrid(copy);
        ++ommRegionGeneration;
        OmmCutoutClassifier.clearCache();
    }

    private static List<OmmSpriteRegion>[] buildRegionGrid(
            List<OmmSpriteRegion> regions
    ) {
        @SuppressWarnings("unchecked")
        final List<OmmSpriteRegion>[] grid = new List[REGION_GRID * REGION_GRID];
        for (OmmSpriteRegion region : regions) {
            // Register with the same epsilon padding containsPoint accepts, so
            // a point found by the old linear scan always lands in a cell the
            // region was registered under.
            final int minU = regionCell(region.u0() - OmmSpriteRegion.UV_EPSILON);
            final int maxU = regionCell(region.u1() + OmmSpriteRegion.UV_EPSILON);
            final int minV = regionCell(region.v0() - OmmSpriteRegion.UV_EPSILON);
            final int maxV = regionCell(region.v1() + OmmSpriteRegion.UV_EPSILON);
            for (int cy = minV; cy <= maxV; ++cy) {
                for (int cx = minU; cx <= maxU; ++cx) {
                    final int index = cy * REGION_GRID + cx;
                    List<OmmSpriteRegion> bucket = grid[index];
                    if (bucket == null) {
                        bucket = new java.util.ArrayList<>(1);
                        grid[index] = bucket;
                    }
                    bucket.add(region);
                }
            }
        }
        return grid;
    }

    private static int regionCell(float uv) {
        // Clamp instead of rejecting: atlas edge regions accept points a hair
        // outside [0,1] (containsPoint epsilon), and the old scan found them.
        final int cell = (int) (uv * REGION_GRID);
        return Math.max(0, Math.min(REGION_GRID - 1, cell));
    }

    // MC 26.3 derives the chunk layer of every quad from the sprite's own
    // pixels (ChunkSectionLayer.byTransparency), and one anti-aliased texel is
    // enough to move leaves, grass and vines into the TRANSLUCENT layer beside
    // windows.  Inside that layer a window is a sprite whose *body* is
    // half-transparent: most of its area is neither invisible nor opaque.
    // Cutout art with a soft edge -- leaves, grass, vines, bars, chains --
    // spends its anti-aliased texels on a thin fringe and stays either mostly
    // invisible or mostly opaque, so it fails this test.  The mean alpha of
    // the visible texels was tried first and cannot do the job: fire is
    // painted as a soft low-alpha blob, so it averages darker than most glass
    // while sharing none of its behaviour.
    private static final float SEE_THROUGH_BODY_FRACTION = 0.5f;

    // Alpha this high or higher is opaque body, not transmission.
    private static final int OPAQUE_ALPHA = 250;

    // The visible texels must also average well below opaque, so a sprite that
    // is "half-transparent" only through a broad near-opaque gradient is not
    // misread as a window.
    private static final float SEE_THROUGH_MAX_MEAN_ALPHA = 240.0f;

    // Precomputed once per sprite at atlas build time; a section compile then
    // reads one boolean instead of walking pixels per quad.
    public static boolean computeSeeThrough(NativeImage source) {
        if (source == null) {
            return false;
        }

        final int width = source.getWidth();
        final int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }

        final long pixels = source.getPointer();
        final long total = (long) width * (long) height;
        long visible = 0L;
        long body = 0L;
        long alphaSum = 0L;

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                final int alpha = org.lwjgl.system.MemoryUtil.memGetByte(
                        pixels + ((long) y * width + x) * 4L + 3L
                ) & 0xFF;

                if (alpha < OmmCutoutClassifier.ALPHA_CUTOFF) {
                    continue;
                }

                ++visible;
                alphaSum += alpha;

                if (alpha < OPAQUE_ALPHA) {
                    ++body;
                }
            }
        }

        if (visible == 0L) {
            // Fully transparent everywhere: no material at all, so it must
            // never be published as a see-through body.
            return false;
        }

        if ((float) body <= SEE_THROUGH_BODY_FRACTION * (float) total) {
            return false;
        }

        final float meanVisibleAlpha = (float) alphaSum / (float) visible;
        return meanVisibleAlpha < SEE_THROUGH_MAX_MEAN_ALPHA;
    }

    // Answers for the quad currently being tagged.  An unknown footprint keeps
    // the historical answer: before the texture was consulted at all, every
    // unclassified translucent-layer quad was published as clear glass.
    public static boolean spriteIsSeeThrough(float u, float v) {
        final OmmSpriteRegion region = findOmmSpriteRegion(u, v);
        return region == null || region.seeThrough();
    }

    public static OmmSpriteRegion findOmmSpriteRegion(float u, float v) {
        RegionLookup lookup = LAST_OMM_REGION.get();
        long generation = ommRegionGeneration;
        if (lookup.generation == generation && lookup.region != null &&
                lookup.region.containsPoint(u, v)) {
            return lookup.region;
        }

        OmmSpriteRegion found = null;
        if (Float.isFinite(u) && Float.isFinite(v)) {
            final List<OmmSpriteRegion>[] grid = ommRegionGrid;
            final int index =
                    regionCell(v) * REGION_GRID + regionCell(u);
            final List<OmmSpriteRegion> bucket = grid[index];
            if (bucket != null) {
                for (OmmSpriteRegion region : bucket) {
                    if (region.containsPoint(u, v)) {
                        found = region;
                        break;
                    }
                }
            }
        }
        lookup.generation = generation;
        lookup.region = found;
        return found;
    }

    private static final class RegionLookup {
        private long generation = Long.MIN_VALUE;
        private OmmSpriteRegion region;
    }

    public record OmmSpriteRegion(
            float u0,
            float v0,
            float u1,
            float v1,
            NativeImage source,
            boolean animated,
            boolean seeThrough
    ) {
        private static final float UV_EPSILON = 1.0e-6f;

        public boolean containsPoint(float u, float v) {
            return Float.isFinite(u) && Float.isFinite(v) &&
                    u >= u0 - UV_EPSILON && u <= u1 + UV_EPSILON &&
                    v >= v0 - UV_EPSILON && v <= v1 + UV_EPSILON;
        }

        public boolean containsTriangle(
                float aU, float aV,
                float bU, float bV,
                float cU, float cV
        ) {
            return containsPoint(aU, aV) &&
                    containsPoint(bU, bV) &&
                    containsPoint(cU, cV);
        }

        /**
         * Returns 0 for fully transparent, 1 for fully opaque and -1 for an
         * unresolved/mixed footprint.  Unresolved values intentionally remain
         * UNKNOWN_OPAQUE in the micromap and still execute .rahit.
         */
        public int classifyRectangle(
                float minU,
                float minV,
                float maxU,
                float maxV,
                int alphaCutoff
        ) {
            if (source == null || !containsPoint(minU, minV) ||
                    !containsPoint(maxU, maxV)) {
                return -1;
            }

            final float spanU = u1 - u0;
            final float spanV = v1 - v0;
            if (Math.abs(spanU) < 1.0e-12f ||
                    Math.abs(spanV) < 1.0e-12f) {
                return -1;
            }

            float localMinU = clamp01((minU - u0) / spanU);
            float localMaxU = clamp01((maxU - u0) / spanU);
            float localMinV = clamp01((minV - v0) / spanV);
            float localMaxV = clamp01((maxV - v0) / spanV);
            if (localMinU > localMaxU) {
                final float swap = localMinU;
                localMinU = localMaxU;
                localMaxU = swap;
            }
            if (localMinV > localMaxV) {
                final float swap = localMinV;
                localMinV = localMaxV;
                localMaxV = swap;
            }

            final int width = source.getWidth();
            final int height = source.getHeight();
            if (width <= 0 || height <= 0) {
                return -1;
            }

            final float epsilon = 1.0e-3f;
            final int x0 = clamp((int) Math.floor(localMinU * width + epsilon), 0, width - 1);
            final int y0 = clamp((int) Math.floor(localMinV * height + epsilon), 0, height - 1);
            final int x1 = clamp((int) Math.ceil(localMaxU * width - epsilon), x0 + 1, width);
            final int y1 = clamp((int) Math.ceil(localMaxV * height - epsilon), y0 + 1, height);

            boolean visible = false;
            boolean invisible = false;
            final long pixels = source.getPointer();
            for (int y = y0; y < y1; ++y) {
                for (int x = x0; x < x1; ++x) {
                    final int alpha = org.lwjgl.system.MemoryUtil.memGetByte(
                            pixels + ((long) y * width + x) * 4L + 3L
                    ) & 0xFF;
                    if (alpha >= alphaCutoff) {
                        visible = true;
                    } else {
                        invisible = true;
                    }
                    if (visible && invisible) {
                        return -1;
                    }
                }
            }
            return visible ? 1 : invisible ? 0 : -1;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float clamp01(float value) {
            return Math.max(0.0f, Math.min(1.0f, value));
        }
    }

    private PBRAtlasStorage() {}
}
