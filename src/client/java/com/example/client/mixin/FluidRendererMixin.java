package com.example.client.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidRenderer.class)
public class FluidRendererMixin {

    @Unique
    private static final ThreadLocal<Boolean> vulkan$isWater =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @ModifyVariable(
            method = "tesselate",
            at = @At("HEAD"),
            argsOnly = true
    )
    private FluidState vulkan$captureFluidState(
            FluidState fluidState
    ) {
        vulkan$isWater.set(
                fluidState.is(FluidTags.WATER)
        );

        return fluidState;
    }

    @ModifyVariable(
            method = "tesselate",
            at = @At("HEAD"),
            argsOnly = true
    )
    private FluidRenderer.Output vulkan$wrapOutput(
            FluidRenderer.Output original
    ) {
        return new FluidRenderer.Output() {
            @Override
            public VertexConsumer getBuilder(
                    ChunkSectionLayer layer
            ) {
                return new MaterialVertexConsumer(
                        original.getBuilder(layer)
                );
            }
        };
    }

    @Inject(
            method = "tesselate",
            at = @At("RETURN")
    )
    private void vulkan$clearFluidState(
            CallbackInfo ci
    ) {
        vulkan$isWater.remove();
    }

    @Unique
    private static int vulkan$tagLight(
            int lightCoords
    ) {
        int materialId =
                Boolean.TRUE.equals(vulkan$isWater.get())
                        ? 1
                        : 0;

        return
                (lightCoords & 0x00FFFFFF) |
                        (materialId << 24);
    }

    @Unique
    private static class MaterialVertexConsumer
            implements VertexConsumer {

        private final VertexConsumer delegate;

        MaterialVertexConsumer(
                VertexConsumer delegate
        ) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer addVertex(
                float x,
                float y,
                float z
        ) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(
                int r,
                int g,
                int b,
                int a
        ) {
            delegate.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setColor(
                int color
        ) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(
                float u,
                float v
        ) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(
                int u,
                int v
        ) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setOverlay(
                int packedOverlayCoords
        ) {
            delegate.setOverlay(
                    packedOverlayCoords
            );

            return this;
        }

        @Override
        public VertexConsumer setUv2(
                int u,
                int v
        ) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv3(
                float u,
                float v
        ) {
            delegate.setUv3(u, v);
            return this;
        }

        @Override
        public VertexConsumer setLight(
                int packedLightCoords
        ) {
            delegate.setLight(
                    vulkan$tagLight(
                            packedLightCoords
                    )
            );

            return this;
        }

        @Override
        public VertexConsumer setNormal(
                float x,
                float y,
                float z
        ) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(
                float width
        ) {
            delegate.setLineWidth(width);
            return this;
        }

        @Override
        public void addVertex(
                float x,
                float y,
                float z,
                int color,
                float u,
                float v,
                int overlayCoords,
                int lightCoords,
                float nx,
                float ny,
                float nz
        ) {
            delegate.addVertex(
                    x,
                    y,
                    z,
                    color,
                    u,
                    v,
                    overlayCoords,
                    vulkan$tagLight(lightCoords),
                    nx,
                    ny,
                    nz
            );
        }
    }
}
