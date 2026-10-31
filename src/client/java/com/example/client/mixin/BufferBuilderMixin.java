package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.BufferBuilder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {

    @Shadow
    private boolean blockFormat;

    @Shadow
    private long vertexPointer;

    @Inject(
            method = "addVertex(FFFIFFIIFFF)V",
            at = @At("TAIL")
    )
    private void onAddVertex(
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
            float nz,
            CallbackInfo ci
    ) {
        EntityRtCapture.captureVertex(x, y, z, color, u, v);

        if (!this.blockFormat) {
            return;
        }

        if (this.vertexPointer == -1L) {
            return;
        }

        if (Float.isNaN(nx) || Float.isInfinite(nx)) {
            return;
        }

        int materialId = Math.round(nx);

        if (materialId < 0) {
            materialId = 0;
        }

        if (materialId > 255) {
            materialId = 255;
        }

        MemoryUtil.memPutByte(
                this.vertexPointer + 31L,
                (byte) materialId
        );
    }

}
