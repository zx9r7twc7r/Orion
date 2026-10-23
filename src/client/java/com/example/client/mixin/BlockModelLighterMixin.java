package com.example.client.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(BlockModelLighter.class)
public class BlockModelLighterMixin {

    // Per-quad hot path: System.getProperty would walk the properties table on
    // every glass quad; resolve the flag once instead.
    @Unique
    private static final boolean RTX_DEBUG =
            Boolean.getBoolean("rtx.debug");

    @Unique
    private static boolean vulkan$isGlass(
            BlockState state
    ) {
        var block =
                state.getBlock();

        return
                block == Blocks.GLASS ||
                        block instanceof StainedGlassBlock;
    }


    // 2026-10-09 修（用户："除了普通玻璃以外全量恢复成原来有折射有反射的样子"）：
    // 这里以前对**所有**玻璃都写 marker 2 —— 包括染色玻璃块。于是 native 的
    // applyStainedGlassDyeToRTVertex()（要求 marker 3..18）不会注入染料，RT 顶点色
    // 仍是原版灰面明暗 ⇒ rchit 的 glassVertexFactorIsFaceShade() 把它判成"普通玻璃"
    // ⇒ 置 RT_STATE_CLEAR_GLASS_BIT ⇒ rgen 的 isClearGlassHit() 把它**当空气**
    // （没有折射、没有反射、没有那条 F0=0.04 的镜面射线）。
    // 染色玻璃板不走这里（它们由 SectionCompilerMixin 标记 3+dyeId），所以症状一直是
    // "只有彩色玻璃板被恢复了"。映射与 SectionCompilerMixin.resolveTrackedGlassMarker()
    // 逐字同一份。
    @Unique
    private static int vulkan$glassMarker(
            BlockState state
    ) {
        var block =
                state.getBlock();

        if (block == Blocks.GLASS) {
            return 2;
        }

        if (block instanceof StainedGlassBlock stainedGlass) {
            final int dyeId =
                    Math.max(
                            0,
                            Math.min(
                                    15,
                                    stainedGlass.getColor().getId()
                            )
                    );

            return 3 + dyeId;
        }

        return 0;
    }


    @Unique
    private static void vulkan$tagGlass(
            BlockState state,
            QuadInstance outputInstance
    ) {
        if (!vulkan$isGlass(state)) {
            return;
        }

        final int marker =
                vulkan$glassMarker(
                        state
                );

        if (marker == 0) {
            return;
        }


        if (RTX_DEBUG) {
            System.out.println(
                    "[T1-1B GLASS PROBE]"
                            + " block="
                            + state.getBlock()
                            + " marker="
                            + marker
            );
        }


        for (int vertex = 0; vertex < 4; ++vertex) {
            int lightCoords =
                    outputInstance.getLightCoords(
                            vertex
                    );


            outputInstance.setLightCoords(
                    vertex,
                    (lightCoords & 0x00FFFFFF) |
                            (marker << 24)
            );
        }
    }


    @Inject(
            method = "prepareQuadAmbientOcclusion",
            at = @At("RETURN")
    )
    private void vulkan$tagGlassAO(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            BakedQuad quad,
            QuadInstance outputInstance,
            CallbackInfo ci
    ) {
        vulkan$tagGlass(
                state,
                outputInstance
        );
    }


    @Inject(
            method = "prepareQuadFlat",
            at = @At("RETURN")
    )
    private void vulkan$tagGlassFlat(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            int lightCoords,
            BakedQuad quad,
            QuadInstance outputInstance,
            CallbackInfo ci
    ) {
        vulkan$tagGlass(
                state,
                outputInstance
        );
    }
}