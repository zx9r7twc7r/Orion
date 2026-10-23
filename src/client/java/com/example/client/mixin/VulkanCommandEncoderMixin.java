package com.example.client.mixin;


import com.example.client.FrameBudget;
import com.example.vulkan.NativeVulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import org.lwjgl.vulkan.VkCommandBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;



@Mixin(value = VulkanCommandEncoder.class, remap = false)
public abstract class VulkanCommandEncoderMixin {


    @Shadow
    private VkCommandBuffer currentCommandBuffer;



    @Inject(
            method = "allocateAndBeginTransientCommandBuffer",
            at = @At("RETURN"),
            remap = false
    )
    private void captureCommandBuffer(
            CallbackInfoReturnable<VkCommandBuffer> cir
    )
    {
        VkCommandBuffer returnedCommandBuffer =
                cir.getReturnValue();

        if (returnedCommandBuffer == null)
        {
            NativeVulkan.currentCommandBuffer = 0L;

            System.out.println(
                    "[RT TRANSIENT CMD] return value null"
            );

            return;
        }


        long handle =
                returnedCommandBuffer.address();


        NativeVulkan.currentCommandBuffer =
                handle;
    }


    @Inject(
            method = "createRenderPass",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRDynamicRendering;"
                                    + "vkCmdBeginRenderingKHR("
                                    + "Lorg/lwjgl/vulkan/VkCommandBuffer;"
                                    + "Lorg/lwjgl/vulkan/VkRenderingInfo;"
                                    + ")V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void vulkan$injectRtBeforeGui(
            RenderPassDescriptor descriptor,
            CallbackInfoReturnable<RenderPassBackend> cir
    ) {
        if (!NativeVulkan.guiRtPending ||
                NativeVulkan.rtInjectedToMainTarget ||
                currentCommandBuffer == null) {

            return;
        }


        var colorAttachments =
                descriptor.colorAttachments();


        if (colorAttachments == null ||
                colorAttachments.isEmpty()) {

            return;
        }


        for (var attachment : colorAttachments) {

            if (attachment == null) {
                continue;
            }


            GpuTextureView view =
                    attachment.textureView();


            if (view == null ||
                    !(view.texture()
                            instanceof VulkanGpuTexture texture)) {

                continue;
            }


            long image =
                    texture.vkImage();


            if (image == 0L ||
                    image != NativeVulkan.pendingGuiTargetImage) {

                continue;
            }


            NativeVulkan.recordFrameCommand(
                    currentCommandBuffer.address(),
                    image,
                    NativeVulkan.pendingGuiTargetWidth,
                    NativeVulkan.pendingGuiTargetHeight,
                    true
            );


            NativeVulkan.guiRtPending =
                    false;

            NativeVulkan.rtInjectedToMainTarget =
                    true;

            return;
        }
    }




    @Inject(
            method = "endCommandBuffer",
            at = @At("HEAD"),
            remap = false
    )
    private void captureBeforeEnd(
            CallbackInfo ci
    )
    {

        if(currentCommandBuffer == null)
        {
            return;   // 这里不要清零
        }


        long handle =
                currentCommandBuffer.address();


        if(handle != 0)
        {
            NativeVulkan.currentCommandBuffer =
                    handle;
        }

    }
    @Inject(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/backend/vulkan/"
                                    + "VulkanQueue$Submission;"
                                    + "close()V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void reflex$renderSubmitStart(
            CallbackInfo ci
    ) {
        NativeVulkan.markRenderSubmitStart();
    }


    @Inject(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/backend/vulkan/"
                                    + "VulkanQueue$Submission;"
                                    + "close()V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void reflex$renderSubmitEnd(
            CallbackInfo ci
    ) {
        NativeVulkan.markRenderSubmitEnd();
    }

    // ---------------------------------------------------------------
    // 2026-10-22 A1（帧预算第九段）：submit 的整体墙钟。
    //
    // 为什么量它：实测 frameTail 中位数 9.22ms，而 acquire(0.015)+present(0.197)
    // 只占 0.21ms ⇒ 剩下约 9ms 无处归因。vkQueueSubmit 在队列满 / 帧生成排队 /
    // 帧节奏受限时是会阻塞的，而这一段恰好落在 render() 之外 ⇒ 它是头号嫌疑。
    // 一帧可能 submit 多次（多 pass / 帧生成）⇒ FrameBudget 按帧累加并计数。
    // 纯读时钟，不改任何渲染行为。
    // ---------------------------------------------------------------
    @Inject(method = "submit", at = @At("HEAD"), remap = false)
    private void frameBudget$submitHead(CallbackInfo ci) {
        FrameBudget.submitHead();
    }

    @Inject(method = "submit", at = @At("RETURN"), remap = false)
    private void frameBudget$submitTail(CallbackInfo ci) {
        FrameBudget.submitTail();
    }
}
