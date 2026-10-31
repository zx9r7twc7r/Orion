package com.example.client.mixin;

import com.example.client.ParticleRtCapture;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleEmissionMixin {
    @Inject(
            method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At("HEAD")
    )
    private void rtx$beginEmissionHandoff(
            QuadParticleRenderState state, Quaternionf rotation,
            float x, float y, float z, float partialTickTime, CallbackInfo ci) {
        ParticleRtCapture.beginParticleExtract(
                (SingleQuadParticle) (Object) this, x, y, z);
    }

    @Inject(
            method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At("RETURN")
    )
    private void rtx$endEmissionHandoff(
            QuadParticleRenderState state, Quaternionf rotation,
            float x, float y, float z, float partialTickTime, CallbackInfo ci) {
        ParticleRtCapture.endParticleExtract();
    }
}
