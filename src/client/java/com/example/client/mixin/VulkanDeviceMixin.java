package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;

@Mixin(value = VulkanDevice.class, remap = false)
public abstract class VulkanDeviceMixin {

    @Shadow @Final private VkDevice vkDevice;

    @Inject(
            method = "<init>(Lcom/mojang/renderpearl/backend/vulkan/VulkanInstance;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lorg/lwjgl/vulkan/VkDevice;JLcom/mojang/renderpearl/backend/vulkan/checkpoints/CheckpointExtension;)V",
            at = @At("RETURN")
    )
    private void onDeviceInit(VulkanInstance i, VulkanPhysicalDevice pd, FeatureSet enabledFeatures, VkDevice d, long vma, CheckpointExtension ce, CallbackInfo ci) {
        try {

            final Int2IntMap hostQueueMap =
                    pd.queueFamilyCreateInfoMap();


            final IntIntPair graphicsQueue =
                    pd.graphicsQueueFamilyAndIndex();

            final IntIntPair computeQueue =
                    pd.computeQueueFamilyAndIndex();

            final IntIntPair transferQueue =
                    pd.transferQueueFamilyAndIndex();


            System.err.println(
                    "========== [FG-2.3 QUEUE PROBE BEGIN] =========="
            );


            System.err.println(
                    "[FG-2.3 HOST SELECT]"
                            + " graphics="
                            + (
                            graphicsQueue != null
                                    ? graphicsQueue.firstInt()
                                    + ":"
                                    + graphicsQueue.secondInt()
                                    : "none"
                    )
                            + " compute="
                            + (
                            computeQueue != null
                                    ? computeQueue.firstInt()
                                    + ":"
                                    + computeQueue.secondInt()
                                    : "none"
                    )
                            + " transfer="
                            + (
                            transferQueue != null
                                    ? transferQueue.firstInt()
                                    + ":"
                                    + transferQueue.secondInt()
                                    : "none"
                    )
            );


            VkPhysicalDevice probePhysicalDevice =
                    this.vkDevice.getPhysicalDevice();


            try (MemoryStack queueStack = MemoryStack.stackPush()) {

                IntBuffer queueCount =
                        queueStack.mallocInt(1);


                VK10.vkGetPhysicalDeviceQueueFamilyProperties(
                        probePhysicalDevice,
                        queueCount,
                        null
                );


                VkQueueFamilyProperties.Buffer queueProps =
                        VkQueueFamilyProperties.calloc(
                                queueCount.get(0),
                                queueStack
                        );


                VK10.vkGetPhysicalDeviceQueueFamilyProperties(
                        probePhysicalDevice,
                        queueCount,
                        queueProps
                );


                for (
                        int family = 0;
                        family < queueCount.get(0);
                        ++family
                ) {
                    VkQueueFamilyProperties props =
                            queueProps.get(family);


                    final int flags =
                            props.queueFlags();


                    final int capacity =
                            props.queueCount();


                    final int hostCreated =
                            hostQueueMap.getOrDefault(
                                    family,
                                    0
                            );


                    final int remaining =
                            Math.max(
                                    0,
                                    capacity - hostCreated
                            );


                    final boolean graphics =
                            (flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0;


                    final boolean compute =
                            (flags & VK10.VK_QUEUE_COMPUTE_BIT) != 0;


                    final boolean transfer =
                            (flags & VK10.VK_QUEUE_TRANSFER_BIT) != 0;


                    final boolean opticalFlow =
                            (flags & 0x00000100) != 0;


                    System.err.println(
                            "[FG-2.3 FAMILY " + family + "]"
                                    + " flags=0x"
                                    + Integer.toHexString(flags).toUpperCase()
                                    + " capacity="
                                    + capacity
                                    + " hostCreated="
                                    + hostCreated
                                    + " remaining="
                                    + remaining
                                    + " G="
                                    + (graphics ? 1 : 0)
                                    + " C="
                                    + (compute ? 1 : 0)
                                    + " T="
                                    + (transfer ? 1 : 0)
                                    + " OF="
                                    + (opticalFlow ? 1 : 0)
                    );
                }
            }


            System.err.println(
                    "========== [FG-2.3 QUEUE PROBE END] =========="
            );
            VkPhysicalDevice rawPhysDevice = this.vkDevice.getPhysicalDevice();

            long deviceHandle = this.vkDevice.address();
            long physicalDeviceHandle = rawPhysDevice.address();
            long instanceHandle = rawPhysDevice.getInstance().address();

            int queueFamilyIndex = -1;
            long queueHandle = 0L;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer pCount = stack.mallocInt(1);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(rawPhysDevice, pCount, null);
                VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.calloc(pCount.get(0), stack);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(rawPhysDevice, pCount, props);

                for (int j = 0; j < pCount.get(0); j++) {
                    if ((props.get(j).queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) {
                        queueFamilyIndex = j;
                        break;
                    }
                }
            }

            if (queueFamilyIndex != -1) {
                PointerBuffer pQueue = MemoryUtil.memAllocPointer(1);
                VK10.vkGetDeviceQueue(this.vkDevice, queueFamilyIndex, 0, pQueue);
                queueHandle = pQueue.get(0);
                MemoryUtil.memFree(pQueue);
            }

            NativeVulkan.pendingInstanceHandle = instanceHandle;
            NativeVulkan.pendingPhysicalDeviceHandle = physicalDeviceHandle;
            NativeVulkan.pendingDeviceHandle = deviceHandle;
            NativeVulkan.pendingQueueHandle = queueHandle;
            NativeVulkan.pendingQueueFamilyIndex = queueFamilyIndex;
            NativeVulkan.handlesPending = true;

            System.out.println("[HEART PROBE] SAFELY EXTRACTED VULKAN HANDLES!");

        } catch (Throwable t) {
            System.err.println("[HEART PROBE FATAL] Error extracting Vulkan handles:");
            t.printStackTrace();
        }
    }
}
