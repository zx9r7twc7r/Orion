package com.example.client.mixin;

import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import com.example.vulkan.NativeVulkan;
import com.example.client.config.RTXSettingsConfig;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDevice16BitStorageFeatures;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import org.lwjgl.vulkan.NVOpticalFlow;
import org.lwjgl.vulkan.VkPhysicalDeviceOpticalFlowFeaturesNV;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.EXTRayTracingInvocationReorder;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;

import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryPriorityFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderClockFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderSubgroupRotateFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderQuadControlFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderMaximalReconvergenceFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderExpectAssumeFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderFloatControls2FeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRawAccessChainsFeaturesNV;

import org.lwjgl.vulkan.VkPhysicalDeviceClusterAccelerationStructureFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceClusterAccelerationStructurePropertiesNV;
import org.lwjgl.vulkan.VkPhysicalDevicePartitionedAccelerationStructureFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDevicePartitionedAccelerationStructurePropertiesNV;

import java.nio.IntBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {

    @Unique
    private static final String RTX_CREATE_DEVICE_METHOD =
            "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;"
                    + "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)"
                    + "Lorg/lwjgl/vulkan/VkDevice;";

    @Unique
    private static final ThreadLocal<VulkanPhysicalDevice> RTX_DEVICE = new ThreadLocal<>();

    @Unique
    private static final VulkanPNextStruct VK12_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceVulkan12Features.class);

    @Unique
    private static final VulkanPNextStruct VK11_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDevice16BitStorageFeatures.class);

    @Unique
    private static final VulkanPNextStruct VK10_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceFeatures2.class);

    private static final VulkanPNextStruct SER_FEATURES_STRUCT =
            new VulkanPNextStruct(
                    VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.class
            );

    private static final VulkanFeature SER_FEATURE =
            new VulkanFeature(
                    SER_FEATURES_STRUCT,
                    "rayTracingInvocationReorder",
                    VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT
                            .RAYTRACINGINVOCATIONREORDER
            );

    private static boolean supportsSerFeature(
            VulkanPhysicalDevice physicalDevice
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 available =
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            SER_FEATURES_STRUCT.findOrCreateStructInPNextChain(
                    available,
                    stack
            );
            VK12.vkGetPhysicalDeviceFeatures2(
                    physicalDevice.vkPhysicalDevice(),
                    available
            );
            return SER_FEATURE.get(available);
        }
    }



    @Unique
    private static void injectStreamlineExtensionsAndFeatures(
            Collection<String> deviceExtensions,
            VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> vulkanFeatures
    ) {

        System.out.println(
                "[Mixin] Intercepted VulkanBackend.createDevice."
        );


        


        deviceExtensions.add(
                "VK_KHR_push_descriptor"
        );

        deviceExtensions.add(
                "VK_NVX_binary_import"
        );

        deviceExtensions.add(
                "VK_NVX_image_view_handle"
        );
        


        if (NativeVulkan.isFGRequirementsReady()) {

            String[] fgDeviceExtensions =
                    NativeVulkan.getFGDeviceExtensions();


            int fgDeviceExtReady = 0;
            int fgDeviceExtMissing = 0;


            for (String extension : fgDeviceExtensions) {

                boolean supported =
                        physicalDevice.hasDeviceExtension(
                                extension
                        );


                System.err.println(
                        "[FG-2.8 DEVICE EXT]"
                                + " name="
                                + extension
                                + " supported="
                                + (supported ? 1 : 0)
                );


                if (supported) {

                    deviceExtensions.add(
                            extension
                    );

                    fgDeviceExtReady++;

                } else {

                    fgDeviceExtMissing++;
                }
            }


            System.err.println(
                    "[FG-2.8 DEVICE EXT SUMMARY]"
                            + " requested="
                            + fgDeviceExtensions.length
                            + " ready="
                            + fgDeviceExtReady
                            + " missing="
                            + fgDeviceExtMissing
            );

        } else {

            System.err.println(
                    "[FG-2.8 DEVICE EXT]"
                            + " requirementsReady=0"
            );
        }

        


        deviceExtensions.add(
                "VK_KHR_acceleration_structure"
        );

        deviceExtensions.add(
                "VK_KHR_ray_tracing_pipeline"
        );

        

        final boolean rayQuerySupported =
                physicalDevice.hasDeviceExtension(
                        "VK_KHR_ray_query"
                );

        System.err.println(
                "[SUNSHADOW DEVICE EXT] VK_KHR_ray_query supported="
                        + rayQuerySupported
        );

        if (rayQuerySupported) {
            deviceExtensions.add(
                    "VK_KHR_ray_query"
            );
        }

        deviceExtensions.add(
                "VK_KHR_deferred_host_operations"
        );

        

        final boolean ommSupported =
                physicalDevice.hasDeviceExtension(
                        EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME
                );

        System.err.println(
                "[OMM-1 DEVICE EXT]"
                        + " name="
                        + EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME
                        + " supported="
                        + (ommSupported ? 1 : 0)
        );

        if (ommSupported) {
            deviceExtensions.add(
                    EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME
            );

            vulkanFeatures.add(
                    new VulkanFeature(
                            new VulkanPNextStruct(
                                    VkPhysicalDeviceOpacityMicromapFeaturesEXT.class
                            ),
                            "micromap",
                            VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP
                    )
            );
        }

        

        final boolean serExtensionSupported =
                physicalDevice.hasDeviceExtension(
                        EXTRayTracingInvocationReorder
                                .VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME
                );
        final boolean serFeatureSupported =
                serExtensionSupported && supportsSerFeature(physicalDevice);
        final boolean serEnabled =
                RTXSettingsConfig.isSerEnabled() && serFeatureSupported;

        System.err.println(
                "[SER-2 DEVICE EXT]"
                        + " name="
                        + EXTRayTracingInvocationReorder
                        .VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME
                        + " supported="
                        + (serExtensionSupported ? 1 : 0)
        );

        if (serEnabled) {
            deviceExtensions.add(
                    EXTRayTracingInvocationReorder
                            .VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME
            );

            vulkanFeatures.add(
                    SER_FEATURE
            );
        }

        System.err.println(
                "[SER-2 DEVICE]"
                        + " extension="
                        + (serExtensionSupported ? 1 : 0)
                        + " feature="
                        + (serFeatureSupported ? 1 : 0)
                        + " requested="
                        + (RTXSettingsConfig.isSerEnabled() ? 1 : 0)
                        + " enabled="
                        + (serEnabled ? 1 : 0)
                        + " shaderMode=runtime-selected"
        );


        


        deviceExtensions.add(
                "VK_KHR_buffer_device_address"
        );


        vulkanFeatures.add(
                new VulkanFeature(
                        VK12_FEATURES_STRUCT,
                        "bufferDeviceAddress",
                        VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS
                )
        );


        


        vulkanFeatures.add(
                new VulkanFeature(
                        new VulkanPNextStruct(
                                VkPhysicalDeviceAccelerationStructureFeaturesKHR.class
                        ),
                        "accelerationStructure",
                        VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE
                )
        );


        


        vulkanFeatures.add(
                new VulkanFeature(
                        new VulkanPNextStruct(
                                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class
                        ),
                        "rayTracingPipeline",
                        VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE
                )
        );

        


        if (rayQuerySupported) {
            vulkanFeatures.add(
                    new VulkanFeature(
                            new VulkanPNextStruct(
                                    VkPhysicalDeviceRayQueryFeaturesKHR.class
                            ),
                            "rayQuery",
                            VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY
                    )
            );
        }
        


        if (
                NativeVulkan.isFGRequirementsReady() &&
                        NativeVulkan.getFGOpticalFlowQueuesRequired() > 0
        ) {

            vulkanFeatures.add(
                    new VulkanFeature(
                            new VulkanPNextStruct(
                                    VkPhysicalDeviceOpticalFlowFeaturesNV.class
                            ),
                            "opticalFlow",
                            VkPhysicalDeviceOpticalFlowFeaturesNV.OPTICALFLOW
                    )
            );


            System.err.println(
                    "[FG-2.10 NATIVE OF]"
                            + " extension=VK_NV_optical_flow"
                            + " feature=opticalFlow"
                            + " enabled=1"
            );
        }


        

        


        boolean fgRequiresDescriptorIndexing =
                false;


        if (NativeVulkan.isFGRequirementsReady()) {

            for (
                    String feature :
                    NativeVulkan.getFGVulkan12Features()
            ) {

                if ("descriptorIndexing".equals(feature)) {

                    fgRequiresDescriptorIndexing =
                            true;

                    break;
                }
            }
        }


        if (fgRequiresDescriptorIndexing) {

            vulkanFeatures.add(
                    new VulkanFeature(
                            VK12_FEATURES_STRUCT,
                            "descriptorIndexing",
                            VkPhysicalDeviceVulkan12Features.DESCRIPTORINDEXING
                    )
            );
        }


        vulkanFeatures.add(
                new VulkanFeature(
                        VK12_FEATURES_STRUCT,
                        "descriptorBindingPartiallyBound",
                        VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND
                )
        );


        vulkanFeatures.add(
                new VulkanFeature(
                        VK12_FEATURES_STRUCT,
                        "runtimeDescriptorArray",
                        VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY
                )
        );


        


        vulkanFeatures.add(
                new VulkanFeature(
                        VK10_FEATURES_STRUCT,
                        "shaderStorageImageReadWithoutFormat"
                )
        );

        vulkanFeatures.add(
                new VulkanFeature(
                        VK10_FEATURES_STRUCT,
                        "shaderStorageImageWriteWithoutFormat"
                )
        );


        System.err.println(
                "[NRD-1 DEVICE] requested core-1.0 features:"
                        + " shaderStorageImageReadWithoutFormat=1"
                        + " shaderStorageImageWriteWithoutFormat=1"
                        + " (required by NRD's untyped storage images)"
        );


        


        vulkanFeatures.add(
                new VulkanFeature(
                        VK11_FEATURES_STRUCT,
                        "storageBuffer16BitAccess"
                )
        );

        vulkanFeatures.add(
                new VulkanFeature(
                        VK10_FEATURES_STRUCT,
                        "shaderInt64"
                )
        );

        

        vulkanFeatures.add(
                new VulkanFeature(
                        VK12_FEATURES_STRUCT,
                        "shaderFloat16"
                )
        );

        vulkanFeatures.add(
                new VulkanFeature(
                        VK12_FEATURES_STRUCT,
                        "shaderBufferInt64Atomics"
                )
        );

        System.err.println(
                "[SHARC-DEVICE] requested features:"
                        + " storageBuffer16BitAccess=1 (VK11)"
                        + " shaderInt64=1 (VK10)"
                        + " shaderFloat16=1 (VK12)"
                        + " shaderBufferInt64Atomics=1 (VK12)"
                        + " | SHaRC cache = buffer_reference + uint64 hash keys"
                        + " + fp16 packed entries"
        );


        System.err.println(
                "[FG-2.8 VK12]"
                        + " descriptorIndexing="
                        + (fgRequiresDescriptorIndexing ? 1 : 0)
                        + " bufferDeviceAddress=1"
                        + " timelineSemaphore=BASE"
        );


        System.err.println(
                "[FG-2.8 VK13]"
                        + " synchronization2=BASE"
        );

        rtx$injectOptionalCapabilities(
                deviceExtensions,
                physicalDevice,
                vulkanFeatures
        );
        System.out.println(
                "[Mixin] Injected Vulkan extensions ("
                        + deviceExtensions.size()
                        + "):"
        );
        for (String extension : deviceExtensions) {
            System.out.println("  - " + extension);
        }
        System.out.println(
                "[Mixin] Injected Vulkan features ("
                        + vulkanFeatures.size()
                        + " struct entries)"
        );
    }
    @Inject(method = RTX_CREATE_DEVICE_METHOD, at = @At("HEAD"), remap = false)
    private static void rtx$capturePhysicalDevice(
            FeatureSet featureSet,
            VulkanPhysicalDevice physicalDevice,
            CallbackInfoReturnable<VkDevice> cir
    ) {
        RTX_DEVICE.set(physicalDevice);
    }

    @Redirect(
            method = RTX_CREATE_DEVICE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;features()Ljava/util/Set;"
            ),
            remap = false
    )
    private static Set<VulkanFeature> rtx$augmentFeatures(FeatureSet featureSet) {
        Set<VulkanFeature> features = new HashSet<>(featureSet.features());
        VulkanPhysicalDevice physicalDevice = RTX_DEVICE.get();
        if (physicalDevice != null) {
            injectStreamlineExtensionsAndFeatures(new HashSet<>(featureSet.extensions()), physicalDevice, features);
        }
        return features;
    }

    @Redirect(
            method = RTX_CREATE_DEVICE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;extensions()Ljava/util/Set;"
            ),
            remap = false
    )
    private static Set<String> rtx$augmentExtensions(FeatureSet featureSet) {
        Set<String> extensions = new HashSet<>(featureSet.extensions());
        VulkanPhysicalDevice physicalDevice = RTX_DEVICE.get();
        if (physicalDevice != null) {
            injectStreamlineExtensionsAndFeatures(extensions, physicalDevice, new HashSet<>(featureSet.features()));
        }
        return extensions;
    }


    @Redirect(
            method = RTX_CREATE_DEVICE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;"
                                    + "queueFamilyCreateInfoMap()"
                                    + "Lit/unimi/dsi/fastutil/ints/Int2IntMap;"
            ),
            remap = false
    )
    private static Int2IntMap redirectQueueFamilyCreateInfoMap(
            VulkanPhysicalDevice physicalDevice
    ) {


        Int2IntMap hostMap =
                physicalDevice.queueFamilyCreateInfoMap();


        Int2IntArrayMap result =
                new Int2IntArrayMap();


        result.putAll(
                hostMap
        );


        if (!NativeVulkan.isFGRequirementsReady()) {

            System.err.println(
                    "[FG-2.9 QUEUE]"
                            + " requirementsReady=0"
                            + " usingHostQueuesOnly=1"
            );

            return result;
        }


        final IntIntPair graphicsQueue =
                physicalDevice.graphicsQueueFamilyAndIndex();

        final IntIntPair computeQueue =
                physicalDevice.computeQueueFamilyAndIndex();

        final IntIntPair transferQueue =
                physicalDevice.transferQueueFamilyAndIndex();


        if (
                graphicsQueue == null ||
                        computeQueue == null
        ) {
            throw new IllegalStateException(
                    "[FG-2.9] Missing host graphics/compute queue"
            );
        }


        final int graphicsRequired =
                NativeVulkan.getFGGraphicsQueuesRequired();

        final int computeRequired =
                NativeVulkan.getFGComputeQueuesRequired();

        final int opticalFlowRequired =
                NativeVulkan.getFGOpticalFlowQueuesRequired();


        final int graphicsFamily =
                graphicsQueue.firstInt();

        final int computeFamily =
                computeQueue.firstInt();

        final int transferFamily =
                transferQueue != null
                        ? transferQueue.firstInt()
                        : -1;


        VkPhysicalDevice rawPhysicalDevice =
                physicalDevice.vkPhysicalDevice();


        try (MemoryStack stack = MemoryStack.stackPush()) {


            IntBuffer familyCount =
                    stack.mallocInt(1);


            VK10.vkGetPhysicalDeviceQueueFamilyProperties(
                    rawPhysicalDevice,
                    familyCount,
                    null
            );


            VkQueueFamilyProperties.Buffer properties =
                    VkQueueFamilyProperties.calloc(
                            familyCount.get(0),
                            stack
                    );


            VK10.vkGetPhysicalDeviceQueueFamilyProperties(
                    rawPhysicalDevice,
                    familyCount,
                    properties
            );



            final int graphicsStartIndex =
                    result.getOrDefault(
                            graphicsFamily,
                            0
                    );


            final int graphicsCapacity =
                    properties
                            .get(graphicsFamily)
                            .queueCount();


            if (
                    graphicsStartIndex +
                            graphicsRequired >
                            graphicsCapacity
            ) {
                throw new IllegalStateException(
                        "[FG-2.9] Insufficient graphics queues:"
                                + " family="
                                + graphicsFamily
                                + " start="
                                + graphicsStartIndex
                                + " required="
                                + graphicsRequired
                                + " capacity="
                                + graphicsCapacity
                );
            }


            result.put(
                    graphicsFamily,
                    graphicsStartIndex +
                            graphicsRequired
            );



            final int computeStartIndex =
                    result.getOrDefault(
                            computeFamily,
                            0
                    );


            final int computeCapacity =
                    properties
                            .get(computeFamily)
                            .queueCount();


            if (
                    computeStartIndex +
                            computeRequired >
                            computeCapacity
            ) {
                throw new IllegalStateException(
                        "[FG-2.9] Insufficient compute queues:"
                                + " family="
                                + computeFamily
                                + " start="
                                + computeStartIndex
                                + " required="
                                + computeRequired
                                + " capacity="
                                + computeCapacity
                );
            }


            result.put(
                    computeFamily,
                    computeStartIndex +
                            computeRequired
            );



            int opticalFlowFamily =
                    -1;

            int opticalFlowStartIndex =
                    -1;


            if (opticalFlowRequired > 0) {

                for (
                        int family = 0;
                        family < familyCount.get(0);
                        ++family
                ) {

                    VkQueueFamilyProperties familyProperties =
                            properties.get(
                                    family
                            );


                    final int flags =
                            familyProperties.queueFlags();


                    final boolean supportsOpticalFlow =
                            (flags & 0x00000100) != 0;


                    if (!supportsOpticalFlow) {
                        continue;
                    }


                    if (
                            family == graphicsFamily ||
                                    family == computeFamily ||
                                    family == transferFamily
                    ) {
                        continue;
                    }


                    final int alreadyCreated =
                            result.getOrDefault(
                                    family,
                                    0
                            );


                    if (alreadyCreated != 0) {
                        continue;
                    }


                    final int capacity =
                            familyProperties.queueCount();


                    if (opticalFlowRequired > capacity) {
                        continue;
                    }


                    opticalFlowFamily =
                            family;

                    opticalFlowStartIndex =
                            0;

                    break;
                }


                if (opticalFlowFamily == -1) {

                    throw new IllegalStateException(
                            "[FG-2.9]"
                                    + " No dedicated native"
                                    + " optical-flow queue family"
                    );
                }


                result.put(
                        opticalFlowFamily,
                        opticalFlowRequired
                );
            }



            final boolean useNativeOpticalFlow =
                    opticalFlowRequired > 0 &&
                            opticalFlowFamily >= 0;


            NativeVulkan.setStreamlineQueuePlan(
                    graphicsFamily,
                    graphicsStartIndex,

                    computeFamily,
                    computeStartIndex,

                    opticalFlowFamily,
                    opticalFlowStartIndex,

                    useNativeOpticalFlow
            );
            System.err.println(
                    "[FG-2.9 QUEUE PLAN]"
                            + " hostG="
                            + graphicsFamily
                            + ":"
                            + graphicsQueue.secondInt()
                            + " hostC="
                            + computeFamily
                            + ":"
                            + computeQueue.secondInt()
                            + " hostT="
                            + (
                            transferQueue != null
                                    ? transferFamily
                                    + ":"
                                    + transferQueue.secondInt()
                                    : "none"
                    )
                            + " slG="
                            + graphicsFamily
                            + ":"
                            + graphicsStartIndex
                            + "+"
                            + graphicsRequired
                            + " slC="
                            + computeFamily
                            + ":"
                            + computeStartIndex
                            + "+"
                            + computeRequired
                            + " slOF="
                            + opticalFlowFamily
                            + ":"
                            + opticalFlowStartIndex
                            + "+"
                            + opticalFlowRequired
            );


            for (
                    Int2IntMap.Entry entry :
                    result.int2IntEntrySet()
            ) {

                System.err.println(
                        "[FG-2.9 FAMILY CREATE]"
                                + " family="
                                + entry.getIntKey()
                                + " queueCount="
                                + entry.getIntValue()
                );
            }
        }


        return result;
    }



    /**
     * 无特征结构体的扩展：只要设备 advertised 就直接加。
     */
    @Unique
    private static void rtx$addPlainExtension(
            Collection<String> deviceExtensions,
            VulkanPhysicalDevice physicalDevice,
            String extension
    ) {
        if (!physicalDevice.hasDeviceExtension(extension)) {
            System.err.println("[OPT-EXT] " + extension + " supported=0 skipped");
            return;
        }

        deviceExtensions.add(extension);
        System.err.println("[OPT-EXT] " + extension + " enabled=1 (no feature struct)");
    }

    /**
     * 带特征结构体的扩展：先查 advertised，再用 vkGetPhysicalDeviceFeatures2 查
     * 字段实际值，只有 names[0]（主特征）为 true 才同时启用扩展与全部可用字段。
     * 任何反射/查询异常都只降级，绝不让设备创建失败。
     */
    @Unique
    private static void rtx$addFeatureExtension(
            Collection<String> deviceExtensions,
            Set<VulkanFeature> vulkanFeatures,
            VulkanPhysicalDevice physicalDevice,
            String extension,
            Class<? extends Struct<?>> structClass,
            String[] names,
            long[] offsets
    ) {
        if (!physicalDevice.hasDeviceExtension(extension)) {
            System.err.println("[OPT-EXT] " + extension + " supported=0 skipped");
            return;
        }

        final VulkanPNextStruct struct;

        try {
            struct = new VulkanPNextStruct(structClass);
        } catch (RuntimeException error) {
            System.err.println("[OPT-EXT] " + extension + " struct failed: " + error);
            return;
        }

        final boolean[] available = new boolean[names.length];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 probe =
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            struct.findOrCreateStructInPNextChain(probe, stack);
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), probe);

            for (int i = 0; i < names.length; i++) {
                available[i] = new VulkanFeature(struct, names[i], offsets[i]).get(probe);
            }
        } catch (RuntimeException error) {
            System.err.println("[OPT-EXT] " + extension + " query failed: " + error);
            return;
        }

        if (!available[0]) {
            System.err.println("[OPT-EXT] " + extension + " feature=0 skipped");
            return;
        }

        deviceExtensions.add(extension);

        StringBuilder enabled = new StringBuilder();

        for (int i = 0; i < names.length; i++) {
            if (!available[i]) {
                continue;
            }

            vulkanFeatures.add(new VulkanFeature(struct, names[i], offsets[i]));

            if (enabled.length() > 0) {
                enabled.append(',');
            }

            enabled.append(names[i]);
        }

        System.err.println("[OPT-EXT] " + extension + " enabled=1 features=" + enabled);
    }

    /**
     * 六组扩展的入口。调用点在 [FG-2.8 VK13] 之后、清单转储之前。
     * ① 挂死诊断 ② 显存 ③ shader 计时 ④ wave 级 shader 能力
     * ⑤ buffer_reference 优化 ⑥ HDR 元数据
     */
    @Unique
    private static void rtx$injectOptionalCapabilities(
            Collection<String> deviceExtensions,
            VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> vulkanFeatures
    ) {
        final String masterSwitch = System.getenv("ORION_EXT_OPTCAP");

        if (masterSwitch != null
                && (masterSwitch.startsWith("0") || masterSwitch.equalsIgnoreCase("false"))) {
            System.err.println(
                    "[OPT-EXT] all optional capabilities DISABLED by ORION_EXT_OPTCAP"
            );
            return;
        }

        final String pageableSwitch = System.getenv("ORION_EXT_PAGEABLE");
        final boolean pageableAllowed = !(pageableSwitch != null
                && (pageableSwitch.startsWith("0") || pageableSwitch.equalsIgnoreCase("false")));

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_EXT_device_fault",
                VkPhysicalDeviceFaultFeaturesEXT.class,
                new String[]{"deviceFault"},
                new long[]{VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT}
        );

        rtx$addPlainExtension(
                deviceExtensions,
                physicalDevice,
                "VK_NV_device_diagnostic_checkpoints"
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_EXT_memory_priority",
                VkPhysicalDeviceMemoryPriorityFeaturesEXT.class,
                new String[]{"memoryPriority"},
                new long[]{VkPhysicalDeviceMemoryPriorityFeaturesEXT.MEMORYPRIORITY}
        );

        if (pageableAllowed) {
            rtx$addFeatureExtension(
                    deviceExtensions,
                    vulkanFeatures,
                    physicalDevice,
                    "VK_EXT_pageable_device_local_memory",
                    VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT.class,
                    new String[]{"pageableDeviceLocalMemory"},
                    new long[]{VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT.PAGEABLEDEVICELOCALMEMORY}
            );
        } else {
            System.err.println(
                    "[OPT-EXT] VK_EXT_pageable_device_local_memory DISABLED by ORION_EXT_PAGEABLE"
            );
        }

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_KHR_shader_clock",
                VkPhysicalDeviceShaderClockFeaturesKHR.class,
                new String[]{"shaderSubgroupClock", "shaderDeviceClock"},
                new long[]{
                        VkPhysicalDeviceShaderClockFeaturesKHR.SHADERSUBGROUPCLOCK,
                        VkPhysicalDeviceShaderClockFeaturesKHR.SHADERDEVICECLOCK
                }
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_KHR_shader_subgroup_rotate",
                VkPhysicalDeviceShaderSubgroupRotateFeaturesKHR.class,
                new String[]{"shaderSubgroupRotate", "shaderSubgroupRotateClustered"},
                new long[]{
                        VkPhysicalDeviceShaderSubgroupRotateFeaturesKHR.SHADERSUBGROUPROTATE,
                        VkPhysicalDeviceShaderSubgroupRotateFeaturesKHR.SHADERSUBGROUPROTATECLUSTERED
                }
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_KHR_shader_quad_control",
                VkPhysicalDeviceShaderQuadControlFeaturesKHR.class,
                new String[]{"shaderQuadControl"},
                new long[]{VkPhysicalDeviceShaderQuadControlFeaturesKHR.SHADERQUADCONTROL}
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_KHR_shader_maximal_reconvergence",
                VkPhysicalDeviceShaderMaximalReconvergenceFeaturesKHR.class,
                new String[]{"shaderMaximalReconvergence"},
                new long[]{VkPhysicalDeviceShaderMaximalReconvergenceFeaturesKHR.SHADERMAXIMALRECONVERGENCE}
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_KHR_shader_expect_assume",
                VkPhysicalDeviceShaderExpectAssumeFeaturesKHR.class,
                new String[]{"shaderExpectAssume"},
                new long[]{VkPhysicalDeviceShaderExpectAssumeFeaturesKHR.SHADEREXPECTASSUME}
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_KHR_shader_float_controls2",
                VkPhysicalDeviceShaderFloatControls2FeaturesKHR.class,
                new String[]{"shaderFloatControls2"},
                new long[]{VkPhysicalDeviceShaderFloatControls2FeaturesKHR.SHADERFLOATCONTROLS2}
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_NV_raw_access_chains",
                VkPhysicalDeviceRawAccessChainsFeaturesNV.class,
                new String[]{"shaderRawAccessChains"},
                new long[]{VkPhysicalDeviceRawAccessChainsFeaturesNV.SHADERRAWACCESSCHAINS}
        );

        rtx$addPlainExtension(
                deviceExtensions,
                physicalDevice,
                "VK_EXT_hdr_metadata"
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_NV_cluster_acceleration_structure",
                VkPhysicalDeviceClusterAccelerationStructureFeaturesNV.class,
                new String[]{"clusterAccelerationStructure"},
                new long[]{
                        VkPhysicalDeviceClusterAccelerationStructureFeaturesNV
                                .CLUSTERACCELERATIONSTRUCTURE
                }
        );

        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_NV_partitioned_acceleration_structure",
                VkPhysicalDevicePartitionedAccelerationStructureFeaturesNV.class,
                new String[]{"partitionedAccelerationStructure"},
                new long[]{
                        VkPhysicalDevicePartitionedAccelerationStructureFeaturesNV
                                .PARTITIONEDACCELERATIONSTRUCTURE
                }
        );

        rtx$logClusterAsLimits(physicalDevice);
    }

    /**
     * 2026-10-29 T3-9：打印 CLAS / partitioned AS 的硬上限。
     *
     * 只读、异常只降级。这些数字决定阶段 2 的 cluster 切分方案：
     *   · maxVerticesPerCluster / maxTrianglesPerCluster —— 每个 cluster 的容量
     *   · maxClusterGeometryIndex                       —— cluster 内几何索引上限
     *   · 各种 *ByteAlignment                            —— 缓冲/模板/scratch 的对齐
     *   · maxPartitionCount                              —— partitioned AS 的分区数上限
     */
    @Unique
    private static void rtx$logClusterAsLimits(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceClusterAccelerationStructurePropertiesNV cluster =
                    VkPhysicalDeviceClusterAccelerationStructurePropertiesNV
                            .calloc(stack)
                            .sType$Default();

            VkPhysicalDevicePartitionedAccelerationStructurePropertiesNV partitioned =
                    VkPhysicalDevicePartitionedAccelerationStructurePropertiesNV
                            .calloc(stack)
                            .sType$Default();

            cluster.pNext(partitioned.address());

            org.lwjgl.vulkan.VkPhysicalDeviceProperties2 properties2 =
                    org.lwjgl.vulkan.VkPhysicalDeviceProperties2
                            .calloc(stack)
                            .sType$Default();

            properties2.pNext(cluster.address());

            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(
                    physicalDevice.vkPhysicalDevice(),
                    properties2
            );

            System.err.println(
                    "[CLAS-LIMITS]"
                            + " maxVerticesPerCluster=" + cluster.maxVerticesPerCluster()
                            + " maxTrianglesPerCluster=" + cluster.maxTrianglesPerCluster()
                            + " maxClusterGeometryIndex=" + cluster.maxClusterGeometryIndex()
                            + " clusterByteAlignment=" + cluster.clusterByteAlignment()
                            + " templateByteAlignment=" + cluster.clusterTemplateByteAlignment()
                            + " scratchByteAlignment=" + cluster.clusterScratchByteAlignment()
                            + " bottomLevelByteAlignment=" + cluster.clusterBottomLevelByteAlignment()
                            + " templateBoundsByteAlignment="
                            + cluster.clusterTemplateBoundsByteAlignment()
                            + " maxPartitionCount=" + partitioned.maxPartitionCount()
            );
        } catch (RuntimeException error) {
            System.err.println("[CLAS-LIMITS] query failed: " + error);
        }
    }
}
