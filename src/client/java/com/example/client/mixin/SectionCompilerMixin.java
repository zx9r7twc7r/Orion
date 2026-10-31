package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;
import com.example.client.access.PBRAtlasStorage;
import com.example.client.access.OmmCutoutClassifier;
import com.example.client.access.SectionUploadDedup;
import com.example.client.access.RtStateCache;
import com.example.client.compat.FarFieldGate;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {


    private static final int SECTION_EDGE = 16;
    private static final int SECTION_VOXEL_COUNT =
            SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;
    private static final int SECTION_VOXEL_BYTES =
            SECTION_VOXEL_COUNT * Integer.BYTES;

    private static final int VLI_VOXEL_EMPTY = 0;
    private static final int VLI_VOXEL_OPAQUE = 1;
    private static final int VLI_VOXEL_TRANSLUCENT = 2;
    private static final int VLI_VOXEL_PARTIAL = 3;
    private static final int VLI_STAINED_GLASS_BIT = 1 << 24;
    private static final int VLI_PARTIAL_SHIFT = 25;
    private static final int VLI_PARTIAL_MAX = 127;
    private static final boolean DEBUG = Boolean.getBoolean("rtx.debug");

    private static final int TERRAIN_LAYER_MASK =
            (1 << ChunkSectionLayer.values().length) - 1;


    private static final int RT_STATE_GLASS_SHIFT = 0;
    private static final int RT_STATE_GLASS_MASK = 0x1F;
    private static final int RT_STATE_FILL_SHIFT = 5;
    private static final int RT_STATE_FILL_MASK = 0x3FF;
    private static final int RT_STATE_TRANSLUCENT_SHIFT = 15;
    private static final int RT_STATE_TRANSLUCENT_MASK = 0x1F;
    private static final int RT_STATE_VALID_BIT = 1 << 20;

    private static int rtStateCache(BlockState state) {
        final RtStateCache duck = (RtStateCache) (Object) state;
        final int cached = duck.rt$getStateCache();
        if ((cached & RT_STATE_VALID_BIT) != 0) {
            return cached;
        }

        final Block block = state.getBlock();
        final int glassMarker = resolveTrackedGlassMarker(block) & RT_STATE_GLASS_MASK;

        int fillPermille = 1000;
        if (state.canOcclude() && !state.isSolidRender()) {
            double[] volume = {0.0};
            state.getOcclusionShape().forAllBoxes(
                    (minX, minY, minZ, maxX, maxY, maxZ) ->
                            volume[0] += (maxX - minX) * (maxY - minY) * (maxZ - minZ)
            );
            fillPermille = Math.max(
                    0,
                    Math.min(1000, (int) Math.round(volume[0] * 1000.0))
            );
        }

        final int translucentMarker =
                resolveTranslucentMarker(block) & RT_STATE_TRANSLUCENT_MASK;

        final int computed = (glassMarker << RT_STATE_GLASS_SHIFT)
                | (fillPermille << RT_STATE_FILL_SHIFT)
                | (translucentMarker << RT_STATE_TRANSLUCENT_SHIFT)
                | RT_STATE_VALID_BIT;
        duck.rt$setStateCache(computed);
        return computed;
    }

    private static int occlusionFillPermille(BlockState state) {
        return (rtStateCache(state) >> RT_STATE_FILL_SHIFT) & RT_STATE_FILL_MASK;
    }

    private static final ThreadLocal<SectionScratch> SECTION_SCRATCH =
            ThreadLocal.withInitial(SectionScratch::new);

    private static final IdentityHashMap<Block, Integer> STAINED_PANE_MARKERS =
            createStainedPaneMarkers();



    private static final int RT_MARKER_GENERIC_TRANSLUCENT = 2;
    private static final int RT_MARKER_TINTED_GLASS = 19;
    private static final int RT_MARKER_ICE = 20;
    private static final int RT_MARKER_SLIME = 21;
    private static final int RT_MARKER_HONEY = 22;
    private static final int RT_MARKER_NETHER_PORTAL = 24;
    private static final int RT_MARKER_FOLIAGE = 25;

    private static final int GLASS_CONNECT_MASK_SHIFT = 8;
    private static final int GLASS_CONNECT_MASK_BITS = 0x0F;
    private static final int GLASS_FLAT_CUBE_BIT = 0x10;
    private static final int GLASS_PAD_BITS =
            GLASS_CONNECT_MASK_BITS | GLASS_FLAT_CUBE_BIT;
    private static final int GLASS_PAD_CLEAR =
            ~(GLASS_PAD_BITS << GLASS_CONNECT_MASK_SHIFT);



    private static final int SC_REPORT_WINDOW = 256;

    private static volatile Object SC_DEDUP_LEVEL = null;

    private static final AtomicLong SC_COUNT = new AtomicLong();
    private static final AtomicLong SC_TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong SC_MAX_NANOS = new AtomicLong();
    private static final AtomicLong SC_TAG_NANOS = new AtomicLong();
    private static final AtomicLong SC_FUSED_NANOS = new AtomicLong();
    private static final AtomicLong SC_JNI_NANOS = new AtomicLong();
    private static final AtomicLong SC_OMM_NANOS = new AtomicLong();
    private static final AtomicLong SC_CLEAR_NANOS = new AtomicLong();
    private static final AtomicLong SC_VOXEL_NANOS = new AtomicLong();
    private static final AtomicLong SC_VOXEL_LOOP_NANOS = new AtomicLong();
    private static final AtomicLong SC_VOXEL_JNI_NANOS = new AtomicLong();

    private static final boolean SECTION_FAST_FILL =
            Boolean.getBoolean("rtx.sectionFastFill");
    private static final boolean PALETTE_DIAG =
            Boolean.getBoolean("rtx.diag.sectionPalette");

    private static final AtomicLong PD_SECTIONS = new AtomicLong();
    private static final AtomicLong PD_NO_SNAPSHOT = new AtomicLong();
    private static final AtomicLong PD_NO_PALETTE = new AtomicLong();
    private static final AtomicLong PD_AIR_ONLY = new AtomicLong();
    private static final AtomicLong PD_SINGLE_NONAIR = new AtomicLong();
    private static final AtomicLong PD_SINGLE_NONAIR_CLEAN = new AtomicLong();
    private static final AtomicLong PD_VPT_ANY = new AtomicLong();
    private static final AtomicLong PD_HAS_GLASS = new AtomicLong();
    private static final AtomicLong PD_HAS_EMISSIVE = new AtomicLong();
    private static final AtomicLong PD_HAS_PARTIAL = new AtomicLong();
    private static final AtomicLong PD_PALETTE_ENTRIES = new AtomicLong();
    private static final AtomicLong PD_PALETTE_MAX = new AtomicLong();

    // 零分配地遍历 palette：SectionScratch 每 worker 一份，避免 lambda/数组逃逸。
    private static final class PaletteProbe implements java.util.function.Consumer<BlockState> {
        int count;
        BlockState first;

        void reset() {
            count = 0;
            first = null;
        }

        @Override
        public void accept(BlockState state) {
            if (count == 0) {
                first = state;
            }
            ++count;
        }
    }

    private static SectionCopy sectionCopyAt(
            SectionPos sectionPos,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region
    ) {
        if (!(region instanceof RenderSectionRegionAccessor accessor)) {
            return null;
        }
        return accessor.invokeGetSection(
                sectionPos.x(),
                sectionPos.y(),
                sectionPos.z()
        );
    }

    private static void paletteDiagReport() {
        final long n = PD_SECTIONS.getAndSet(0L);
        if (n <= 0L) {
            return;
        }
        final long noSnapshot = PD_NO_SNAPSHOT.getAndSet(0L);
        final long noPalette = PD_NO_PALETTE.getAndSet(0L);
        final long airOnly = PD_AIR_ONLY.getAndSet(0L);
        final long singleNonAir = PD_SINGLE_NONAIR.getAndSet(0L);
        final long singleNonAirClean = PD_SINGLE_NONAIR_CLEAN.getAndSet(0L);
        final long vptAny = PD_VPT_ANY.getAndSet(0L);
        final long hasGlass = PD_HAS_GLASS.getAndSet(0L);
        final long hasEmissive = PD_HAS_EMISSIVE.getAndSet(0L);
        final long hasPartial = PD_HAS_PARTIAL.getAndSet(0L);
        final long entries = PD_PALETTE_ENTRIES.getAndSet(0L);
        final long paletteMax = PD_PALETTE_MAX.getAndSet(0L);

        System.out.println(
                "[SECTION PALETTE]"
                        + " n=" + n
                        + " noSnapshot=" + noSnapshot
                        + " emptyChunk=" + noPalette
                        + " airOnly=" + airOnly
                        + " singleNonAir=" + singleNonAir
                        + " singleNonAirClean=" + singleNonAirClean
                        + " vptAny=" + vptAny
                        + " hasGlass=" + hasGlass
                        + " hasEmissive=" + hasEmissive
                        + " hasPartial=" + hasPartial
                        + " avgPalette=" + scRound((double) entries / n)
                        + " maxPalette=" + paletteMax
                        + "  (每段 palette 条目数/特征，诊断用)"
        );
    }

    private static void scReport() {
        final long n = SC_COUNT.getAndSet(0L);
        if (n <= 0L) {
            return;
        }

        final double totalMs =
                SC_TOTAL_NANOS.getAndSet(0L) / 1_000_000.0;
        final double maxMs =
                SC_MAX_NANOS.getAndSet(0L) / 1_000_000.0;
        final double tagMs =
                SC_TAG_NANOS.getAndSet(0L) / 1_000_000.0;
        final double fusedMs =
                SC_FUSED_NANOS.getAndSet(0L) / 1_000_000.0;
        final double jniMs =
                SC_JNI_NANOS.getAndSet(0L) / 1_000_000.0;
        final double ommMs =
                SC_OMM_NANOS.getAndSet(0L) / 1_000_000.0;
        final double clearMs =
                SC_CLEAR_NANOS.getAndSet(0L) / 1_000_000.0;
        final double voxelMs =
                SC_VOXEL_NANOS.getAndSet(0L) / 1_000_000.0;
        final double voxelLoopMs =
                SC_VOXEL_LOOP_NANOS.getAndSet(0L) / 1_000_000.0;
        final double voxelJniMs =
                SC_VOXEL_JNI_NANOS.getAndSet(0L) / 1_000_000.0;

        System.out.println(
                "[SECTION COMPILE]"
                        + " n=" + n
                        + " avgMs=" + scRound(totalMs / n)
                        + " maxMs=" + scRound(maxMs)
                        + " tagMs=" + scRound(tagMs / n)
                        + " fusedMs=" + scRound(fusedMs / n)
                        + " jniMs=" + scRound(jniMs / n)
                        + " ommMs=" + scRound(ommMs / n)
                        + " clearMs=" + scRound(clearMs / n)
                        + " voxelMs=" + scRound(voxelMs / n)
                        + " vxLoopMs=" + scRound(voxelLoopMs / n)
                        + " vxJniMs=" + scRound(voxelJniMs / n)
                        + "  (= worker 侧每个 section 的 RT 上传路径分相，"
                        + SC_REPORT_WINDOW + " 个一报)"
        );

        if (PALETTE_DIAG) {
            paletteDiagReport();
        }
    }

    private static double scRound(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static IdentityHashMap<Block, Integer> createStainedPaneMarkers() {
        IdentityHashMap<Block, Integer> markers =
                new IdentityHashMap<>(32);

        for (net.minecraft.world.item.DyeColor dye :
                net.minecraft.world.item.DyeColor.values()) {
            markers.put(
                    Blocks.STAINED_GLASS_PANE.pick(
                            dye
                    ),
                    3 + dye.getId()
            );
        }

        return markers;
    }


    @Inject(method = "compile", at = @At("RETURN"))
    private void onCompileReturn(SectionPos sectionPos, net.minecraft.client.renderer.chunk.RenderSectionRegion region, com.mojang.blaze3d.vertex.VertexSorting vertexSorting, net.minecraft.client.renderer.SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionCompiler.Results results = cir.getReturnValue();
        if (results == null) return;

        if (FarFieldGate.shouldSuppress(sectionPos)) {
            FarFieldGate.recordState(sectionPos, false);
            return;
        }
        FarFieldGate.recordState(sectionPos, true);

        final long scStartNanos = System.nanoTime();
        long scTagNanos = 0L;
        long scFusedNanos = 0L;
        long scJniNanos = 0L;
        long scOmmNanos = 0L;
        long scClearNanos = 0L;
        long scVoxelNanos = 0L;



        final SectionScratch scratch = SECTION_SCRATCH.get();
        scratch.clearVoxelAccumulators();
        final int[] vptRed = scratch.vptRed;
        final int[] vptGreen = scratch.vptGreen;
        final int[] vptBlue = scratch.vptBlue;
        final int[] vptCount = scratch.vptCount;

        int uploadedLayerMask = 0;

        int matchedTranslucentQuads = 0;

        for (Map.Entry<net.minecraft.client.renderer.chunk.ChunkSectionLayer, com.mojang.blaze3d.vertex.MeshData> entry : results.renderedLayers.entrySet()) {
            com.mojang.blaze3d.vertex.MeshData mesh = entry.getValue();
            if (mesh != null) {
                var drawState = mesh.drawState();

                ByteBuffer vertexBuffer = mesh.vertexBuffer();

                int vertexCount = drawState.vertexCount();
                int stride = drawState.format().getVertexSize();
                int layerId =
                        entry.getKey().ordinal();

                uploadedLayerMask |= 1 << layerId;

                if (vertexBuffer != null &&
                        vertexBuffer.isDirect() &&
                        vertexCount > 0) {

                    final boolean translucentLayer =
                            entry.getKey().name().contains("TRANSLUCENT");

                    if (translucentLayer) {
                        final long scTTag = System.nanoTime();
                        matchedTranslucentQuads += tagTranslucentQuads(
                                sectionPos,
                                region,
                                vertexBuffer,
                                vertexCount,
                                stride
                        );

                        matchedTranslucentQuads += fillUntaggedTranslucentQuads(
                                vertexBuffer,
                                vertexCount,
                                stride
                        );
                        scTagNanos += System.nanoTime() - scTTag;
                    } else {
                        final long scTFused = System.nanoTime();
                        accumulateVptAlbedoAndTagRainPuddles(
                                sectionPos,
                                region,
                                vertexBuffer,
                                vertexCount,
                                stride,
                                vptRed,
                                vptGreen,
                                vptBlue,
                                vptCount,
                                entry.getKey().ordinal() == 1,
                                scratch
                        );
                        scFusedNanos += System.nanoTime() - scTFused;
                    }

                    ByteBuffer scCulledBuffer = null;

                    if (translucentLayer) {
                        final ByteBuffer[] scCullOut = new ByteBuffer[1];
                        final int scCulledVerts = cullInteriorGlassFaces(
                                sectionPos,
                                region,
                                vertexBuffer,
                                vertexCount,
                                stride,
                                scCullOut
                        );

                        if (scCulledVerts >= 0) {
                            scCulledBuffer = scCullOut[0];
                            vertexCount = scCulledVerts;
                        }
                    }

                    long pointer =
                            MemoryUtil.memAddress(
                                    scCulledBuffer != null
                                            ? scCulledBuffer
                                            : vertexBuffer
                            );

                    long scChunkHash = 0L;

                    if (SectionUploadDedup.enabled() && vertexCount > 0) {
                        final Object level =
                                net.minecraft.client.Minecraft.getInstance().level;

                        if (SC_DEDUP_LEVEL != level) {
                            SC_DEDUP_LEVEL = level;
                            SectionUploadDedup.invalidateAll();
                        }

                        final long scKey =
                                SectionUploadDedup.key(
                                        sectionPos.x(),
                                        sectionPos.y(),
                                        sectionPos.z(),
                                        layerId
                                );

                        scChunkHash =
                                SectionUploadDedup.hash(pointer, vertexCount * stride);

                        if (SectionUploadDedup.shouldSkip(scKey, scChunkHash)) {
                            continue;
                        }
                    }

                    final long scTJni = System.nanoTime();
                    try {
                        NativeVulkan.uploadChunk(
                                sectionPos.x(),
                                sectionPos.y(),
                                sectionPos.z(),
                                layerId,
                                pointer,
                                vertexCount,
                                stride
                        );
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    scJniNanos += System.nanoTime() - scTJni;

                    if (SectionUploadDedup.enabled() && vertexCount > 0) {
                        SectionUploadDedup.record(
                                SectionUploadDedup.key(
                                        sectionPos.x(),
                                        sectionPos.y(),
                                        sectionPos.z(),
                                        layerId
                                ),
                                scChunkHash
                        );
                    }

                    if (layerId == 1) {
                        final long scTOmm = System.nanoTime();
                        final OmmCutoutClassifier.Result omm =
                                OmmCutoutClassifier.buildForMinecraftQuads(
                                        vertexBuffer,
                                        vertexCount,
                                        stride
                                );
                        try {
                            NativeVulkan.uploadCutoutOmmSection(
                                    sectionPos.x(),
                                    sectionPos.y(),
                                    sectionPos.z(),
                                    omm.data() == null
                                            ? 0L
                                            : MemoryUtil.memAddress(omm.data()),
                                    omm.data() == null
                                            ? 0
                                            : omm.data().limit(),
                                    omm.triangleCount(),
                                    OmmCutoutClassifier.SUBDIVISION_LEVEL,
                                    omm.knownMicroTriangles()
                            );
                        } catch (Throwable t) {
                            t.printStackTrace();
                        } finally {
                            omm.free();
                        }
                        scOmmNanos += System.nanoTime() - scTOmm;
                    }
                }
            }
        }

        final int missingLayerMask =
                (~uploadedLayerMask) & TERRAIN_LAYER_MASK;

        if (missingLayerMask != 0) {
            final long scTClear = System.nanoTime();
            try {
                NativeVulkan.clearChunkLayers(
                        sectionPos.x(),
                        sectionPos.y(),
                        sectionPos.z(),
                        missingLayerMask
                );
            } catch (Throwable t) {
                t.printStackTrace();
            }
            scClearNanos += System.nanoTime() - scTClear;

            SectionUploadDedup.invalidateSection(
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    missingLayerMask
            );
        }

        final long scTVoxel = System.nanoTime();
        final int glassBlockCount = uploadSectionVoxelLight(
                sectionPos,
                region,
                vptRed,
                vptGreen,
                vptBlue,
                vptCount,
                scratch
        );
        scVoxelNanos += System.nanoTime() - scTVoxel;

        


        if (DEBUG && matchedTranslucentQuads > 0) {
            System.out.println(
                    "[T1 TRANSLUCENT MARKER]"
                            + " section=("
                            + sectionPos.x()
                            + ","
                            + sectionPos.y()
                            + ","
                            + sectionPos.z()
                            + ")"
                            + " glassBlocks="
                            + glassBlockCount
                            + " matchedQuads="
                            + matchedTranslucentQuads
            );
        }

        final long scTotalNanos = System.nanoTime() - scStartNanos;
        SC_TOTAL_NANOS.addAndGet(scTotalNanos);
        SC_MAX_NANOS.accumulateAndGet(scTotalNanos, Math::max);
        SC_TAG_NANOS.addAndGet(scTagNanos);
        SC_FUSED_NANOS.addAndGet(scFusedNanos);
        SC_JNI_NANOS.addAndGet(scJniNanos);
        SC_OMM_NANOS.addAndGet(scOmmNanos);
        SC_CLEAR_NANOS.addAndGet(scClearNanos);
        SC_VOXEL_NANOS.addAndGet(scVoxelNanos);

        if (SC_COUNT.incrementAndGet() % SC_REPORT_WINDOW == 0L) {
            scReport();
        }
    }


    private static int getTrackedGlassMarker(
            BlockState blockState
    ) {
        return (rtStateCache(blockState) >> RT_STATE_GLASS_SHIFT) & RT_STATE_GLASS_MASK;
    }



    private static int getTranslucentMarker(
            BlockState blockState
    ) {
        return (rtStateCache(blockState) >> RT_STATE_TRANSLUCENT_SHIFT)
                & RT_STATE_TRANSLUCENT_MASK;
    }


    private static int resolveTranslucentMarker(
            Block block
    ) {
        if (block == Blocks.NETHER_PORTAL) {
            return RT_MARKER_NETHER_PORTAL;
        }

        final int glassMarker =
                resolveTrackedGlassMarker(
                        block
                );

        if (glassMarker >= 2) {
            return glassMarker;
        }

        if (block == Blocks.TINTED_GLASS) {
            return RT_MARKER_TINTED_GLASS;
        }

        if (block == Blocks.ICE ||
                block == Blocks.PACKED_ICE ||
                block == Blocks.BLUE_ICE ||
                block == Blocks.FROSTED_ICE) {
            return RT_MARKER_ICE;
        }

        if (block == Blocks.SLIME_BLOCK) {
            return RT_MARKER_SLIME;
        }

        if (block == Blocks.HONEY_BLOCK) {
            return RT_MARKER_HONEY;
        }

        final BlockState state =
                block.defaultBlockState();

        if (!state.getFluidState().isEmpty()) {
            return 0;
        }

        return 0;
    }


    private static int resolveTrackedGlassMarker(
            Block block
    ) {
        if (block == Blocks.GLASS) {
            return 2;
        }

        if (block instanceof StainedGlassBlock stainedGlass) {
            final int dyeId = Math.max(
                    0,
                    Math.min(
                            15,
                            stainedGlass.getColor().getId()
                    )
            );

            return 3 + dyeId;
        }

        if (block == Blocks.GLASS_PANE) {
            return 2;
        }

        final Integer paneMarker =
                STAINED_PANE_MARKERS.get(
                        block
                );

        if (paneMarker != null) {
            return paneMarker;
        }

        final String path =
                BuiltInRegistries.BLOCK
                        .getKey(block)
                        .getPath();

        if ("glass_pane".equals(path)) {
            return 2;
        }

        if (path.endsWith("_stained_glass_pane")) {
            final String dyeName =
                    path.substring(
                            0,
                            path.length() - "_stained_glass_pane".length()
                    );

            for (int dyeId = 0; dyeId < 16; ++dyeId) {
                if (net.minecraft.world.item.DyeColor.byId(dyeId)
                        .getName()
                        .equals(dyeName)) {
                    return 3 + dyeId;
                }
            }
        }

        return 0;
    }



    private static boolean isGlassFamilyMarker(
            int marker
    ) {
        return marker >= 2 && marker <= RT_MARKER_TINTED_GLASS;
    }


    private static boolean isConnectableGlass(
            BlockState state
    ) {
        return getTrackedGlassMarker(state) >= 2 ||
                state.getBlock() == Blocks.TINTED_GLASS;
    }


    private static boolean isFlatGlassOwner(
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            BlockPos.MutableBlockPos ownerCandidateA,
            BlockPos.MutableBlockPos ownerCandidateB
    ) {
        final Block ownerA = region.getBlockState(
                ownerCandidateA
        ).getBlock();

        final Block ownerB = region.getBlockState(
                ownerCandidateB
        ).getBlock();

        return isPlainGlassBlock(ownerA) || isPlainGlassBlock(ownerB);
    }


    private static boolean isPlainGlassBlock(
            Block block
    ) {
        return block == Blocks.GLASS ||
                block == Blocks.GLASS_PANE;
    }


    private static int computeGlassConnectMask(
            float[] x,
            float[] y,
            float[] z,
            float[] u,
            float[] v,
            float centerX,
            float centerY,
            float centerZ,
            int minX,
            int minY,
            int minZ,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            BlockPos.MutableBlockPos scratch
    ) {
        final float minU = Math.min(Math.min(u[0], u[1]), Math.min(u[2], u[3]));
        final float maxU = Math.max(Math.max(u[0], u[1]), Math.max(u[2], u[3]));
        final float minV = Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3]));
        final float maxV = Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3]));

        final int ownCellX = minX + (int) Math.floor(centerX);
        final int ownCellY = minY + (int) Math.floor(centerY);
        final int ownCellZ = minZ + (int) Math.floor(centerZ);

        int mask = 0;

        for (int edge = 0; edge < 4; ++edge) {
            final int a = edge;
            final int b = (edge + 1) & 3;

            final float midX = 0.5f * (x[a] + x[b]);
            final float midY = 0.5f * (y[a] + y[b]);
            final float midZ = 0.5f * (z[a] + z[b]);

            final int bit;

            if (Math.abs(u[a] - u[b]) <= Math.abs(v[a] - v[b])) {
                final float edgeU = 0.5f * (u[a] + u[b]);
                bit = Math.abs(edgeU - minU) <= Math.abs(edgeU - maxU)
                        ? 0x1
                        : 0x2;
            } else {
                final float edgeV = 0.5f * (v[a] + v[b]);
                bit = Math.abs(edgeV - minV) <= Math.abs(edgeV - maxV)
                        ? 0x4
                        : 0x8;
            }

            float stepX = midX - centerX;
            float stepY = midY - centerY;
            float stepZ = midZ - centerZ;

            final float ax = Math.abs(stepX);
            final float ay = Math.abs(stepY);
            final float az = Math.abs(stepZ);

            if (ax >= ay && ax >= az) {
                stepX = stepX < 0.0f ? -0.5f : 0.5f;
                stepY = 0.0f;
                stepZ = 0.0f;
            } else if (ay >= az) {
                stepX = 0.0f;
                stepY = stepY < 0.0f ? -0.5f : 0.5f;
                stepZ = 0.0f;
            } else {
                stepX = 0.0f;
                stepY = 0.0f;
                stepZ = stepZ < 0.0f ? -0.5f : 0.5f;
            }

            final int probeX = minX + (int) Math.floor(midX + stepX);
            final int probeY = minY + (int) Math.floor(midY + stepY);
            final int probeZ = minZ + (int) Math.floor(midZ + stepZ);

            if (probeX == ownCellX &&
                    probeY == ownCellY &&
                    probeZ == ownCellZ) {
                continue;
            }

            scratch.set(
                    probeX,
                    probeY,
                    probeZ
            );

            if (isConnectableGlass(region.getBlockState(scratch))) {
                mask |= bit;
            }
        }

        return mask;
    }


    

    private static int fillUntaggedTranslucentQuads(
            ByteBuffer vertexBuffer,
            int vertexCount,
            int stride
    ) {
        if (vertexBuffer == null ||
                !vertexBuffer.isDirect() ||
                stride != 28 ||
                vertexCount <= 0) {
            return 0;
        }

        final ByteBuffer vertices =
                vertexBuffer
                        .duplicate()
                        .order(
                                ByteOrder.nativeOrder()
                        );

        int tagged = 0;

        for (int vertex = 0; vertex < vertexCount; ++vertex) {
            final int markerOffset =
                    vertex * stride + 27;

            if ((vertices.get(markerOffset) & 0xFF) != 0) {
                continue;
            }

            vertices.put(
                    markerOffset,
                    (byte) RT_MARKER_GENERIC_TRANSLUCENT
            );

            ++tagged;
        }

        return tagged;
    }

    private static int tagTranslucentQuads(
            SectionPos sectionPos,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            ByteBuffer vertexBuffer,
            int vertexCount,
            int stride
    ) {
        if (
                region == null ||
                        vertexBuffer == null ||
                        !vertexBuffer.isDirect() ||
                        stride != 28 ||
                        vertexCount <= 0 ||
                        (vertexCount % 4) != 0
        ) {
            return 0;
        }

        ByteBuffer vertices =
                vertexBuffer
                        .duplicate()
                        .order(
                                ByteOrder.nativeOrder()
                        );

        final int minX =
                sectionPos.minBlockX();

        final int minY =
                sectionPos.minBlockY();

        final int minZ =
                sectionPos.minBlockZ();

        int matchedTranslucentQuads =
                0;

        BlockPos.MutableBlockPos ownerCandidateA =
                new BlockPos.MutableBlockPos();

        BlockPos.MutableBlockPos ownerCandidateB =
                new BlockPos.MutableBlockPos();

        BlockPos.MutableBlockPos glassConnectScratch =
                new BlockPos.MutableBlockPos();

        final int quadCount =
                vertexCount / 4;

        final float[] x =
                new float[4];

        final float[] y =
                new float[4];

        final float[] z =
                new float[4];

        final float[] u =
                new float[4];

        final float[] v =
                new float[4];

        for (int quad = 0; quad < quadCount; ++quad) {
            final int preMarker =
                    vertices.get((quad * 4) * stride + 27) & 0xFF;

            if (preMarker != 0 && !isGlassFamilyMarker(preMarker)) {
                continue;
            }

            float centerX =
                    0.0f;

            float centerY =
                    0.0f;

            float centerZ =
                    0.0f;

            for (int vertex = 0; vertex < 4; ++vertex) {
                final int base =
                        (quad * 4 + vertex) *
                                stride;

                x[vertex] =
                        vertices.getFloat(
                                base
                        );

                y[vertex] =
                        vertices.getFloat(
                                base + 4
                        );

                z[vertex] =
                        vertices.getFloat(
                                base + 8
                        );

                u[vertex] =
                        vertices.getFloat(
                                base + 16
                        );

                v[vertex] =
                        vertices.getFloat(
                                base + 20
                        );

                centerX +=
                        x[vertex];

                centerY +=
                        y[vertex];

                centerZ +=
                        z[vertex];
            }

            centerX *=
                    0.25f;

            centerY *=
                    0.25f;

            centerZ *=
                    0.25f;


            final float edge1X =
                    x[1] - x[0];

            final float edge1Y =
                    y[1] - y[0];

            final float edge1Z =
                    z[1] - z[0];


            final float edge2X =
                    x[2] - x[0];

            final float edge2Y =
                    y[2] - y[0];

            final float edge2Z =
                    z[2] - z[0];


            float normalX =
                    edge1Y * edge2Z -
                            edge1Z * edge2Y;

            float normalY =
                    edge1Z * edge2X -
                            edge1X * edge2Z;

            float normalZ =
                    edge1X * edge2Y -
                            edge1Y * edge2X;


            final float normalLengthSquared =
                    normalX * normalX +
                            normalY * normalY +
                            normalZ * normalZ;


            if (normalLengthSquared <= 1.0e-12f) {
                continue;
            }


            final float inverseNormalLength =
                    1.0f /
                            (float)Math.sqrt(
                                    normalLengthSquared
                            );


            normalX *=
                    inverseNormalLength;

            normalY *=
                    inverseNormalLength;

            normalZ *=
                    inverseNormalLength;


            final float probeDistance =
                    0.01f;


            final int localAX =
                    (int)Math.floor(
                            centerX -
                                    normalX *
                                            probeDistance
                    );

            final int localAY =
                    (int)Math.floor(
                            centerY -
                                    normalY *
                                            probeDistance
                    );

            final int localAZ =
                    (int)Math.floor(
                            centerZ -
                                    normalZ *
                                            probeDistance
                    );


            final int localBX =
                    (int)Math.floor(
                            centerX +
                                    normalX *
                                            probeDistance
                    );

            final int localBY =
                    (int)Math.floor(
                            centerY +
                                    normalY *
                                            probeDistance
                    );

            final int localBZ =
                    (int)Math.floor(
                            centerZ +
                                    normalZ *
                                            probeDistance
                    );


            ownerCandidateA.set(
                    minX + localAX,
                    minY + localAY,
                    minZ + localAZ
            );


            ownerCandidateB.set(
                    minX + localBX,
                    minY + localBY,
                    minZ + localBZ
            );


            final boolean markerAlreadyWritten =
                    preMarker != 0;

            boolean glassConnectCandidate =
                    markerAlreadyWritten;

            int translucentMarker;

            if (markerAlreadyWritten) {
                translucentMarker =
                        preMarker;
            } else {
                translucentMarker =
                        getTranslucentMarker(
                                region.getBlockState(
                                        ownerCandidateA
                                )
                        );

                if (translucentMarker < 2) {
                    translucentMarker =
                            getTranslucentMarker(
                                    region.getBlockState(
                                            ownerCandidateB
                                    )
                            );
                }

                glassConnectCandidate =
                        translucentMarker >= 2;

                if (translucentMarker == 0) {
                    final int firstVertexBase =
                            (quad * 4) * stride;

                    translucentMarker =
                            PBRAtlasStorage.spriteIsSeeThrough(
                                    vertices.getFloat(firstVertexBase + 16),
                                    vertices.getFloat(firstVertexBase + 20)
                            )
                                    ? RT_MARKER_GENERIC_TRANSLUCENT
                                    : 0;

                    glassConnectCandidate =
                            false;
                }
            }


            if (
                    translucentMarker >= 2
            ) {

                final boolean glassConnect =
                        glassConnectCandidate &&
                                isGlassFamilyMarker(translucentMarker);

                final boolean flatGlassWindow =
                        translucentMarker == RT_MARKER_GENERIC_TRANSLUCENT &&
                                isFlatGlassOwner(
                                        region,
                                        ownerCandidateA,
                                        ownerCandidateB
                                );

                final int connectMask = glassConnect
                        ? computeGlassConnectMask(
                                x,
                                y,
                                z,
                                u,
                                v,
                                centerX,
                                centerY,
                                centerZ,
                                minX,
                                minY,
                                minZ,
                                region,
                                glassConnectScratch
                        )
                        : 0;

                final int glassPadValue =
                        connectMask |
                                (flatGlassWindow ? GLASS_FLAT_CUBE_BIT : 0);

                for (int vertex = 0; vertex < 4; ++vertex) {
                    final int vertexBase =
                            (quad * 4 + vertex) *
                                    stride;

                    if (!markerAlreadyWritten) {
                        vertices.put(
                                vertexBase + 27,
                                (byte)translucentMarker
                        );
                    }

                    if (glassPadValue != 0) {
                        final int lightCoords =
                                vertices.getInt(
                                        vertexBase + 24
                                );

                        vertices.putInt(
                                vertexBase + 24,
                                (lightCoords & GLASS_PAD_CLEAR) |
                                        (glassPadValue << GLASS_CONNECT_MASK_SHIFT)
                        );
                    }
                }

                ++matchedTranslucentQuads;
            }
        }

        return matchedTranslucentQuads;
    }

    private static final java.util.concurrent.atomic.AtomicLong SC_GLASS_CULLED_VERTS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final ThreadLocal<ByteBuffer> GLASS_CULL_SCRATCH =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(0));

    private static boolean glassInteriorCullEnabled() {
        final String raw = System.getProperty("rtx.glassInteriorCull");

        return raw == null || !"false".equalsIgnoreCase(raw);
    }

    private static ByteBuffer glassCullScratch(int bytes) {
        ByteBuffer buffer = GLASS_CULL_SCRATCH.get();

        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes);
            GLASS_CULL_SCRATCH.set(buffer);
        }

        buffer.clear();
        buffer.limit(bytes);

        return buffer;
    }

    private static boolean isInteriorSharedGlassFace(
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            BlockPos a,
            BlockPos b
    ) {
        if (a.equals(b)) {
            return false;
        }

        final BlockState stateA = region.getBlockState(a);
        final BlockState stateB = region.getBlockState(b);

        if (stateA == stateB || stateA.getBlock() != stateB.getBlock()) {
            return false;
        }

        return stateA.isCollisionShapeFullBlock(region, a)
                && stateB.isCollisionShapeFullBlock(region, b);
    }

    /**
     * 第二遍：把落在玻璃内部的共享面压缩掉。
     *
     * 不改原缓冲（它仍属于原版 MeshData，可能被别处使用），而是整块拷进
     * 线程本地 scratch 后在 scratch 上做原地压缩（write 指针 <= read 指针，
     * 同缓冲压缩安全），再把压缩后的缓冲交给 uploadChunk。
     *
     * @return 压缩后的顶点数；未启用 / 无需剔除时返回 -1，调用方沿用原缓冲。
     */
    private static int cullInteriorGlassFaces(
            SectionPos sectionPos,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            ByteBuffer vertexBuffer,
            int vertexCount,
            int stride,
            ByteBuffer[] outBuffer
    ) {
        outBuffer[0] = null;

        if (!glassInteriorCullEnabled() ||
                region == null ||
                vertexBuffer == null ||
                !vertexBuffer.isDirect() ||
                stride != 28 ||
                vertexCount <= 0 ||
                (vertexCount % 4) != 0) {
            return -1;
        }

        final int quadCount = vertexCount / 4;

        final ByteBuffer markerReader = vertexBuffer
                .duplicate()
                .order(ByteOrder.nativeOrder());
        int glassQuads = 0;

        for (int quad = 0; quad < quadCount; ++quad) {
            if (isGlassFamilyMarker(markerReader.get((quad * 4) * stride + 27) & 0xFF)) {
                if (++glassQuads >= 2) {
                    break;
                }
            }
        }

        if (glassQuads < 2) {
            return -1;
        }

        final int byteCount = vertexCount * stride;
        final ByteBuffer source = vertexBuffer
                .duplicate()
                .order(ByteOrder.nativeOrder());
        source.position(0);
        source.limit(byteCount);

        final ByteBuffer out = glassCullScratch(byteCount);
        out.put(source);
        out.position(0);

        final int minX = sectionPos.minBlockX();
        final int minY = sectionPos.minBlockY();
        final int minZ = sectionPos.minBlockZ();

        final float[] vx = new float[4];
        final float[] vy = new float[4];
        final float[] vz = new float[4];
        final BlockPos.MutableBlockPos candidateA = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos candidateB = new BlockPos.MutableBlockPos();

        int writeQuad = 0;
        int culledQuads = 0;

        for (int quad = 0; quad < quadCount; quad++) {
            final int marker = out.get((quad * 4) * stride + 27) & 0xFF;

            boolean drop = false;

            if (isGlassFamilyMarker(marker)) {
                float centerX = 0.0f;
                float centerY = 0.0f;
                float centerZ = 0.0f;

                for (int vertex = 0; vertex < 4; ++vertex) {
                    final int base = (quad * 4 + vertex) * stride;

                    vx[vertex] = out.getFloat(base);
                    vy[vertex] = out.getFloat(base + 4);
                    vz[vertex] = out.getFloat(base + 8);

                    centerX += vx[vertex];
                    centerY += vy[vertex];
                    centerZ += vz[vertex];
                }

                centerX *= 0.25f;
                centerY *= 0.25f;
                centerZ *= 0.25f;

                final float edge1X = vx[1] - vx[0];
                final float edge1Y = vy[1] - vy[0];
                final float edge1Z = vz[1] - vz[0];
                final float edge2X = vx[2] - vx[0];
                final float edge2Y = vy[2] - vy[0];
                final float edge2Z = vz[2] - vz[0];

                final float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
                final float normalY = edge1Z * edge2X - edge1X * edge2Z;
                final float normalZ = edge1X * edge2Y - edge1Y * edge2X;
                final float lengthSquared =
                        normalX * normalX + normalY * normalY + normalZ * normalZ;

                if (lengthSquared > 1.0e-12f) {
                    final float inverseLength =
                            1.0f / (float)Math.sqrt(lengthSquared);

                    final float probe = 0.01f;

                    candidateA.set(
                            minX + (int)Math.floor(centerX - normalX * inverseLength * probe),
                            minY + (int)Math.floor(centerY - normalY * inverseLength * probe),
                            minZ + (int)Math.floor(centerZ - normalZ * inverseLength * probe)
                    );
                    candidateB.set(
                            minX + (int)Math.floor(centerX + normalX * inverseLength * probe),
                            minY + (int)Math.floor(centerY + normalY * inverseLength * probe),
                            minZ + (int)Math.floor(centerZ + normalZ * inverseLength * probe)
                    );

                    if (Math.abs(centerX) <= 32.0f
                            && Math.abs(centerY) <= 32.0f
                            && Math.abs(centerZ) <= 32.0f) {
                        drop = isInteriorSharedGlassFace(region, candidateA, candidateB);
                    }
                }
            }

            if (drop) {
                ++culledQuads;
                continue;
            }

            if (writeQuad != quad) {
                MemoryUtil.memCopy(
                        MemoryUtil.memAddress(out) + (long)quad * 4L * stride,
                        MemoryUtil.memAddress(out) + (long)writeQuad * 4L * stride,
                        4L * stride
                );
            }

            ++writeQuad;
        }

        if (culledQuads == 0) {
            return -1;
        }

        final int newVertexCount = writeQuad * 4;

        out.position(0);
        out.limit(newVertexCount * stride);
        outBuffer[0] = out;

        SC_GLASS_CULLED_VERTS.addAndGet(vertexCount - newVertexCount);

        return newVertexCount;
    }

    private static float quadSpan(float a, float b, float c, float d) {
        final float min = Math.min(Math.min(a, b), Math.min(c, d));
        final float max = Math.max(Math.max(a, b), Math.max(c, d));

        return max - min;
    }


    private static int classifyLocalLightType(
            BlockState blockState
    ) {
        var block =
                blockState.getBlock();


        if (
                block == Blocks.TORCH ||
                        block == Blocks.WALL_TORCH ||
                        block == Blocks.LANTERN ||
                        block == Blocks.CAMPFIRE ||
                        block == Blocks.FIRE
        ) {
            return 1;
        }



        if (
                block == Blocks.SOUL_TORCH ||
                        block == Blocks.SOUL_WALL_TORCH ||
                        block == Blocks.SOUL_LANTERN ||
                        block == Blocks.SOUL_CAMPFIRE ||
                        block == Blocks.SOUL_FIRE
        ) {
            return 2;
        }



        if (
                block == Blocks.REDSTONE_TORCH ||
                        block == Blocks.REDSTONE_WALL_TORCH
        ) {
            return 3;
        }



        if (
                block == Blocks.LAVA
        ) {
            return 4;
        }



        if (
                block == Blocks.GLOWSTONE ||
                        block == Blocks.SEA_LANTERN ||
                        block == Blocks.SHROOMLIGHT ||
                        block == Blocks.JACK_O_LANTERN ||
                        block == Blocks.REDSTONE_LAMP
        ) {
            return 5;
        }



        if (
                block == Blocks.NETHER_PORTAL
        ) {
            return 6;
        }



        return 0;
    }

    private static void accumulateVptAlbedoAndTagRainPuddles(
            SectionPos sectionPos,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            ByteBuffer vertexBuffer,
            int vertexCount,
            int stride,
            int[] red,
            int[] green,
            int[] blue,
            int[] count,
            boolean cutoutLayer,
            SectionScratch scratch
    ) {
        if (region == null || vertexBuffer == null || !vertexBuffer.isDirect()
                || stride != 28 || vertexCount <= 0 || (vertexCount % 4) != 0) {
            return;
        }

        final com.mojang.blaze3d.platform.NativeImage albedoAtlas =
                PBRAtlasStorage.globalVptAlbedoAtlas;
        final boolean doAlbedo = albedoAtlas != null;
        final int atlasWidth = doAlbedo ? albedoAtlas.getWidth() : 0;
        final int atlasHeight = doAlbedo ? albedoAtlas.getHeight() : 0;
        final long atlasPixels = doAlbedo ? albedoAtlas.getPointer() : 0L;

        final ByteBuffer vertices = vertexBuffer.duplicate().order(ByteOrder.nativeOrder());
        final int minX = sectionPos.minBlockX();
        final int minY = sectionPos.minBlockY();
        final int minZ = sectionPos.minBlockZ();
        final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos owner = new BlockPos.MutableBlockPos();

        for (int quad = 0; quad < vertexCount / 4; ++quad) {
            float centerX = 0.0f, centerY = 0.0f, centerZ = 0.0f;
            float centerU = 0.0f, centerV = 0.0f;
            float x0 = 0.0f, y0 = 0.0f, z0 = 0.0f;
            float x1 = 0.0f, y1 = 0.0f, z1 = 0.0f;
            float x2 = 0.0f, y2 = 0.0f, z2 = 0.0f;
            float x3 = 0.0f, y3 = 0.0f, z3 = 0.0f;

            for (int v = 0; v < 4; ++v) {
                int base = (quad * 4 + v) * stride;
                float x = vertices.getFloat(base);
                float y = vertices.getFloat(base + 4);
                float z = vertices.getFloat(base + 8);
                if (v == 0) { x0 = x; y0 = y; z0 = z; }
                else if (v == 1) { x1 = x; y1 = y; z1 = z; }
                else if (v == 2) { x2 = x; y2 = y; z2 = z; }
                else { x3 = x; y3 = y; z3 = z; }
                centerX += x;
                centerY += y;
                centerZ += z;
                centerU += vertices.getFloat(base + 16);
                centerV += vertices.getFloat(base + 20);
            }

            centerX *= 0.25f;
            centerY *= 0.25f;
            centerZ *= 0.25f;
            centerU *= 0.25f;
            centerV *= 0.25f;

            if (doAlbedo) {
                float edge1X = x1 - x0, edge1Y = y1 - y0, edge1Z = z1 - z0;
                float edge2X = x2 - x0, edge2Y = y2 - y0, edge2Z = z2 - z0;
                float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
                float normalY = edge1Z * edge2X - edge1X * edge2Z;
                float normalZ = edge1X * edge2Y - edge1Y * edge2X;
                float normalLength = (float) Math.sqrt(normalX * normalX +
                        normalY * normalY + normalZ * normalZ);
                if (normalLength > 1.0e-6f) {
                    normalX /= normalLength;
                    normalY /= normalLength;
                    normalZ /= normalLength;

                    int localX = (int) Math.floor(centerX - normalX * 0.01f);
                    int localY = (int) Math.floor(centerY - normalY * 0.01f);
                    int localZ = (int) Math.floor(centerZ - normalZ * 0.01f);
                    boolean probeInBounds =
                            localX >= 0 && localX < 16 && localY >= 0 &&
                            localY < 16 && localZ >= 0 && localZ < 16;
                    if (probeInBounds) {
                        position.set(minX + localX, minY + localY, minZ + localZ);
                        if (region.getBlockState(position).isAir()) {
                            localX = (int) Math.floor(centerX + normalX * 0.01f);
                            localY = (int) Math.floor(centerY + normalY * 0.01f);
                            localZ = (int) Math.floor(centerZ + normalZ * 0.01f);
                            probeInBounds =
                                    localX >= 0 && localX < 16 && localY >= 0 &&
                                    localY < 16 && localZ >= 0 && localZ < 16;
                            if (probeInBounds) {
                                position.set(minX + localX, minY + localY, minZ + localZ);
                                probeInBounds = !region.getBlockState(position).isAir();
                            }
                        }
                    }

                    if (probeInBounds) {
                        int atlasX = Math.max(0, Math.min(atlasWidth - 1,
                                (int) Math.floor(centerU * atlasWidth)));
                        int atlasY = Math.max(0, Math.min(atlasHeight - 1,
                                (int) Math.floor(centerV * atlasHeight)));
                        long pixel = atlasPixels
                                + (((long) atlasY * atlasWidth + atlasX) * 4L);

                        int index = localX + 16 * (localZ + 16 * localY);
                        red[index] += MemoryUtil.memGetByte(pixel) & 0xFF;
                        green[index] += MemoryUtil.memGetByte(pixel + 1L) & 0xFF;
                        blue[index] += MemoryUtil.memGetByte(pixel + 2L) & 0xFF;
                        ++count[index];
                        scratch.vptAny = true;
                    }
                }
            }

            if (cutoutLayer) {
                float fnx = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
                float fny = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
                float fnz = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
                float fnLen = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
                if (fnLen > 1.0e-6f) {
                    fnx /= fnLen; fny /= fnLen; fnz /= fnLen;
                    boolean foliageOwner = false;
                    position.set(
                            minX + (int) Math.floor(centerX - fnx * 0.02f),
                            minY + (int) Math.floor(centerY - fny * 0.02f),
                            minZ + (int) Math.floor(centerZ - fnz * 0.02f)
                    );
                    net.minecraft.world.level.block.state.BlockState ownerState =
                            region.getBlockState(position);
                    if (ownerState.is(net.minecraft.tags.BlockTags.LEAVES) ||
                            ownerState.is(net.minecraft.tags.BlockTags.FLOWERS)) {
                        foliageOwner = true;
                    } else {
                        position.set(
                                minX + (int) Math.floor(centerX),
                                minY + (int) Math.floor(centerY),
                                minZ + (int) Math.floor(centerZ)
                        );
                        ownerState = region.getBlockState(position);
                        foliageOwner =
                                ownerState.is(net.minecraft.tags.BlockTags.LEAVES) ||
                                ownerState.is(net.minecraft.tags.BlockTags.FLOWERS);
                    }
                    if (foliageOwner) {
                        for (int v = 0; v < 4; ++v) {
                            vertices.put((quad * 4 + v) * stride + 27, (byte) 25);
                        }
                    }
                }
            }

            final float spanX = quadSpan(x0, x1, x2, x3);
            final float spanY = quadSpan(y0, y1, y2, y3);
            final float spanZ = quadSpan(z0, z1, z2, z3);
            final float collapseEpsilon = 1.0e-4f;
            final boolean flatX = spanX <= collapseEpsilon;
            final boolean flatY = spanY <= collapseEpsilon;
            final boolean flatZ = spanZ <= collapseEpsilon;

            if ((flatX ? 1 : 0) + (flatY ? 1 : 0) + (flatZ ? 1 : 0) != 1) {
                continue;
            }

            final boolean ownerResolved;

            if (flatY) {
                owner.set(
                        minX + (int)Math.floor(centerX),
                        minY + (int)Math.floor(centerY - 0.01f),
                        minZ + (int)Math.floor(centerZ)
                );

                ownerResolved = region.getBlockState(owner).canOcclude();
            } else {
                final float sideX = flatX ? 1.0f : 0.0f;
                final float sideZ = flatZ ? 1.0f : 0.0f;

                owner.set(
                        minX + (int)Math.floor(centerX - sideX * 0.01f),
                        minY + (int)Math.floor(centerY),
                        minZ + (int)Math.floor(centerZ - sideZ * 0.01f)
                );

                boolean resolved = region.getBlockState(owner).canOcclude();

                if (!resolved) {
                    owner.set(
                            minX + (int)Math.floor(centerX + sideX * 0.01f),
                            minY + (int)Math.floor(centerY),
                            minZ + (int)Math.floor(centerZ + sideZ * 0.01f)
                    );

                    resolved = region.getBlockState(owner).canOcclude();
                }

                ownerResolved = resolved;
            }

            if (!ownerResolved) {
                continue;
            }

            for (int vertex = 0; vertex < 4; ++vertex) {
                vertices.put((quad * 4 + vertex) * stride + 27, (byte)23);
            }
        }
    }

    private static int uploadSectionVoxelLight(
            SectionPos sectionPos,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            int[] vptRed,
            int[] vptGreen,
            int[] vptBlue,
            int[] vptCount,
            SectionScratch scratch
    ) {
        if (region == null) {
            return 0;
        }

        ByteBuffer voxelBuffer = scratch.voxelBuffer;
        voxelBuffer.clear();

        final int minX = sectionPos.minBlockX();
        final int minY = sectionPos.minBlockY();
        final int minZ = sectionPos.minBlockZ();

        BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

        int emissiveVoxelCount = 0;
        int maxEmission = 0;
        int trackedGlassBlocks = 0;

        final PaletteProbe probe = scratch.paletteProbe;
        final SectionCopy sectionCopy = sectionCopyAt(sectionPos, region);
        final PalettedContainer<BlockState> palette = sectionCopy == null
                ? null
                : ((SectionCopyAccessor) sectionCopy).getSectionPalette();
        final boolean paletteKnown = sectionCopy != null;
        final boolean emptyChunk = paletteKnown && palette == null;

        int paletteSize = 0;
        boolean singleAir = false;
        if (palette != null) {
            probe.reset();
            palette.forEachInPalette(probe);
            paletteSize = probe.count;
            singleAir = paletteSize == 1 && probe.first != null && probe.first.isAir();
        }
        final boolean allAir = emptyChunk || singleAir;

        if (PALETTE_DIAG) {
            PD_SECTIONS.incrementAndGet();
            if (scratch.vptAny) {
                PD_VPT_ANY.incrementAndGet();
            }
            if (!paletteKnown) {
                PD_NO_SNAPSHOT.incrementAndGet();
            } else if (emptyChunk) {
                PD_NO_PALETTE.incrementAndGet();
            } else {
                PD_PALETTE_ENTRIES.addAndGet(paletteSize);
                PD_PALETTE_MAX.accumulateAndGet(paletteSize, Math::max);
                if (singleAir) {
                    PD_AIR_ONLY.incrementAndGet();
                } else if (paletteSize == 1) {
                    PD_SINGLE_NONAIR.incrementAndGet();
                    if (!scratch.vptAny) {
                        PD_SINGLE_NONAIR_CLEAN.incrementAndGet();
                    }
                }
                if (palette.maybeHas(state -> getTrackedGlassMarker(state) >= 2)) {
                    PD_HAS_GLASS.incrementAndGet();
                }
                if (palette.maybeHas(state -> state.getLightEmission() > 0)) {
                    PD_HAS_EMISSIVE.incrementAndGet();
                }
                if (palette.maybeHas(state -> state.canOcclude() && !state.isSolidRender())) {
                    PD_HAS_PARTIAL.incrementAndGet();
                }
            }
        }

        final int scanEdge = (SECTION_FAST_FILL && allAir) ? 0 : SECTION_EDGE;
        if (scanEdge == 0) {
            MemoryUtil.memSet(MemoryUtil.memAddress(voxelBuffer, 0), 0, SECTION_VOXEL_BYTES);
        }

        final long scTLoop = System.nanoTime();
        try {
            for (int localY = 0; localY < scanEdge; ++localY) {
                final int worldY =
                        minY + localY;

                for (int localZ = 0; localZ < scanEdge; ++localZ) {
                    final int worldZ =
                            minZ + localZ;

                    for (int localX = 0; localX < scanEdge; ++localX) {
                        final int worldX =
                                minX + localX;

                        pos.set(
                                worldX,
                                worldY,
                                worldZ
                        );

                        final BlockState blockState =
                                region.getBlockState(
                                        pos
                                );

                        if (blockState.isAir()) {
                            voxelBuffer.putInt(0);
                            continue;
                        }

                        final int emission =
                                blockState.getLightEmission();

                        final int glassMarker = getTrackedGlassMarker(blockState);
                        if (glassMarker >= 2) {
                            ++trackedGlassBlocks;
                        }
                        final int voxelClass;
                        int partialTransmission = 0;

                        if (blockState.isAir()) {
                            voxelClass = VLI_VOXEL_EMPTY;
                        } else if (glassMarker >= 2) {
                            voxelClass = VLI_VOXEL_TRANSLUCENT;
                        } else if (blockState.canOcclude()) {
                            if (blockState.isSolidRender()) {
                                voxelClass = VLI_VOXEL_OPAQUE;
                            } else {
                                final int fillPermille =
                                        occlusionFillPermille(blockState);
                                if (fillPermille <= 0) {
                                    voxelClass = VLI_VOXEL_TRANSLUCENT;
                                } else if (fillPermille >= 1000) {
                                    voxelClass = VLI_VOXEL_OPAQUE;
                                } else {
                                    voxelClass = VLI_VOXEL_PARTIAL;
                                    partialTransmission = Math.max(
                                            1,
                                            Math.min(
                                                    VLI_PARTIAL_MAX,
                                                    (VLI_PARTIAL_MAX *
                                                            (1000 - fillPermille) +
                                                            500) / 1000
                                            )
                                    );
                                }
                            }
                        } else {
                            voxelClass = VLI_VOXEL_TRANSLUCENT;
                        }

                        final int emissionClamped =
                                Math.max(0, Math.min(15, emission));

                        if (emissionClamped > 0) {
                            ++emissiveVoxelCount;
                            maxEmission = Math.max(maxEmission, emissionClamped);
                        }

                        final int typeCode =
                                emissionClamped > 0
                                        ? classifyLocalLightType(blockState)
                                        : 0;

                        final int voxelIndex =
                                localX + SECTION_EDGE * (
                                        localZ + SECTION_EDGE * localY
                                );

                        int r5 = vptCount[voxelIndex] == 0
                                ? 0
                                : Math.min(31, (vptRed[voxelIndex] * 31 +
                                vptCount[voxelIndex] * 127) /
                                (vptCount[voxelIndex] * 255));
                        int g5 = vptCount[voxelIndex] == 0
                                ? 0
                                : Math.min(31, (vptGreen[voxelIndex] * 31 +
                                vptCount[voxelIndex] * 127) /
                                (vptCount[voxelIndex] * 255));
                        int b5 = vptCount[voxelIndex] == 0
                                ? 0
                                : Math.min(31, (vptBlue[voxelIndex] * 31 +
                                vptCount[voxelIndex] * 127) /
                                (vptCount[voxelIndex] * 255));

                        final boolean stainedGlass =
                                glassMarker >= 3 && glassMarker <= 18;
                        if (stainedGlass) {
                            final int rgb = net.minecraft.world.item.DyeColor
                                    .byId(glassMarker - 3)
                                    .getFireworkColor();
                            r5 = (((rgb >> 16) & 0xFF) * 31 + 127) / 255;
                            g5 = (((rgb >> 8) & 0xFF) * 31 + 127) / 255;
                            b5 = ((rgb & 0xFF) * 31 + 127) / 255;
                        }

                        final int voxelWord =
                                voxelClass |
                                        (emissionClamped << 2) |
                                        (typeCode << 6) |
                                        (r5 << 9) |
                                        (g5 << 14) |
                                        (b5 << 19) |
                                        (stainedGlass ? VLI_STAINED_GLASS_BIT : 0) |
                                        (partialTransmission << VLI_PARTIAL_SHIFT);

                        voxelBuffer.putInt(voxelWord);

                    }
                }
            }

            SC_VOXEL_LOOP_NANOS.addAndGet(System.nanoTime() - scTLoop);

            final long scTVoxelJni = System.nanoTime();
            try {
                NativeVulkan.uploadVoxelLightSection(
                        sectionPos.x(),
                        sectionPos.y(),
                        sectionPos.z(),
                        MemoryUtil.memAddress(voxelBuffer, 0),
                        SECTION_VOXEL_BYTES
                );
            } catch (Throwable t) {
                t.printStackTrace();
            }
            SC_VOXEL_JNI_NANOS.addAndGet(System.nanoTime() - scTVoxelJni);

            if (DEBUG && emissiveVoxelCount > 0) {
                System.out.println(
                        "[VPT EMISSION]"
                                + " section=("
                                + sectionPos.x()
                                + ","
                                + sectionPos.y()
                                + ","
                                + sectionPos.z()
                                + ")"
                                + " emissiveVoxels="
                                + emissiveVoxelCount
                                + " maxEmission="
                                + maxEmission
                );
            }

        } finally {
            voxelBuffer.clear();
        }

        return trackedGlassBlocks;
    }

    private static final class SectionScratch {
        final int[] vptRed = new int[SECTION_VOXEL_COUNT];
        final int[] vptGreen = new int[SECTION_VOXEL_COUNT];
        final int[] vptBlue = new int[SECTION_VOXEL_COUNT];
        final int[] vptCount = new int[SECTION_VOXEL_COUNT];
        final ByteBuffer voxelBuffer = MemoryUtil.memAlloc(SECTION_VOXEL_BYTES)
                .order(ByteOrder.nativeOrder());
        final PaletteProbe paletteProbe = new PaletteProbe();
        boolean vptAny;

        void clearVoxelAccumulators() {
            java.util.Arrays.fill(vptRed, 0);
            java.util.Arrays.fill(vptGreen, 0);
            java.util.Arrays.fill(vptBlue, 0);
            java.util.Arrays.fill(vptCount, 0);
            vptAny = false;
        }
    }

}
