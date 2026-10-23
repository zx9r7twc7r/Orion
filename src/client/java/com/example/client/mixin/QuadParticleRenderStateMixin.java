package com.example.client.mixin;

import com.example.client.ParticleRtCapture;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the finalized quad render-state shared by every vanilla
 * SingleQuadParticle.  This is deliberately downstream of individual
 * particle subclasses, so terrain/block-break particles cannot bypass the
 * RT capture by overriding their own extraction helper.
 */
@Mixin(QuadParticleRenderState.class)
public abstract class QuadParticleRenderStateMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void rtx$captureRtParticle(
            SingleQuadParticle.Layer layer,
            float x,
            float y,
            float z,
            float xRot,
            float yRot,
            float zRot,
            float wRot,
            float scale,
            float u0,
            float u1,
            float v0,
            float v1,
            int color,
            int lightCoords,
            CallbackInfo ci) {
        ParticleRtCapture.capture(
                layer,
                new Quaternionf(xRot, yRot, zRot, wRot),
                x,
                y,
                z,
                scale,
                u0,
                u1,
                v0,
                v1,
                color,
                lightCoords
        );
    }
}
