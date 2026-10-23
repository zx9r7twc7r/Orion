package com.example.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanRenderPass.class, remap = false)
public interface VulkanRenderPassAccessor {

    @Accessor("commandBuffer")
    VkCommandBuffer getCommandBuffer();

    @Accessor("outputWidth")
    int getOutputWidth();

    @Accessor("outputHeight")
    int getOutputHeight();
}