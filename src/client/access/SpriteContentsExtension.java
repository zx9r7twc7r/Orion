package com.example.client.access;

import com.mojang.blaze3d.platform.NativeImage;

public interface SpriteContentsExtension {
    NativeImage fabric_getNormalImage();
    void fabric_setNormalImage(NativeImage image);

    NativeImage fabric_getSpecularImage();
    void fabric_setSpecularImage(NativeImage image);
}