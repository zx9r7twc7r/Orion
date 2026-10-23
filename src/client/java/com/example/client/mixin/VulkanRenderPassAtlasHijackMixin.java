package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;
import com.example.client.EntityRtCapture;
import com.example.client.access.PBRAtlasStorage;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FrontendRenderPass.class, remap = false)
public class VulkanRenderPassAtlasHijackMixin {
    // Kept as a pair, and compared as one. A resource reload frees the
    // previous view and the driver is free to hand the replacement that same
    // value, so the view alone cannot separate a new atlas from the old one.
    // The epoch is bumped by the block-atlas reload hook in
    // PBRAtlasStorage, which is what makes the next bind look changed again.
    private static long lastImageView = 0L;
    private static long lastAtlasEpoch = 0L;

    @Inject(method = "setUniform", at = @At("HEAD"))
    private void onBindTexture(String name, GpuTextureView textureView, GpuSampler sampler, CallbackInfo ci) {
        if ("Sampler0".equals(name) && textureView instanceof VulkanGpuTextureView && sampler instanceof VulkanGpuSampler) {
            String label = ((VulkanGpuTextureView) textureView).texture().getLabel();
            long imageViewHandle = ((VulkanGpuTextureView) textureView).vkImageView();
            long samplerHandle = ((VulkanGpuSampler) sampler).vkSampler();

            EntityRtCapture.onTextureBound(label, imageViewHandle, samplerHandle);

            // 绝对物理指纹过滤
            if ("minecraft:textures/atlas/blocks.png".equals(label)) {
                final long atlasEpoch = PBRAtlasStorage.atlasEpoch();
                if (imageViewHandle != 0L && samplerHandle != 0L &&
                        (imageViewHandle != lastImageView || atlasEpoch != lastAtlasEpoch)) {
                    System.out.println("[ATLAS HIJACK] Target Locked! VkImageView: 0x" + Long.toHexString(imageViewHandle));
                    System.out.println("[ATLAS] BEFORE updateAtlas");

                    NativeVulkan.updateAtlas(imageViewHandle, samplerHandle);

                    System.out.println("[ATLAS] AFTER updateAtlas");
                    lastImageView = imageViewHandle;
                    lastAtlasEpoch = atlasEpoch;
                }
            }
        }
    }
}
