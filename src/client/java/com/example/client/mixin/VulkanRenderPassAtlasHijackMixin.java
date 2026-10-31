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
    private static long lastImageView = 0L;
    private static long lastAtlasEpoch = 0L;

    @Inject(method = "setUniform", at = @At("HEAD"))
    private void onBindTexture(String name, GpuTextureView textureView, GpuSampler sampler, CallbackInfo ci) {
        if ("Sampler0".equals(name) && textureView instanceof VulkanGpuTextureView && sampler instanceof VulkanGpuSampler) {
            String label = ((VulkanGpuTextureView) textureView).texture().getLabel();
            long imageViewHandle = ((VulkanGpuTextureView) textureView).vkImageView();
            long samplerHandle = ((VulkanGpuSampler) sampler).vkSampler();

            EntityRtCapture.onTextureBound(label, imageViewHandle, samplerHandle);

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
