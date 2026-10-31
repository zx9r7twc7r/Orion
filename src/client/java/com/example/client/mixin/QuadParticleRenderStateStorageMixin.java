package com.example.client.mixin;

import com.example.client.ParticleRtCapture;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs while QuadParticleRenderState.buildLayer() consumes its stored quads.
 * That happens inside LevelRenderer's frame graph, after beginFrame() and
 * before endFrame(), unlike particle extraction which precedes the frame.
 */
@Mixin(targets = "net.minecraft.client.renderer.state.level.QuadParticleRenderState$Storage")
public abstract class QuadParticleRenderStateStorageMixin {
    @Shadow private int currentParticleIndex;

    @Unique private int[] rtx$emissionMarkers = new int[1024];
    @Unique private int rtx$renderCursor;

    @Inject(method = "add", at = @At("HEAD"))
    private void rtx$rememberEmissionMarker(
            float x, float y, float z, float xRot, float yRot, float zRot, float wRot,
            float scale, float u0, float u1, float v0, float v1, int color, int lightCoords,
            CallbackInfo ci) {
        if (this.currentParticleIndex >= this.rtx$emissionMarkers.length) {
            int[] grown = new int[this.rtx$emissionMarkers.length * 2];
            System.arraycopy(this.rtx$emissionMarkers, 0, grown, 0, this.rtx$emissionMarkers.length);
            this.rtx$emissionMarkers = grown;
        }
        this.rtx$emissionMarkers[this.currentParticleIndex] = ParticleRtCapture.currentExtractEmissionMarker();
    }

    @Inject(method = "forEachParticle", at = @At("HEAD"))
    private void rtx$resetRenderCursor(QuadParticleRenderState.ParticleConsumer consumer, CallbackInfo ci) {
        this.rtx$renderCursor = 0;
    }

    @Redirect(
            method = "forEachParticle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState$ParticleConsumer;consume(FFFFFFFFFFFFII)V"
            )
    )
    private void rtx$captureRtParticleAtSubmit(
            QuadParticleRenderState.ParticleConsumer consumer,
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
            int lightCoords) {
        int marker = this.rtx$emissionMarkers[this.rtx$renderCursor++];
        if (!ParticleRtCapture.isRtRainMarker(marker)) {
            consumer.consume(x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords);
        }
        ParticleRtCapture.capture(
                ParticleRtCapture.currentBuildLayer(),
                x, y, z, scale, u0, u1, v0, v1,
                xRot, yRot, zRot, wRot,
                color,
                marker
        );
    }
}
