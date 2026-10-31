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