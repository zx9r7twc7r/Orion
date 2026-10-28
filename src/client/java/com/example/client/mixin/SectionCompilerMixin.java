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

    // =====================================================
    // VLI-1.4 Section voxel source staging
    //
    // One uint32 per Minecraft block, in x-fastest order:
    //   bits 0..1  voxel class (0 empty, 1 opaque, 2 translucent, 3 partial)
    //   bits 2..5  Minecraft emission level (0..15)
    //   bits 6..8  existing local-light typeCode (0..5)
    //   bits 9..23 RGB555 surface albedo, or exact stained-glass dye RGB
    //   bit 24     exact stained-glass / stained-pane transmission marker
    //   bits 25..31 partial-occluder transmission (0..127, 127 = transparent)
    //
    // VLI-2.0: class 3 exists because `canOcclude()` is a *block-level* flag
    // (default true and inherited unchanged by `ofLegacyCopy`), so a bottom
    // slab, a stair, a wall or a fence all reported `canOcclude() == true` and
    // were encoded as a full cell.  Every voxel DDA in rt_test.rgen and
    // rt_vpt_irradiance.comp then stopped the ray at that cell, so a torch
    // threw a 1x1x1 box shadow instead of the block's real silhouette.  The
    // "is this a full cube" question is answered by `isSolidRender()`; anything
    // that occludes without being a full cube becomes class 3 and carries how
    // much of the cell is actually filled.
    // =====================================================

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

    // ChunkSectionLayer 在 26.2 只有 SOLID / CUTOUT / TRANSLUCENT 三层，位 = ordinal。
    // 用它把"本次编译没有几何的层"一次交给 native（见 clearChunkLayers）。
    private static final int TERRAIN_LAYER_MASK =
            (1 << ChunkSectionLayer.values().length) - 1;

    // =====================================================
    // 2026-10-21 RT per-BlockState derived cache（FerriteCore 的"扁平表 + 整数索引"）
    //
    // 这个文件关于一个 state 需要的三份派生数据 —— tracked-glass marker、
    // occlusion fill、translucent marker —— 全都是 (interned) BlockState 的纯函数。
    // 它们原先分放在三个 ConcurrentHashMap<.., Integer> 里 ⇒ 每个体素 3 次装箱
    // 查表（一个 Section 4096 次），半透明趟每个 quad 还要再来一次。
    //
    // 现在收进 state 自身携带的一个 int 字段（BlockStateRtCacheMixin），
    // 哈希、Integer 装箱、三份表头全部消失：
    //
    //   bits 0..4    tracked-glass marker（0..25，RT_MARKER_*）
    //   bits 5..14   occlusion fill，per mille（0..1000）
    //   bits 15..19  translucent-family marker（0..25）
    //   bit  20      valid
    //
    // 惰性写存在良性竞态：两个 worker 同时首次命中同一 state 会各算一次、
    // 写回同一位模式，结果一致（纯函数），无需加锁。
    // =====================================================

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

        // fill 只在"能遮挡但不是整方块"时被读（见 uploadSectionVoxelLight）。
        // 其余状态填饱和值 1000：语义上等价于 opaque，且整方块不必为此走
        // VoxelShape 遍历 —— 与改动前"按需计算"的成本结构一致。
        int fillPermille = 1000;
        if (state.canOcclude() && !state.isSolidRender()) {
            // VoxelShape boxes are block-relative in 0..1, so a full cube sums
            // to exactly 1.0 and a bottom slab to 0.5.
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

    // Section compilation runs on worker threads. Reusing one scratch page per
    // worker removes repeated 64 KiB heap churn and native 16 KiB alloc/free
    // pairs without sharing mutable buffers between compilers.
    private static final ThreadLocal<SectionScratch> SECTION_SCRATCH =
            ThreadLocal.withInitial(SectionScratch::new);

    // Vanilla stained panes are StainedGlassPaneBlock instances, not
    // StainedGlassBlock, so they miss the instanceof fast path in
    // getTrackedGlassMarker.  In this yarn version the 16 panes live in the
    // Blocks.STAINED_GLASS_PANE ColorCollection rather than individual
    // constants; the table is built once, identity lookup keeps the
    // per-block cost at O(1), and modded panes keep the registry-string
    // fallback.
    private static final IdentityHashMap<Block, Integer> STAINED_PANE_MARKERS =
            createStainedPaneMarkers();

    // The VPT source pass evaluates this marker for every one of the 4096
    // voxels, and the unresolved fallback inspects the block registry and a
    // registry path string.  The marker is a pure function of the Block
    // instance, so it is memoized in the per-state cache (rtStateCache) instead
    // of a boxing map.


    // =====================================================
    // RT-only byte 27 translucent material ABI
    //
    // Must stay in lockstep with VulkanCore.cpp's
    // "T1-1 Generic translucent classification" block and
    // rt_test_translucent.rchit's getTransparentProfileFromRawMaterialId.
    //
    //   1     water                (FluidRendererMixin, mesh bake time)
    //   2     clear glass / pane, and the generic translucent body
    //   3..18 stained glass / pane (DyeColor id + 3)
    //   19    tinted glass
    //   20    ice / packed ice / blue ice / frosted ice
    //   21    slime block
    //   22    honey block
    //   23    rain-eligible solid face (tagRainPuddleCandidateQuads)
    //
    // Values 19..22 are already decoded by native into
    // materialId = 2.0 + 0.1 * (marker - 18), i.e. the shaders receive
    // transparent profiles 1..4 without any native ABI change.
    // =====================================================
    private static final int RT_MARKER_GENERIC_TRANSLUCENT = 2;
    private static final int RT_MARKER_TINTED_GLASS = 19;
    private static final int RT_MARKER_ICE = 20;
    private static final int RT_MARKER_SLIME = 21;
    private static final int RT_MARKER_HONEY = 22;
    // 2026-10-06 地狱传送门专用 marker。
    // ⚠ 23 已被"雨洼候选面"占用（VulkanCore.cpp:38700），所以用 24。
    // native 把 24 映射成 RT materialId 5.0（**不在**玻璃族 [1.5,2.5] 里）；
    // 落在玻璃族的话 rt_test*.rchit 的玻璃分支会把 payload.emission 清零
    // —— 这就是"地狱门不发光"的根因。5.0 由 shader 直接给紫光。
    private static final int RT_MARKER_NETHER_PORTAL = 24;
    // 2026-10-05 薄片背光透射（#24）：树叶/花瓣标记。native 映射成
    // RT materialId 0.25 —— 仍落在不透明路由带（所有 <=0.5 判据照旧），
    // rgen 据此给背光透射项。25 = 24（地狱门）之后的第一个空闲 marker。
    private static final int RT_MARKER_FOLIAGE = 25;

    // =====================================================
    // 玻璃连接纹理（CTM）连通掩码 —— RT-only
    //
    // 位的含义按 **quad 自己的 sprite 轴向** 命名，着色器因此不必知道 UV
    // 朝向就能套用：
    //   1 = sprite u-min 边   2 = u-max 边
    //   4 = sprite v-min 边   8 = v-max 边
    // 哪条边与玻璃相接由几何求出：从该边中点沿"离开 quad 中心"的方向跨
    // 半格取邻居方块，是玻璃族就把对应位置 1。
    //
    // 载体：地形 packed lightCoords 的中段空闲位（原版只用 bits 4..7 与
    // 20..23，bits 24..31 是本 mod 的 marker），native 在把 28B 顶点扩容成
    // 48B RT 顶点时读进 Vertex.pad2 —— 地形顶点的该 lane 原本恒为 0。
    //
    // 2026-10-08 同一个字段再多带一位"平窗档"（bit 12 / pad2 值 16）：
    // 只有**原版普通玻璃方块**（完整立方体，marker 2）会置位，玻璃板、染色
    // 玻璃、冰/粘液/蜂蜜都不会。rchit 见到该位就退成"贴图 + 背后直接透视"。
    // =====================================================
    private static final int GLASS_CONNECT_MASK_SHIFT = 8;
    private static final int GLASS_CONNECT_MASK_BITS = 0x0F;
    private static final int GLASS_FLAT_CUBE_BIT = 0x10;
    private static final int GLASS_PAD_BITS =
            GLASS_CONNECT_MASK_BITS | GLASS_FLAT_CUBE_BIT;
    private static final int GLASS_PAD_CLEAR =
            ~(GLASS_PAD_BITS << GLASS_CONNECT_MASK_SHIFT);

    // Same reasoning as the tracked-glass marker: a pure function of the Block
    // instance, memoized in the per-state cache instead of a boxing map.


    // =====================================================
    // 2026-10-15 worker 侧每 section 计时（纯读时钟，不改上传行为与相序）。
    //
    // 为什么量它：SectionCompiler.compile 跑在 worker 线程上，不在主线程的
    // simMs 里 —— 它决定"RT 几何喂给 GPU 的速度"，对应原生
    // [PERF L10 STALL] worldGeometryQueueIdle（= Section AS 重建前的
    // vkQueueWaitIdle 整队列抽干）与移动时的区块可见延迟。
    // 分相：tag=半透明标记、fused=实心/镂空融合趟、jni=uploadChunk、
    // omm=cutout OMM 构建+上传、clear=缺失层批量删除、voxel=体素光源整趟
    // （含其内部 JNI）。256 个 section 一报，标签 [SECTION COMPILE]。
    // =====================================================
    private static final int SC_REPORT_WINDOW = 256;

    // 2026-10-24：uploadChunk 内容哈希去重（实现见 SectionUploadDedup）。
    // 这里只追踪世界对象身份，换世界时整表失效。
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
    // 2026-10-21：voxel 相拆成"逐体素分类"与"JNI 上传"两段，用来判断
    // 再优化循环还有多少可拿（voxel 是稳态最贵的一相，必须知道里面各占多少）。
    private static final AtomicLong SC_VOXEL_LOOP_NANOS = new AtomicLong();
    private static final AtomicLong SC_VOXEL_JNI_NANOS = new AtomicLong();

    // =====================================================
    // 2026-10-21 Lithium 手法：section 级 palette 查询
    //
    // 借鉴 Lithium 的 mixin/util/block_tracking：不要靠"扫 4096 个方块"回答
    // "这一段里有没有 X"，而是直接问 PalettedContainer 的 palette
    // （forEachInPalette / maybeHas，成本 O(palette) 而不是 O(4096)）。
    // 客户端拿不到 LevelChunkSection，只能走 RenderSectionRegion 的邻域快照
    // （SectionCopy），palette 就在它私有的 PalettedContainer 里，所以要有
    // 两个 accessor（RenderSectionRegionAccessor / SectionCopyAccessor）。
    //
    // 两个开关都默认关闭：
    //   -Drtx.sectionFastFill=true      全空气段跳过逐体素分类（发全 0）
    //   -Drtx.diag.sectionPalette=true  每 256 段打一行 [SECTION PALETTE]
    // =====================================================
    private static final boolean SECTION_FAST_FILL =
            Boolean.getBoolean("rtx.sectionFastFill");
    private static final boolean PALETTE_DIAG =
            Boolean.getBoolean("rtx.diag.sectionPalette");

    private static final AtomicLong PD_SECTIONS = new AtomicLong();
    private static final AtomicLong PD_NO_SNAPSHOT = new AtomicLong();
    private static final AtomicLong PD_NO_PALETTE = new AtomicLong();
    private static final AtomicLong PD_AIR_ONLY = new AtomicLong();
    private static final AtomicLong PD_SINGLE_NONAIR = new AtomicLong();
    // 同质非空气段里"可以整段填充"的那部分（没有 VPT albedo）—— 这是
    // "全石头快路径"唯一能命中的集合，也是本次评估要的决定性数字。
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

    // region 的邻域快照按"绝对 section 坐标"取（见 26.3 的 RenderSectionRegion.index）。
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
        // 并发窗口边界可能差一两个样本，诊断口径可接受。
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

        // 远场接管:12 区块(192 格)外的 section 只参与原版编译,不上传 RT,
        // 远场由 voxy LOD 代理。FarFieldGate 每帧推进状态,玩家靠近时标脏重编译。
        if (FarFieldGate.shouldSuppress(sectionPos)) {
            FarFieldGate.recordState(sectionPos, false);
            return;
        }
        FarFieldGate.recordState(sectionPos, true);

        // 2026-10-15 worker 侧分相计时起点（FarFieldGate 抑制的 section 不计入）。
        final long scStartNanos = System.nanoTime();
        long scTagNanos = 0L;
        long scFusedNanos = 0L;
        long scJniNanos = 0L;
        long scOmmNanos = 0L;
        long scClearNanos = 0L;
        long scVoxelNanos = 0L;

        // =====================================================
        // VLI-1.4
        //
        // SectionCompiler is the only producer. One pass over this exact
        // 16^3 render Section supplies both voxel occupancy and emission;
        // native replaces only this persistent Section page.
        // =====================================================

        // =====================================================
        // T1-1B-3 Exact Glass Marker Write
        //
        // - Count exact glass / stained-glass BlockStates.
        // - Inspect only the already-built TRANSLUCENT mesh.
        // - Reconstruct the likely owner block from each quad's
        //   section-local geometry.
        // - For matched glass quads ONLY, write marker=2 into
        //   byte 27 of all four 28-byte terrain vertices.
        // =====================================================

        // VPT-1.3: RGB sums and face counts are built while traversing the
        // same mesh that is already being uploaded to native.  There is no
        // second Section mesh pass and no texture read during rendering.
        final SectionScratch scratch = SECTION_SCRATCH.get();
        scratch.clearVoxelAccumulators();
        final int[] vptRed = scratch.vptRed;
        final int[] vptGreen = scratch.vptGreen;
        final int[] vptBlue = scratch.vptBlue;
        final int[] vptCount = scratch.vptCount;

        // 原版只报告"有几何"的层,这里记录本次编译确实产出的层。
        // 缺失的层随后必须显式通知 native 删除。
        int uploadedLayerMask = 0;

        // Written before the layer loop so the debug print after the voxel
        // pass can report it without a second mesh scan.
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

                // 该层有 mesh 就代表原版认为它存在,后面不能删它。
                // 放在上传条件之前:即使某次上传因异常被跳过,也不能
                // 退化成"缺失"而把上一帧仍有效的旧几何删掉。
                uploadedLayerMask |= 1 << layerId;

                if (vertexBuffer != null &&
                        vertexBuffer.isDirect() &&
                        vertexCount > 0) {

                    final boolean translucentLayer =
                            entry.getKey().name().contains("TRANSLUCENT");

                    if (translucentLayer) {
                        // =================================================
                        // ★ 顺序硬约束：必须在 uploadChunk 之前写标记 ★
                        //
                        // Native's uploadChunk memcmp+copy is immediate
                        // (VulkanCore.cpp Java_..._uploadChunk).  The previous
                        // revision ran this tagging in a second loop after the
                        // whole layer loop, so byte 27 was written into a buffer
                        // that had already been copied to the GPU: every glass
                        // and pane quad reached native with marker 0 and was
                        // shaded as an opaque surface.  Water never suffered
                        // this because FluidRendererMixin writes its marker
                        // while the mesh is still being baked.
                        //
                        // Everything the TRANSLUCENT render layer emits is a
                        // translucent body by definition, so one pass labels
                        // the whole family (water keeps marker 1 through the
                        // per-vertex "already marked" guard).
                        // =================================================
                        final long scTTag = System.nanoTime();
                        matchedTranslucentQuads += tagTranslucentQuads(
                                sectionPos,
                                region,
                                vertexBuffer,
                                vertexCount,
                                stride
                        );

                        // 2026-10-26（对齐 Remix）：Remix 的 translucent 层没有
                        // "几何反推 owner 方块"这一步 —— 材质由绘制状态的 blend
                        // 决定，正反面一视同仁。上面那次反推一失败就把 byte27 留成
                        // 0，该面在 native 侧落回**不透明地形**，于是出现
                        // "正面透明、侧面是色块"。
                        // 这里整层兜底：仍然没有标记的 quad 统一标成半透明体
                        // （marker 2）；水（1）与玻璃族（3..18）保持不动。
                        matchedTranslucentQuads += fillUntaggedTranslucentQuads(
                                vertexBuffer,
                                vertexCount,
                                stride
                        );
                        scTagNanos += System.nanoTime() - scTTag;
                    } else {
                        // Fused pass: reads every quad once and applies both
                        // former passes (VPT albedo accumulation + rain-puddle
                        // tagging) with their original independent guards, so
                        // each quad's outcome is identical to the two-pass
                        // behaviour at one traversal + one duplicate() instead
                        // of two.
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

                    // =================================================
                    // 2026-10-24 项 2：透明层内部共享面剔除。
                    //
                    // 位置有硬约束：必须在 tagTranslucentQuads 之后（压缩要带上
                    // 已写好的 byte27 marker）、在 uploadChunk 之前（native 是
                    // 同步 memcpy，缓冲只在本次调用内有效）。
                    //
                    // vertexCount 随之改写 ⇒ 后面的去重哈希、上传计数、
                    // record() 全部自动跟着压缩后的值走。
                    // =================================================
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

                    // 2026-10-24：内容哈希去重 —— 相同内容不必进 native。
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

        // =====================================================
        // 被挖空的层必须显式删除
        //
        // renderedLayers 只包含 mesh != null 的层,所以某一层被清空
        // (例如挖掉该区段最后一块玻璃,或整个区段被挖成空气)时不会触发
        // 任何上传,native 会一直保留旧几何:RT 里残留幽灵方块,并且这份
        // 顶点缓冲 / BLAS 要到区块卸载才回收。
        //
        // native 侧只在"确实擦掉了缓存条目"时才标脏,所以对从未有几何的
        // 层重复发零顶点上传是廉价空操作,不会引起多余重建。
        // =====================================================
        // 2026-10-03 批量化：原先对每个缺失层各发一次零顶点 uploadChunk（最多 3 次
        // JNI，每次都要取 g_chunkMutex）。现在一次传完整掩码，native 侧取锁一次、
        // 标脏至多一次，逐层语义与原路径完全相同（仍只对"真擦掉缓存"的层标脏）。
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

            // 2026-10-24：native 已擦掉这些层的几何 ⇒ 哈希必须同步失效，
            // 否则下次相同内容会被判为"未变"而不再上传（表现为空洞）。
            SectionUploadDedup.invalidateSection(
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    missingLayerMask
            );
        }

        // The VPT source pass already resolves the tracked-glass marker for
        // all 4096 voxels, so it also reports how many tracked glass blocks
        // this Section owns.
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

        // Dead code kept for reference: the previous revision tagged the
        // translucent mesh here, i.e. AFTER every layer had already been handed
        // to NativeVulkan.uploadChunk.  Because uploadChunk copies the payload
        // synchronously, this second pass could never influence the geometry
        // the GPU saw.  The tagging now runs inside the layer loop, before its
        // own upload (see tagTranslucentQuads).  Keep the old ordering visible
        // so the mistake is not reintroduced.
        /*
        int matchedGlassQuads = 0;
        if (glassBlockCount > 0) {
            for (Map.Entry<net.minecraft.client.renderer.chunk.ChunkSectionLayer,
                    com.mojang.blaze3d.vertex.MeshData> entry : results.renderedLayers.entrySet()) {
                if (!entry.getKey().name().contains("TRANSLUCENT") ||
                        entry.getValue() == null) {
                    continue;
                }

                var translucentDrawState = entry.getValue().drawState();
                ByteBuffer translucentBuffer = entry.getValue().vertexBuffer();
                int translucentVertexCount = translucentDrawState.vertexCount();

                if (translucentBuffer != null &&
                        translucentBuffer.isDirect() &&
                        translucentVertexCount > 0) {
                    matchedGlassQuads += tagTrackedGlassQuads(
                            sectionPos,
                            region,
                            translucentBuffer,
                            translucentVertexCount,
                            translucentDrawState.format().getVertexSize()
                    );
                }
            }
        }
        */

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

        // 2026-10-15 worker 侧分相计时结算（见上方 SC_* 累加器说明）。
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

    // =====================================================
    // Exact translucent classification for the RT-only marker.  This does
    // not modify Minecraft's translucent mesh or its vanilla render path.
    // Clear/stained panes deliberately share the exact profile of their
    // matching full glass blocks.
    // =====================================================

    private static int getTrackedGlassMarker(
            BlockState blockState
    ) {
        return (rtStateCache(blockState) >> RT_STATE_GLASS_SHIFT) & RT_STATE_GLASS_MASK;
    }


    // =====================================================
    // Full translucent-family marker.  This is the one the mesh pass writes;
    // getTrackedGlassMarker() is kept untouched because the VPT voxel source
    // pass (uploadSectionVoxelLight) must keep its exact previous meaning:
    // only exact glass transmits colour there.
    // =====================================================

    private static int getTranslucentMarker(
            BlockState blockState
    ) {
        return (rtStateCache(blockState) >> RT_STATE_TRANSLUCENT_SHIFT)
                & RT_STATE_TRANSLUCENT_MASK;
    }


    // Every marker returned here must exist in VulkanCore.cpp's
    // "T1-1 Generic translucent classification" decoding table.
    //
    // Nothing else has to change natively: native already maps 2 -> 2.0 and
    // 19..22 -> 2.1..2.4, and rt_test_translucent.rchit already turns that
    // fractional material id into transparent profiles 1..4.
    private static int resolveTranslucentMarker(
            Block block
    ) {
        // 2026-10-06 地狱传送门：必须**先于**玻璃族判定，也不能让它落进
        // "半透明体=窗口"的通用档（marker 2 → materialId 2.0 → 玻璃族）。
        if (block == Blocks.NETHER_PORTAL) {
            return RT_MARKER_NETHER_PORTAL;
        }

        // Exact glass family keeps its own chromatic profile.
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

        // Fluids carry FluidRendererMixin's water marker (1) already, and a
        // liquid block must never be relabelled as a translucent sheet.
        if (!state.getFluidState().isEmpty()) {
            return 0;
        }

        // Nothing here is a glass body by block identity, so the block alone
        // cannot decide the material.  MC 26.3 derives each quad's chunk layer
        // from the sprite's own pixels (ChunkSectionLayer.byTransparency): one
        // anti-aliased texel is enough to move leaves, grass overlays, vines,
        // fire and bars into the TRANSLUCENT layer next to windows.  Labelling
        // the whole layer as glass is what turned those blocks see-through, so
        // the answer is deferred to the quad's own texture in
        // tagTranslucentQuads.
        return 0;
    }


    // byte 27 remains a private RT-only marker in the existing 28-byte
    // terrain layout.  Keep the already confirmed water value 1 and clear
    // glass / clear glass pane value 2.  Stained glass and panes carry their
    // exact vanilla DyeColor ID in 3..18.  Native expands the marker only
    // inside its 48-byte RT vertex copy, never in the vanilla render mesh.
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


    // =====================================================
    // 玻璃连接纹理（CTM）连通性判定
    //
    // marker 2..19 是"能互相接缝"的玻璃族：清玻璃/玻璃板(2)、染色玻璃/
    // 染色板(3..18)、遮光玻璃(19)。冰/粘液/蜂蜜(20..22)是致密体，永远
    // 不是可连成一片的薄片。
    // =====================================================

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


    // 平窗档判定：这块面的主人是**原版普通玻璃**（立方体或玻璃板）。
    //
    // 不需要额外的几何判据 —— 玻璃板的面永远落在自己那一格内部（1/8、3/8
    // 的偏移），所以候选 A/B 两个探针格只会解析到 GLASS_PANE；而立方体的面
    // 正好压在整数格边界上，主人必然落在 A 或 B 里。混砌（cube 挨着 pane）
    // 时 pane 那一侧的两个候选仍是 pane 自己那格，不会被误判成平窗。
    //
    // 2026-10-12：用户要求把普通玻璃板的浮雕/光斑一并退档，所以这里从只认
    // Blocks.GLASS 扩到 Blocks.GLASS_PANE。不会漏到染色玻璃板：调用点还要求
    // translucentMarker == 2，而染色板带的是自己的 marker（3..18 段）。
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


    // 求出 quad 四条 sprite 边里哪几条与另一块玻璃相接。地形 quad 轴对齐，
    // 所以"边中点 - quad 中心"只有一个轴非零；把该轴取正负半格就到邻居
    // 方块的中心格。邻居查询走 RenderSectionRegion 的 3×3×3 SectionCopy
    // 数组，跨 section 的 ±1 格是安全的。
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

        // Cell that owns the quad.  A full block face never probes it, but a
        // pane attached flush to one side can step back into it; that is not a
        // neighbour and must leave the border alone.
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
                // Constant-u edge: u-min if it sits on the sprite's u-min side.
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


    // =====================================================
    // T1-1B-4 Translucent quad tagging (RT-only byte 27)
    //
    // ★ MUST run before NativeVulkan.uploadChunk ★
    //
    // Covers the whole TRANSLUCENT render layer, so every translucent body is
    // on the RT transparent path instead of the legacy opaque fallback.  A
    // vertex that already carries a marker (water = 1, written by
    // FluidRendererMixin during mesh baking) is left alone.
    // =====================================================
    /**
     * 2026-10-26：TRANSLUCENT 层的整层兜底标记。
     *
     * <p>Remix 里 translucent 层的材质来自绘制状态（blend state）与逐 texel
     * 的贴图 alpha，不存在"猜这个 quad 属于哪个方块"这一步；本工程拿不到绘制
     * 状态，只能由 {@link #tagTranslucentQuads} 用几何反推。反推失败的面会带着
     * byte27 = 0 进入 native，被当作不透明地形渲染 —— 表现为同一个玻璃方块的
     * 正面透明、侧面是纯色块。
     *
     * <p>这里把仍然为 0 的顶点一律标成半透明体 marker 2（水 = 1、玻璃族 3..18
     * 不动），使"是不是半透明"这一判断不再依赖几何反推是否命中。
     *
     * @return 本次新标记的顶点数
     */
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
                // 水（1）与玻璃族（3..18）已有正确身份，不覆盖。
                continue;
            }

            vertices.put(
                    markerOffset,
                    (byte) RT_MARKER_GENERIC_TRANSLUCENT
            );

            ++tagged;
        }

        // duplicate() 共享底层数据，写入已直接生效；这里显式声明意图。
        return tagged;
    }

    private static int tagTranslucentQuads(
            SectionPos sectionPos,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            ByteBuffer vertexBuffer,
            int vertexCount,
            int stride
    ) {
        // The native RT upload path is already locked to this
        // exact terrain vertex contract.
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

        // Hoisted above the quad loop: one set of vertex scratch arrays per
        // mesh instead of three allocations per quad.
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
            // Water already owns marker 1, written by FluidRendererMixin during
            // mesh baking.  One byte read per quad is enough to keep fluids out
            // of this pass, so the geometry probe below never runs for the
            // (very common) all-water Section.
            //
            // Glass arrives here already carrying marker 2: BlockModelLighterMixin
            // tags it while the mesh is baked, and that pass has no neighbour
            // access.  Those quads must still be processed so the connectivity
            // mask below can be written, but their byte-27 marker stays as-is.
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


            // Probe a tiny distance to both sides of the rendered
            // face. This avoids floor() ambiguity when a glass face
            // lies exactly on an integer block boundary.
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


            // Candidate A is the -geometric-normal side of the emitted
            // quad, i.e. the block that owns a normally wound exterior face.
            // Candidate B remains the boundary fallback retained by the
            // original exact matcher, and is only read when Candidate A
            // resolved to air / no marker.
            //
            // A quad that already arrived with a glass marker was tagged from
            // block identity by the model baker, so its marker is trusted and
            // the two lookups are skipped.
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

                // Block identity was inconclusive.  MC 26.3 puts anti-aliased
                // cutout art in this same layer, so the only remaining witness is
                // the quad's own sprite: a body that is uniformly half-transparent
                // is a window, one that is merely soft at its edges is foliage,
                // fire or bars.  UV0 is float u at +16 and v at +20 in the
                // confirmed 28-byte stride.
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

                    // A body judged only from its sprite is not a glass block,
                    // so it must never connect to its neighbours.
                    glassConnectCandidate =
                            false;
                }
            }


            if (
                    translucentMarker >= 2
            ) {
                // =================================================
                // T1-1B-4
                //
                // Terrain vertex stride is already confirmed as
                // 28 bytes by the native upload contract.
                //
                // Byte 27 is the high byte of packed lightCoords, which
                // vanilla never touches (LightTexture.pack only fills
                // bits 4..7 and 20..23), and which FluidRendererMixin
                // already uses for water = 1.
                //
                // Every translucent-family quad matched above receives its
                // RT-only marker here.  All other vertex bytes stay
                // untouched, and the buffer is uploaded to native AFTER this
                // loop so the marker actually reaches the GPU.
                //
                // Glass additionally gets its neighbour-connectivity mask
                // written into lightCoords bits 8..11 and, for plain glass
                // cubes, the flat-window flag into bit 12; native lifts both
                // into the RT Vertex.pad2 lane for the closest-hit shader.
                // =================================================

                final boolean glassConnect =
                        glassConnectCandidate &&
                                isGlassFamilyMarker(translucentMarker);

                // Every translucent quad whose owner is plain glass (cube or
                // pane) gets the flat-window flag.  Quads already tagged by the
                // model baker carry no neighbour information, so the owner is
                // looked up here either way.
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

    // =====================================================
    // 2026-10-24 项 2：透明层内部共享面剔除
    //
    // 原版 mesher 对 SOLID / CUTOUT 层做邻面剔除，但 **TRANSLUCENT 层不做** ——
    // 玻璃墙连成片时，每一对相邻方块的共享面会被两个方块各渲染一次，两份都进
    // RT 的 BLAS，并且参与每一次可见性射线与透射射线。
    //
    // 判据刻意收到最窄：
    //   · 只处理玻璃家族（byte 27 的 marker >= 2，见 isGlassFamilyMarker）。
    //     水（marker = 1）与 foliage / 火焰 / 栏杆一律不碰 —— 水的染色依赖
    //     界面数、栏杆形状根本没有"共享面"；
    //   · A / B 两侧必须是**同一个 Block**；
    //   · 两侧都必须是满方块碰撞形状 ⇒ 玻璃板 / 玻璃栏杆自动排除
    //     （相邻时并不共享一个面）。
    //
    // 语义代价（必须知道）：玻璃是半透明的，剔掉中间界面后，光穿过 n 层玻璃
    // 遇到的界面数从 2n 降到 2 ⇒ 染色累积变淡、菲涅尔次数减少。这是**更接近
    // 真实**的方向（现实里玻璃墙也只有内外两个界面），但观感确实会变，
    // 所以留了开关：-Drtx.glassInteriorCull=false 可关掉。
    // =====================================================
    // 诊断计数：本进程累计被内部面剔除吞掉的透明层顶点数。编译发生在 worker
    // 线程上，所以用 AtomicLong；只进日志，不参与任何判定。
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

        // 满方块形状这一条同时排除了玻璃板、玻璃栏杆、台阶形玻璃：
        // 它们相邻时并不共享一个完整平面，剔掉会真的丢几何。
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

        // 快速前置：至少要有两个玻璃家族 quad 才可能存在共享面（一个孤立方块
        // 没有邻居）。绝大多数 Section 没有玻璃，这一遍只读 1 字节/quad，
        // 换来的是省掉下面那一次整块 memcpy。
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

                    // 与 tagTranslucentQuads 同一个 ±0.01 探测口径：
                    // 面正好落在整数边界时 floor() 会有歧义。
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

                    drop = isInteriorSharedGlassFace(region, candidateA, candidateB);
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

    // Marker 23 is consumed only by VulkanCore while expanding the 28-byte
    // Minecraft vertex into its private 48-byte RT vertex.  It identifies a
    // solid axis-aligned face that may receive rain -- a horizontal top face
    // or a vertical wall face.  Actual sky exposure remains a per-frame
    // ray-traced decision in rgen, which tells the two orientations apart
    // from the geometric hit normal and applies the matching rain model.
    //
    // Both orientations share marker 23 on purpose: the marker only means
    // "this face is a rain-eligible solid exterior face", and the orientation
    // is already carried losslessly by the RT geometric normal.  That keeps
    // VulkanCore and rt_test.rchit untouched, so this change needs no DLL
    // rebuild.
    //
    // The tagging itself now lives in accumulateVptAlbedoAndTagRainPuddles
    // (fused with the VPT albedo pass); quadSpan below is its helper.
    // Extent of a quad corner coordinate along one axis.  A terrain quad is
    // axis aligned, so exactly one of the three spans collapses to zero and
    // names the face orientation.
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

        // =====================================================
        // 1 = 普通暖色火焰类
        // 火把 / 墙上火把 / 灯笼 / 营火 / 普通火
        // =====================================================

        if (
                block == Blocks.TORCH ||
                        block == Blocks.WALL_TORCH ||
                        block == Blocks.LANTERN ||
                        block == Blocks.CAMPFIRE ||
                        block == Blocks.FIRE
        ) {
            return 1;
        }


        // =====================================================
        // 2 = 灵魂火蓝青色
        // =====================================================

        if (
                block == Blocks.SOUL_TORCH ||
                        block == Blocks.SOUL_WALL_TORCH ||
                        block == Blocks.SOUL_LANTERN ||
                        block == Blocks.SOUL_CAMPFIRE ||
                        block == Blocks.SOUL_FIRE
        ) {
            return 2;
        }


        // =====================================================
        // 3 = 红石火把
        //
        // 注意：
        // REDSTONE_LAMP 不在这里。
        // 按已经确认的 A 方案归 type 5。
        // =====================================================

        if (
                block == Blocks.REDSTONE_TORCH ||
                        block == Blocks.REDSTONE_WALL_TORCH
        ) {
            return 3;
        }


        // =====================================================
        // 4 = 岩浆
        // =====================================================

        if (
                block == Blocks.LAVA
        ) {
            return 4;
        }


        // =====================================================
        // 5 = 暖白发光方块
        // =====================================================

        if (
                block == Blocks.GLOWSTONE ||
                        block == Blocks.SEA_LANTERN ||
                        block == Blocks.SHROOMLIGHT ||
                        block == Blocks.JACK_O_LANTERN ||
                        block == Blocks.REDSTONE_LAMP
        ) {
            return 5;
        }


        // =====================================================
        // 6 = 地狱传送门（紫）
        //
        // 2026-10-17：传送门此前落进 type 0（未分类）⇒ 着色器给的是暖白
        // (1.0,0.82,0.48)，所以门面自己是紫的、照到周围却是白光。typeCode
        // 只有 3 位（0..7），6 是空闲值。
        // 同步点：rt_test.rgen 的 vliDecodeEmitterColor / decodeLocalLightColor
        // 与 rt_vpt_irradiance.comp 的 emitterColor。
        // =====================================================

        if (
                block == Blocks.NETHER_PORTAL
        ) {
            return 6;
        }


        // =====================================================
        // 0 = 其他 emission > 0 的未知/未分类光源
        // =====================================================

        return 0;
    }

    // =====================================================
    // Fused SOLID/CUTOUT per-quad pass.
    //
    // This was previously two independent loops over the same vertex buffer
    // (accumulateVptVoxelAlbedo + tagRainPuddleCandidateQuads), each with its
    // own duplicate() of the buffer and its own vertex read.  The fused pass
    // reads every quad once and then applies both taggers with their original
    // independent early-continue conditions, so each quad's outcome is
    // bit-identical to the two-pass behaviour:
    //   - albedo accumulation only when globalVptAlbedoAtlas != null, skipped
    //     for degenerate normals (area <= 1e-6) and air probe blocks,
    //   - rain tagging only for quads with exactly one collapsed axis and an
    //     occluding owner block.
    // Byte 27 (the marker byte) is never read by the albedo logic, so tagging
    // quad N before reading quad N+1 cannot change any albedo result.
    // =====================================================
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

        // Read the atlas handle once per section: it only changes on a full
        // resource reload, which recompiles sections anyway.  The old code
        // dereferenced getPointer() per quad.
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

            // ---- VPT albedo accumulation (former accumulateVptVoxelAlbedo) --
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
                        // 一次标记：本段存在 VPT albedo ⇒ 体素字不能整段填充。
                        scratch.vptAny = true;
                    }
                }
            }

            // ---- 薄片背光透射标记（#24，2026-10-05）------------------
            // 仅 CUTOUT 层：叶面 quad 的背面（-法线侧）就是叶块；交叉 quad
            // （花草）中心所在格就是自身。两者任一命中 LEAVES/FLOWERS 标签
            // ⇒ 四顶点 byte27 写 marker 25（native → materialId 0.25）。
            // 每 quad 1-2 次 BlockState 查表，section 编译线程内可忽略。
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

            // ---- rain puddle tagging (former tagRainPuddleCandidateQuads) --
            // Classify the quad by how its four corners spread along the
            // three axes.  Terrain quads are axis aligned, so exactly one
            // axis must collapse.  No collapsed axis means a sloped or
            // crossed quad (plants, torches, fences) and more than one
            // collapsed axis means a degenerate quad; neither can carry a
            // rain film.
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
                // The original top-face probe: the owning solid block sits
                // directly below the face.  A downward face reads the block
                // below as air and is rejected, so undersides stay untagged.
                owner.set(
                        minX + (int)Math.floor(centerX),
                        minY + (int)Math.floor(centerY - 0.01f),
                        minZ + (int)Math.floor(centerZ)
                );

                ownerResolved = region.getBlockState(owner).canOcclude();
            } else {
                // A wall face has solid on one side and air on the other.
                // Probe both sides and accept whichever one occludes; an
                // interior face is accepted by the first probe, which is
                // harmless: the mesher does not emit interior faces.
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
        // Same predicate the former countTrackedGlassBlocks pass used, so the
        // glass-quad tagging gate and the DEBUG count are unchanged.
        int trackedGlassBlocks = 0;

        // 2026-10-21 Lithium 式前置判据：先问 palette（O(palette)），不扫 4096 格。
        //
        // 三种情况必须分开（26.3 语义）：
        //   sectionCopy == null        → 快照缺失（不该发生，防御性回退到逐体素）
        //   sectionCopy != null 且
        //     palette == null         → EmptyLevelChunk，整段空气（getBlockState 返回 AIR）
        //   palette != null           → 用 palette 条目数判断是否同质
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

        // 全空气段：4096 个字按定义全是 0（与下方 isAir 快路径逐位一致），
        // 因此直接发全 0、不进逐体素分类。scanEdge=0 即跳过三重循环。
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

                        // AIR fast path (bit-identical to the full path below):
                        // air has no tracked-glass marker (marker 0, no
                        // trackedGlassBlocks bump), zero light emission, and
                        // accumulateVptVoxelAlbedo never accumulates into an
                        // air voxel's cell -- it re-probes to the non-air side
                        // of the quad or skips the quad entirely -- so
                        // vptCount[air] == 0 keeps r5=g5=b5=0.  Every field of
                        // the voxel word is therefore exactly zero for air.
                        // Guard this invariant if another writer ever feeds
                        // vptCount/vptRed/... for air cells.
                        if (blockState.isAir()) {
                            voxelBuffer.putInt(0);
                            continue;
                        }

                        final int emission =
                                blockState.getLightEmission();

                        // The VPT DDA must be allowed to cross every tracked
                        // glass block/pane; stained glass carries its own RGB
                        // transmission in the voxel word below.
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
                            // canOcclude() is the *block-level* flag, so it is
                            // also true for slabs/stairs/walls/fences.  Only a
                            // full cube may be encoded as class 1; the rest get
                            // class 3 plus the fraction of the cell that is
                            // actually free for light to pass through.
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
                                    // 1..127, never 0: a fully black voxel would
                                    // be a hard box shadow again, which is the
                                    // artefact this whole class exists to remove.
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

                        // Interior/unmeshed blocks cannot be reached by the
                        // VPT DDA from open space, so a black fallback is the
                        // only safe value.  Do not invent a material colour.
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
        // 2026-10-21：palette 遍历的零分配探针（每个 worker 一份）。
        final PaletteProbe paletteProbe = new PaletteProbe();
        // 2026-10-21：本段是否累加过任何 VPT albedo。为 false ⇒ vptCount[] 全 0
        // ⇒ 体素字里的 r5/g5/b5 恒为 0 ⇒ 同质段可以只算一次字再整段填充。
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
