package com.example.client.mixin;

import com.example.client.config.RTXSettingsConfig;
import com.example.vulkan.NativeVulkan;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.LongList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.lang.reflect.Field;
import com.llamalad7.mixinextras.sugar.Local;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;

import org.lwjgl.system.MemoryUtil;

import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = VulkanGpuSurface.class, remap = false)
public abstract class VulkanGpuSurfaceMixin {
    @Shadow
    @Final
    private VulkanDevice device;
    @Shadow
    @Final
    private int swapchainImageFormat;
    private static boolean hdrSurfaceProbePrinted =
            false;
    private static int hdrSurfaceFormatPickCount =
            0;
    private static boolean hdrNativeProbeDone =
            false;
    private static volatile boolean frameFieldsResolved = false;
    private static volatile boolean frameFieldsUnavailable = false;
    private static volatile boolean frameFailureReported = false;
    private static Field surfaceHandleField;
    private static Field swapchainImagesField;
    private static Field currentImageIndexField;
    private static Field swapchainWidthField;
    private static Field swapchainHeightField;
    private static Field swapchainSuboptimalField;

    private static boolean resolveFrameFields() {
        if (frameFieldsResolved) {
            return true;
        }
        if (frameFieldsUnavailable) {
            return false;
        }

        synchronized (VulkanGpuSurfaceMixin.class) {
            if (frameFieldsResolved) {
                return true;
            }
            if (frameFieldsUnavailable) {
                return false;
            }

            try {
                surfaceHandleField = VulkanGpuSurface.class.getDeclaredField("surface");
                swapchainImagesField = VulkanGpuSurface.class.getDeclaredField("swapchainImages");
                currentImageIndexField = VulkanGpuSurface.class.getDeclaredField("currentImageIndex");
                swapchainWidthField = VulkanGpuSurface.class.getDeclaredField("swapchainWidth");
                swapchainHeightField = VulkanGpuSurface.class.getDeclaredField("swapchainHeight");
                swapchainSuboptimalField = VulkanGpuSurface.class.getDeclaredField("swapchainSuboptimal");
                surfaceHandleField.setAccessible(true);
                swapchainImagesField.setAccessible(true);
                currentImageIndexField.setAccessible(true);
                swapchainWidthField.setAccessible(true);
                swapchainHeightField.setAccessible(true);
                swapchainSuboptimalField.setAccessible(true);
                frameFieldsResolved = true;
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                frameFieldsUnavailable = true;
                System.err.println("[RTX] Unable to cache surface fields: " + e.getClass().getSimpleName());
                return false;
            }
        }
    }

    private static void dumpHDRSurfaceFieldsOnce(
            VulkanGpuSurface surface
    ) {
        if(hdrSurfaceProbePrinted) {
            return;
        }

        hdrSurfaceProbePrinted =
                true;


        System.out.println(
                "========== [HDR SURFACE PROBE BEGIN] =========="
        );


        Class<?> clazz =
                surface.getClass();


        while(clazz != null) {

            for(Field field : clazz.getDeclaredFields()) {

                try {
                    field.setAccessible(
                            true
                    );


                    Object value =
                            field.get(
                                    surface
                            );


                    String valueText;


                    if(field.getType() == long.class) {

                        long longValue =
                                field.getLong(
                                        surface
                                );


                        valueText =
                                longValue
                                        + " / 0x"
                                        + Long.toHexString(
                                        longValue
                                );

                    } else if(
                            value == null ||
                                    value instanceof Number ||
                                    value instanceof Boolean ||
                                    value instanceof String ||
                                    value instanceof Enum<?>
                    ) {

                        valueText =
                                String.valueOf(
                                        value
                                );

                    } else {

                        valueText =
                                "<"
                                        + value.getClass()
                                        .getName()
                                        + ">";
                    }


                    System.out.println(
                            "[HDR SURFACE FIELD]"
                                    + " owner="
                                    + clazz.getName()
                                    + " name="
                                    + field.getName()
                                    + " type="
                                    + field.getType()
                                    .getName()
                                    + " value="
                                    + valueText
                    );

                } catch(Throwable e) {

                    System.out.println(
                            "[HDR SURFACE FIELD]"
                                    + " owner="
                                    + clazz.getName()
                                    + " name="
                                    + field.getName()
                                    + " ERROR="
                                    + e.getClass()
                                    .getSimpleName()
                    );
                }
            }


            clazz =
                    clazz.getSuperclass();
        }


        System.out.println(
                "========== [HDR SURFACE PROBE END] =========="
        );
    }

    @Inject(
            method = "blitFromTexture",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void afterBlit(
            CommandEncoderBackend commandEncoder,
            GpuTextureView textureView,
            CallbackInfo ci,
            @Local VkCommandBuffer blitCommandBuffer
    ) {
        try {

            VulkanGpuSurface surface =
                    (VulkanGpuSurface)(Object)this;
            if (!resolveFrameFields()) {
                return;
            }


            if (NativeVulkan.hdrSwapchainDirty) {
                NativeVulkan.hdrSwapchainDirty = false;

                swapchainSuboptimalField.setBoolean(surface, true);

                System.out.println("[HDR-2.3] requested swapchain rebuild");
            }

            if(!hdrNativeProbeDone) {
                try {
                    long surfaceHandle = surfaceHandleField.getLong(surface);
                    if(surfaceHandle != 0L) {
                        NativeVulkan.probeHDRSurface(surfaceHandle);
                        hdrNativeProbeDone = true;
                    }
                } catch(IllegalAccessException e) {
                    frameFieldsUnavailable = true;
                    System.err.println("[RTX] HDR probe field access failed: " + e.getClass().getSimpleName());
                }
            }


            dumpHDRSurfaceFieldsOnce(
                    surface
            );


            if (NativeVulkan.rtInjectedToMainTarget) {

                if (
                        blitCommandBuffer != null &&
                                textureView.texture()
                                        instanceof VulkanGpuTexture sourceTexture
                ) {
                    long sourceImage =
                            sourceTexture.vkImage();


                    if (
                            sourceImage != 0L &&
                                    sourceImage ==
                                            NativeVulkan.pendingGuiTargetImage
                    ) {
                        NativeVulkan.captureFGFinalAndBuildUIAlpha(
                                blitCommandBuffer.address(),
                                sourceImage,
                                textureView.getWidth(0),
                                textureView.getHeight(0)
                        );
                    }
                }


                return;
            }


            LongList images = (LongList) swapchainImagesField.get(surface);
            int index = currentImageIndexField.getInt(surface);

            if (images == null || index < 0 || index >= images.size()) {
                return;
            }

            int swapchainWidth = swapchainWidthField.getInt(surface);
            int swapchainHeight = swapchainHeightField.getInt(surface);

            long swapchainImage = images.getLong(index);

            long commandBuffer =
                    NativeVulkan.currentCommandBuffer;

            if (commandBuffer == 0 || swapchainImage == 0) {
                return;
            }

            NativeVulkan.recordFrameCommand(
                    commandBuffer,
                    swapchainImage,
                    swapchainWidth,
                    swapchainHeight,
                    false
            );
        } catch (Throwable e) {
            if (!frameFailureReported) {
                frameFailureReported = true;
                System.err.println("[RTX] Frame injection failed: " + e.getClass().getSimpleName());
            }
        } finally {

            NativeVulkan.resetGuiRtState();
        }
    }

    private static final int HDR10_PACK32_FORMAT = 64;
    private static final int HDR10_ST2084_COLOR_SPACE = 1000104008;

    @Inject(
            method = "pickSwapchainSurfaceFormat",
            at = @At("RETURN"),
            remap = false
    )
    private void hdr$probeSwapchainSurfaceFormat(
            VkSurfaceFormatKHR.Buffer formats,
            CallbackInfoReturnable<VkSurfaceFormatKHR> cir
    ) {
        hdrSurfaceFormatPickCount++;


        if(NativeVulkan.isLoaded()) {
            NativeVulkan.setHDREnabledNative(
                    RTXSettingsConfig.isHDREnabled()
            );
        }


        boolean hdr10Available =
                false;

        StringBuilder available =
                new StringBuilder();


        for(int i = 0; i < formats.remaining(); i++) {

            VkSurfaceFormatKHR candidate =
                    formats.get(
                            formats.position() + i
                    );


            if(candidate == null) {
                continue;
            }


            available
                    .append(" [")
                    .append(candidate.format())
                    .append("/")
                    .append(candidate.colorSpace())
                    .append("]");


            if(candidate.format() == HDR10_PACK32_FORMAT &&
                    candidate.colorSpace() == HDR10_ST2084_COLOR_SPACE) {

                hdr10Available =
                        true;
            }
        }


        if(NativeVulkan.isLoaded()) {
            NativeVulkan.setHDRSurfaceSupportedNative(
                    hdr10Available
            );
        }


        VkSurfaceFormatKHR selected =
                cir.getReturnValue();


        System.out.println(
                "[HDR-3.3 PICK]"
                        + " call="
                        + hdrSurfaceFormatPickCount
                        + " available="
                        + formats.remaining()
                        + " selectedFormat="
                        + (selected == null
                        ? "null"
                        : selected.format())
                        + " selectedColorSpace="
                        + (selected == null
                        ? "null"
                        : selected.colorSpace())
                        + " hdr10Available="
                        + hdr10Available
                        + " options="
                        + available
        );
    }

    @Inject(
            method = "<init>",
            at = @At("RETURN"),
            remap = false
    )
    private void fg$captureBackbufferFormat(
            VulkanDevice device,
            long windowHandle,
            CallbackInfo ci
    ) {
        NativeVulkan.setFGBackbufferFormat(
                this.swapchainImageFormat
        );
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/sdl/SDLVulkan;"
                                    + "SDL_Vulkan_CreateSurface("
                                    + "J"
                                    + "Lorg/lwjgl/vulkan/VkInstance;"
                                    + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;"
                                    + "Ljava/nio/LongBuffer;"
                                    + ")Z"
            ),
            remap = false
    )
    private boolean fg$createWindowSurface(
            long windowHandle,
            VkInstance instance,
            VkAllocationCallbacks allocator,
            LongBuffer surfaceOut
    ) {
        boolean prepared =
                NativeVulkan.prepareStreamlineVulkan(
                        instance.address(),
                        this.device
                                .vkDevice()
                                .getPhysicalDevice()
                                .address(),
                        this.device
                                .vkDevice()
                                .address()
                );


        if(!prepared) {

            System.err.println(
                    "[FG-3 JAVA]"
                            + " prepareStreamlineVulkan=0"
                            + " fallback=SDL"
            );

            return SDLVulkan.SDL_Vulkan_CreateSurface(
                    windowHandle,
                    instance,
                    allocator,
                    surfaceOut
            );
        }


        long hwnd =
                SDLProperties.SDL_GetPointerProperty(
                        SDLVideo.SDL_GetWindowProperties(windowHandle),
                        SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,
                        0L
                );


        if(hwnd == 0L) {

            System.err.println(
                    "[FG-3 JAVA]"
                            + " hwnd=0"
                            + " fallback=SDL"
            );

            return SDLVulkan.SDL_Vulkan_CreateSurface(
                    windowHandle,
                    instance,
                    allocator,
                    surfaceOut
            );
        }


        return NativeVulkan.slCreateWin32Surface(
                instance.address(),
                hwnd,
                MemoryUtil.memAddress(
                        surfaceOut
                )
        ) == 0;
    }


    @Redirect(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSwapchain;"
                                    + "vkCreateSwapchainKHR("
                                    + "Lorg/lwjgl/vulkan/VkDevice;"
                                    + "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"
                                    + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;"
                                    + "Ljava/nio/LongBuffer;"
                                    + ")I"
            ),
            remap = false
    )
    private int fg$createSwapchain(
            VkDevice device,
            VkSwapchainCreateInfoKHR createInfo,
            VkAllocationCallbacks allocator,
            LongBuffer swapchainOut
    ) {
        return NativeVulkan.slCreateSwapchain(
                device.address(),
                createInfo.address(),
                MemoryUtil.memAddress(
                        swapchainOut
                )
        );
    }


    @Redirect(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSwapchain;"
                                    + "vkGetSwapchainImagesKHR("
                                    + "Lorg/lwjgl/vulkan/VkDevice;"
                                    + "J"
                                    + "Ljava/nio/IntBuffer;"
                                    + "Ljava/nio/LongBuffer;"
                                    + ")I"
            ),
            remap = false
    )
    private int fg$getSwapchainImages(
            VkDevice device,
            long swapchain,
            IntBuffer count,
            LongBuffer images
    ) {
        return NativeVulkan.slGetSwapchainImages(
                device.address(),
                swapchain,

                MemoryUtil.memAddress(
                        count
                ),

                images != null
                        ? MemoryUtil.memAddress(
                        images
                )
                        : 0L
        );
    }


    @Redirect(
            method = "acquireNextTexture",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSwapchain;"
                                    + "vkAcquireNextImageKHR("
                                    + "Lorg/lwjgl/vulkan/VkDevice;"
                                    + "J"
                                    + "J"
                                    + "J"
                                    + "J"
                                    + "Ljava/nio/IntBuffer;"
                                    + ")I"
            ),
            remap = false
    )
    private int fg$acquireNextImage(
            VkDevice device,
            long swapchain,
            long timeout,
            long semaphore,
            long fence,
            IntBuffer imageIndex
    ) {
        return NativeVulkan.slAcquireNextImage(
                device.address(),
                swapchain,
                timeout,
                semaphore,
                fence,
                MemoryUtil.memAddress(
                        imageIndex
                )
        );
    }


    @Redirect(
            method = "destroySwapchain",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSwapchain;"
                                    + "vkDestroySwapchainKHR("
                                    + "Lorg/lwjgl/vulkan/VkDevice;"
                                    + "J"
                                    + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;"
                                    + ")V"
            ),
            remap = false
    )
    private void fg$destroySwapchain(
            VkDevice device,
            long swapchain,
            VkAllocationCallbacks allocator
    ) {
        NativeVulkan.slDestroySwapchain(
                device.address(),
                swapchain
        );
    }


    @Redirect(
            method = "close",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSurface;"
                                    + "vkDestroySurfaceKHR("
                                    + "Lorg/lwjgl/vulkan/VkInstance;"
                                    + "J"
                                    + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;"
                                    + ")V"
            ),
            remap = false
    )
    private void fg$destroySurface(
            VkInstance instance,
            long surface,
            VkAllocationCallbacks allocator
    ) {
        NativeVulkan.slDestroySurface(
                instance.address(),
                surface
        );
    }


    @Redirect(
            method = "present",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSwapchain;"
                                    + "vkQueuePresentKHR("
                                    + "Lorg/lwjgl/vulkan/VkQueue;"
                                    + "Lorg/lwjgl/vulkan/VkPresentInfoKHR;"
                                    + ")I"
            ),
            remap = false
    )
    private int fg$queuePresent(
            VkQueue queue,
            VkPresentInfoKHR presentInfo
    ) {
        NativeVulkan.markPresentStart();

        try {

            return NativeVulkan.slQueuePresent(
                    queue.address(),
                    presentInfo.address()
            );

        } finally {

            NativeVulkan.markPresentEnd();
        }
    }

}
