package com.example.client.mixin;

import com.example.client.ParticleRtCapture;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.ARGB;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin {
    @Shadow protected float alpha;
    @Shadow protected float rCol;
    @Shadow protected float gCol;
    @Shadow protected float bCol;
    @Shadow protected abstract SingleQuadParticle.Layer getLayer();
    @Shadow protected abstract float getU0();
    @Shadow protected abstract float getU1();
    @Shadow protected abstract float getV0();
    @Shadow protected abstract float getV1();
    @Shadow public abstract float getQuadSize(float partialTickTime);

    @Inject(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V", at = @At("HEAD"))
    private void rtx$captureRtParticle(QuadParticleRenderState state, Quaternionf rotation,
                                       float x, float y, float z, float partialTickTime, CallbackInfo ci) {
        ParticleRtCapture.capture(getLayer(), new Quaternionf(rotation), x, y, z,
                getQuadSize(partialTickTime), getU0(), getU1(), getV0(), getV1(),
                ARGB.colorFromFloat(alpha, rCol, gCol, bCol), 0);
    }
}
