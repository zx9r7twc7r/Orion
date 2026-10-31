package com.example.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.2 owns precipitation in a separate WeatherEffectRenderer.
 * Cancelling this renderer is independent of LevelRenderer's legacy weather
 * helper, whose optional injection can otherwise silently miss after mapping
 * changes.  RT weather is submitted by ParticleRtCapture instead.
 *
 * <p>LevelRenderer drives this renderer through three entry points, all of
 * which have to be cancelled to actually remove the vanilla pass:
 * {@code extractRenderState} (called by the level extractor),
 * {@code prepare} and {@code render}/{@code renderOit}.
 */
@Mixin(targets = "net.minecraft.client.renderer.WeatherEffectRenderer")
public abstract class WeatherEffectRendererMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void rtx$skipVanillaPrecipitationExtraction(
            ClientLevel level, float partialTick, Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true, require = 0)
    private void rtx$skipVanillaPrecipitationPrepare(
            Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true, require = 0)
    private void rtx$disableVanillaPrecipitationOit(
            OitStage stage, WeatherRenderState state, RenderPass pass, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void rtx$disableVanillaPrecipitation(CallbackInfo ci) {
        ci.cancel();
    }
}