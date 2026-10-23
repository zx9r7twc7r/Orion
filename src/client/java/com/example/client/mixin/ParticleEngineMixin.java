package com.example.client.mixin;

import com.example.client.ParticleRtCapture;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    // Custom RT weather never uses ParticleEngine.  Suppress any residual
    // vanilla rain-particle creation so it cannot appear as a raster overlay.
    // Vanilla water bubbles are suppressed for the same reason: the RT water
    // surface renders its own foam/wake, and the raster bubble quads pop
    // through it as flat billboard artifacts.
    private static boolean rtx$isSuppressedVanillaParticle(ParticleOptions options) {
        String name = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()).getPath();
        return "rain".equals(name)
            || "bubble".equals(name)
            || "bubble_pop".equals(name)
            || "bubble_column_up".equals(name)
            || "bubble_column_updown".equals(name)
            || "suspended".equals(name)
            || "suspended_depth".equals(name);
    }

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void rtx$blockVanillaRainParticle(
            ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
            CallbackInfoReturnable<Particle> cir) {
        if (rtx$isSuppressedVanillaParticle(options)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "createParticle", at = @At("RETURN"))
    private void rtx$rememberEmissionType(
            ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
            CallbackInfoReturnable<Particle> cir) {
        Particle particle = cir.getReturnValue();
        if (particle != null) {
            ParticleRtCapture.rememberEmissionType(particle, options);
        }
    }
}
