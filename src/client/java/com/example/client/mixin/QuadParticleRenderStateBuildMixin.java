package com.example.client.mixin;

import com.example.client.ParticleRtCapture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleRenderState.class)
public abstract class QuadParticleRenderStateBuildMixin {
    @Inject(method = "buildLayer", at = @At("HEAD"))
    private void rtx$setActiveLayer(SingleQuadParticle.Layer layer, VertexConsumer bufferBuilder, CallbackInfo ci) {
        ParticleRtCapture.beginBuildLayer(layer);
    }

    @Inject(method = "buildLayer", at = @At("RETURN"))
    private void rtx$clearActiveLayer(SingleQuadParticle.Layer layer, VertexConsumer bufferBuilder, CallbackInfo ci) {
        ParticleRtCapture.endBuildLayer();
    }
}
