package com.example.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kept inert for compatibility.  RT weather geometry is generated directly
 * by ParticleRtCapture and never enters Minecraft's ParticleEngine.
 */
@Mixin(Minecraft.class)
public final class RainDropMixin {
}
