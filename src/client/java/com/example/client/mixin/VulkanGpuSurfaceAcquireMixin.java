package com.example.client.mixin;

import com.example.client.FrameBudget;
import com.example.vulkan.NativeVulkan;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import it.unimi.dsi.fastutil.longs.LongList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = VulkanGpuSurface.class, remap = false)
public abstract class VulkanGpuSurfaceAcquireMixin {

    private static volatile boolean surfaceFieldsResolved;
    private static volatile boolean surfaceFieldsUnavailable;
    private static Field swapchainImagesField;
    private static Field currentImageIndexField;

    private static boolean resolveSurfaceFields() {
        if (surfaceFieldsResolved) {
            return true;
        }
        if (surfaceFieldsUnavailable) {
            return false;
        }

        synchronized (VulkanGpuSurfaceAcquireMixin.class) {
            if (surfaceFieldsResolved) {
                return true;
            }
            if (surfaceFieldsUnavailable) {
                return false;
            }

            try {
                swapchainImagesField = VulkanGpuSurface.class.getDeclaredField("swapchainImages");
                currentImageIndexField = VulkanGpuSurface.class.getDeclaredField("currentImageIndex");
                swapchainImagesField.setAccessible(true);
                currentImageIndexField.setAccessible(true);
                surfaceFieldsResolved = true;
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                surfaceFieldsUnavailable = true;
                System.err.println("[RTX] Unable to cache swapchain fields: " + e.getClass().getSimpleName());
                return false;
            }
        }
    }

    /**
     * 2026-10-14 帧预算第三段：交换链 acquire 的起点。纯读时钟。
     * 为什么量它：`[FRAME SPLIT]` 显示 renderFrame 里、render() 之外还有稳定 7.5~9ms，
     * 而 GpuSurface 的 acquireNextTexture 在**队列满时是阻塞的**（帧生成打开时最容易排队）
     * ⇒ 它是这一段的头号嫌疑。
     */
    @Inject(method = "acquireNextTexture", at = @At("HEAD"), remap = false)
    private void beforeAcquireTiming(CallbackInfo ci) {
        FrameBudget.acquireHead();
    }

    @Inject(method = "acquireNextTexture", at = @At("RETURN"), remap = false)
    private void afterAcquire(CallbackInfo ci) {
        FrameBudget.acquireTail();

        if (NativeVulkan.handlesPending) {
            NativeVulkan.initDeviceHandles(
                    NativeVulkan.pendingInstanceHandle,
                    NativeVulkan.pendingPhysicalDeviceHandle,
                    NativeVulkan.pendingDeviceHandle,
                    NativeVulkan.pendingQueueHandle,
                    NativeVulkan.pendingQueueFamilyIndex
            );
            NativeVulkan.handlesPending = false;
        }

        if (!resolveSurfaceFields()) {
            return;
        }

        try {
            LongList images = (LongList) swapchainImagesField.get(this);
            int index = currentImageIndexField.getInt(this);
            if (images != null && index >= 0 && index < images.size()) {
                NativeVulkan.currentSwapchainImage = images.getLong(index);
            }
        } catch (IllegalAccessException e) {
            surfaceFieldsUnavailable = true;
            System.err.println("[RTX] Unable to read cached swapchain fields: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 2026-10-14 帧预算第三段：present 的耗时。纯读时钟。
     * 一帧里可能被调用多次（帧生成会为每个生成帧 present 一次）⇒ FrameBudget 按帧累加。
     */
    @Inject(method = "present", at = @At("HEAD"), remap = false)
    private void beforePresentTiming(CallbackInfo ci) {
        FrameBudget.presentHead();
    }

    @Inject(method = "present", at = @At("RETURN"), remap = false)
    private void afterPresentTiming(CallbackInfo ci) {
        FrameBudget.presentTail();
    }
}
