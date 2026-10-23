package com.example.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    // 强制突破 private 限制，暴露底层的像素数据对象
    @Accessor("originalImage")
    NativeImage getOriginalImage();
}