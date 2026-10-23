package com.example.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Stable access to the active camera; 26.3 no longer exposes the old public GameRenderer accessor. */
@Mixin(GameRenderer.class)
public interface GameRendererCameraAccessor {
    @Accessor("mainCamera")
    Camera rtx$getMainCamera();
}
