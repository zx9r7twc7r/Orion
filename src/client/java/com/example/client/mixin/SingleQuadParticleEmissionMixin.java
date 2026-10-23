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
        // 2026-10-09 运动矢量：把**已插值、相机相对**的粒子中心一并交给捕获侧 ——
        // 位移就是"同一粒子帧间的这个量之差 + 相机位移"（见 ParticleRtCapture 里
        // updateParticleMotionCamera / beginParticleExtract 的推导）。
        // 这里不读 Particle 的 xo/x：该版本 MC 的 Particle 没有可用的位置 getter。
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
