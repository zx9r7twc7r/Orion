package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanRenderPass.class, remap = false)
public class VulkanRenderPassMixin {

    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void onSetPipelineCaptureCmd(CallbackInfo ci) {
        VkCommandBuffer cmd = ((VulkanRenderPassAccessor) this).getCommandBuffer();
        if (cmd != null) {
            NativeVulkan.currentCommandBuffer = cmd.address();
        }
    }
}