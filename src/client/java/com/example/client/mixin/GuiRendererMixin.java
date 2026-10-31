package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;

import net.minecraft.client.gui.render.GuiRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;


@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    private static boolean mainTargetFormatLogged = false;

    @Inject(
            method = "executeDrawRange",
            at = @At("HEAD")
    )
    private void vulkan$armRtBeforeGui(
            Supplier<String> label,
            RenderTarget mainRenderTarget,
            GpuBufferSlice dynamicTransforms,
            int startIndex,
            int endIndex,
            CallbackInfo ci
    ) {
        if(!mainTargetFormatLogged) {
            mainTargetFormatLogged = true;

            GpuTexture loggedTexture =
                    mainRenderTarget.getColorTexture();

            System.out.println(
                    "[HDR-2.4 MAIN TARGET]"
                            + " format="
                            + (loggedTexture == null
                            ? "null"
                            : loggedTexture.getFormat())
                            + " usage=0x"
                            + (loggedTexture == null
                            ? "?"
                            : Integer.toHexString(loggedTexture.usage()))
            );
        }

        if (NativeVulkan.rtInjectedToMainTarget ||
                NativeVulkan.guiRtPending) {

            return;
        }


        GpuTexture colorTexture =
                mainRenderTarget.getColorTexture();


        if (!(colorTexture instanceof VulkanGpuTexture vulkanTexture)) {
            return;
        }


        if ((colorTexture.usage() & GpuTexture.USAGE_COPY_DST) == 0) {

            System.err.println(
                    "[RT UI] Main target has no COPY_DST; "
                            + "falling back to direct swapchain path"
            );

            return;
        }


        long image =
                vulkanTexture.vkImage();

        int width =
                colorTexture.getWidth(0);

        int height =
                colorTexture.getHeight(0);


        if (image == 0L ||
                width <= 0 ||
                height <= 0) {

            return;
        }


        NativeVulkan.pendingGuiTargetImage =
                image;

        NativeVulkan.pendingGuiTargetWidth =
                width;

        NativeVulkan.pendingGuiTargetHeight =
                height;

        NativeVulkan.guiRtPending =
                true;
    }
}