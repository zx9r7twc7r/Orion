package com.example.client.mixin;

import com.example.client.access.SpriteContentsExtension;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public class SpriteContentsMixin implements SpriteContentsExtension {

    @Unique
    private NativeImage normalImage = null;

    @Unique
    private NativeImage specularImage = null;

    @Override
    public NativeImage fabric_getNormalImage() { return this.normalImage; }

    @Override
    public void fabric_setNormalImage(NativeImage image) { this.normalImage = image; }

    @Override
    public NativeImage fabric_getSpecularImage() { return this.specularImage; }

    @Override
    public void fabric_setSpecularImage(NativeImage image) { this.specularImage = image; }

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        if (this.normalImage != null) {
            this.normalImage.close();
            this.normalImage = null;
        }
        if (this.specularImage != null) {
            this.specularImage.close();
            this.specularImage = null;
        }
    }
}