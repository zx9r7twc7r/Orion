package com.example.client.mixin;

import com.example.client.access.SpriteContentsExtension;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

@Mixin(SpriteLoader.class)
public class SpriteLoaderMixin {

    @Inject(method = "stitch", at = @At("HEAD"))
    private void onStitchHead(List<SpriteContents> sprites, int maxMipmapLevels, Executor executor, CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();

        for (SpriteContents sprite : sprites) {
            Identifier id = sprite.name();

            String normalPath = "textures/" + id.getPath() + "_n.png";
            String specularPath = "textures/" + id.getPath() + "_s.png";

            Identifier normalId = Identifier.fromNamespaceAndPath(id.getNamespace(), normalPath);
            Identifier specularId = Identifier.fromNamespaceAndPath(id.getNamespace(), specularPath);
            Optional<Resource> normalResource = manager.getResource(normalId);
            if (normalResource.isPresent()) {
                try (InputStream is = normalResource.get().open()) {
                    NativeImage img = NativeImage.read(is);
                    ((SpriteContentsExtension) sprite).fabric_setNormalImage(img);
                } catch (Exception e) {
                }
            }

            Optional<Resource> specularResource = manager.getResource(specularId);
            if (specularResource.isPresent()) {
                try (InputStream is = specularResource.get().open()) {
                    NativeImage img = NativeImage.read(is);
                    ((SpriteContentsExtension) sprite).fabric_setSpecularImage(img);
                } catch (Exception e) {
                }
            }
        }
    }
}