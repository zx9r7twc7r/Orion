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

// 2026-10-29 OPT-EXT：可选能力（全部按设备实际 advertised 门控，见
// rtx$injectOptionalCapabilities）。这些类来自 lwjgl-vulkan 3.4.1，已逐个用
// javap 核对过 sType$Default() 与字段常量名，不是照名字猜的。
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

// 2026-10-29 T3-9 阶段 1：CLAS / partitioned AS（只探测能力与上限，不替换主路径）
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

    // 2026-10-11 SHaRC：16 位存储是核心 1.1 特性，所以它有自己的结构体。
    // 用独立结构（VkPhysicalDevice16BitStorageFeatures）而不是 promoted 的
    // VkPhysicalDeviceVulkan11Features：Vulkan 规范禁止同一个 pNext 链里同时出现
    // 两者，而 renderpearl 的链里只有 VK10 / VK12 / VK13 三档（见 latest.log 的
    // [FG-2.6 JAVA VK12] / [FG-2.6 JAVA VK13] 两行），没有 VK11。
    @Unique
    private static final VulkanPNextStruct VK11_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDevice16BitStorageFeatures.class);

    // Core Vulkan 1.0 features live in the "features" member of
    // VkPhysicalDeviceFeatures2 rather than in its pNext chain, and renderpearl
    // models exactly that by wrapping the Features2 struct itself (its own
    // VulkanFeatureSets.VK10_FEATURES_STRUCT is built the same way).  A
    // VulkanFeature then addresses a field of the embedded
    // VkPhysicalDeviceFeatures by name.
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


        /*
         * ============================================================
         * Streamline / NVIDIA Extensions
         * ============================================================
         */

        deviceExtensions.add(
                "VK_KHR_push_descriptor"
        );

        deviceExtensions.add(
                "VK_NVX_binary_import"
        );

        deviceExtensions.add(
                "VK_NVX_image_view_handle"
        );
        /*
         * ============================================================
         * FG-2.8
         * DLSS-G runtime Device requirements
         *
         * Source:
         * slGetFeatureRequirements(kFeatureDLSS_G)
         * -> C++ snapshot
         * -> JNI bridge
         * ============================================================
         */

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

        /*
         * ============================================================
         * Vulkan Ray Tracing Extensions
         * ============================================================
         */

        deviceExtensions.add(
                "VK_KHR_acceleration_structure"
        );

        deviceExtensions.add(
                "VK_KHR_ray_tracing_pipeline"
        );

        /*
         * VK_KHR_ray_query is NOT implied by VK_KHR_ray_tracing_pipeline.
         *
         * It is an independent extension with its own feature struct
         * (VkPhysicalDeviceRayQueryFeaturesKHR), and the native backend needs it
         * for the sun shadow map (cpp/shaders/rt_sun_shadow.comp): a compute
         * shader cannot call traceRayEXT, so rayQueryEXT is the only way to
         * trace from there.  That pass is one 256x256 dispatch per frame and is
         * what gives the volumetric light and fog their terrain occlusion.
         */
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

        /*
         * ============================================================
         * OMM-1
         *
         * OMM is optional.  Do not request it on hardware/driver stacks
         * without VK_EXT_opacity_micromap: the native renderer keeps the
         * original Any-Hit path in that case.
         * ============================================================
         */
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

        /*
         * ============================================================
         * SER-2
         *
         * Enable the EXT device capability only when the selected device
         * advertises it.  The native renderer keeps water, glass, OMM and
         * primary rays on traceRayEXT; only the optional secondary-GI raygen
         * uses Hit Objects when its SPIR-V asset is selected.
         * ============================================================
         */
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


        /*
         * ============================================================
         * Buffer Device Address
         * ============================================================
         */

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


        /*
         * ============================================================
         * Acceleration Structure
         * ============================================================
         */

        vulkanFeatures.add(
                new VulkanFeature(
                        new VulkanPNextStruct(
                                VkPhysicalDeviceAccelerationStructureFeaturesKHR.class
                        ),
                        "accelerationStructure",
                        VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE
                )
        );


        /*
         * ============================================================
         * Ray Tracing Pipeline
         * ============================================================
         */

        vulkanFeatures.add(
                new VulkanFeature(
                        new VulkanPNextStruct(
                                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class
                        ),
                        "rayTracingPipeline",
                        VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE
                )
        );

        /*
         * ============================================================
         * Ray Query
         *
         * VK_KHR_ray_query is an independent extension, not a subset of
         * VK_KHR_ray_tracing_pipeline: it has its own feature struct and has to
         * be enabled separately.  The native backend needs it for the sun
         * shadow map (cpp/shaders/rt_sun_shadow.comp) -- a compute shader cannot
         * call traceRayEXT, so rayQueryEXT is the only way to trace from there.
         *
         * Only chained when the extension is actually present: an unsupported
         * feature struct in the pNext chain is a validation error.  The native
         * side degrades gracefully when it is missing (g_sunShadowAvailable
         * stays false, so volumetric light/fog keep cloud shadows only).
         * ============================================================
         */

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
        /*
         * ============================================================
         * FG-2.10
         * Native NVIDIA Optical Flow feature
         *
         * Native OF route was selected.
         * VK_NV_optical_flow extension alone is not sufficient:
         * VkPhysicalDeviceOpticalFlowFeaturesNV::opticalFlow must
         * also be enabled during vkCreateDevice().
         * ============================================================
         */

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


        /*
         * ============================================================
         * Descriptor Indexing (Bindless Textures / LabPBR)
         * ============================================================
         */
        /*
         * ============================================================
         * Descriptor Indexing
         *
         * FG requires descriptorIndexing itself.
         * Existing bindless RT path additionally requires
         * descriptorBindingPartiallyBound + runtimeDescriptorArray.
         * ============================================================
         */

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


        /*
         * ============================================================
         * NRD (NVIDIA Real-Time Denoisers) core-1.0 requirements
         *
         * NRD's precompiled SPIR-V declares the
         * "StorageImageReadWithoutFormat" / "StorageImageWriteWithoutFormat"
         * capabilities: it binds its internal pool and history textures as
         * UNTYPED storage images, so the pipeline cannot be created unless the
         * matching device features are enabled at device-creation time.
         *
         * Verified by disassembling the shipped SPIR-V
         * (cpp/deps/NRD/_Shaders/*.spirv) -- the capability set is exactly
         * {Shader, StorageImageReadWithoutFormat, StorageImageWriteWithoutFormat}
         * and does NOT include Float16, so no 16-bit shader support is needed.
         *
         * These are core Vulkan 1.0 features, so they are addressed through
         * VK10_FEATURES_STRUCT.  Both are free on desktop NVIDIA hardware.
         * ============================================================
         */

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


        /*
         * ============================================================
         * 2026-10-11 SHaRC (spatially hashed radiance cache) 设备特性
         *
         * SHaRC 的三块缓存按 buffer_reference（64 位设备地址）访问，哈希键是 uint64，
         * 缓存条目里的辐射度按 fp16 打包。于是带 sharc_enable.glsl 的 rt_test.rgen
         * 编译出来后，SPIR-V 会声明两个此前从未出现过的能力：
         *
         *     OpCapability Int64                     (hashEntries 的元素类型是 uint64)
         *     OpCapability StorageBuffer16BitAccess  (SharcPackedData.radianceData 是 float16_t4)
         *
         * 这两个能力不在设备上启用时，**管线创建会直接失败**（不是警告）。判据来自
         * 实机反汇编（2026-10-11，探针版 rt_test.rgen，vulkan1.2 风味）：
         *
         *     ImageQuery / Int64 / PhysicalStorageBufferAddresses / RayTracingKHR /
         *     StorageBuffer16BitAccess / StorageImageExtendedFormats
         *
         * 其中 PhysicalStorageBufferAddresses 已由 VK12 的 bufferDeviceAddress 覆盖；
         * Float16 **没有**出现 —— 探针只读 fp16 所在的结构体字段，不做 fp16 运算，
         * 所以 shaderFloat16 暂时不需要（真正接 update/resolve 时要重新看一次能力表）。
         *
         * shaderInt64 是核心 1.0 特性（走 VK10_FEATURES_STRUCT，与 NRD 那两条同一写法），
         * storageBuffer16BitAccess 是核心 1.1 特性。桌面 NVIDIA 全部支持。
         * ============================================================
         */

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

        /*
         * 2026-10-11 5b-1 追加两条（都是"先编出来看 OpCapability，再决定"的结果，
         * 不是猜的）。反汇编证据（vulkan1.2 风味）：
         *
         *   rt_sharc_resolve.comp.spv:
         *     Shader / **Float16** / Int64 / StorageBuffer16BitAccess /
         *     PhysicalStorageBufferAddresses
         *   ⇒ Float16 来自 SharcResolveEntry 对 fp16 打包值的**运算**（不只是存储），
         *     所以 shaderFloat16 必须启用。它属于 VK12 的 promoted 结构
         *     （VkPhysicalDeviceVulkan12Features.shaderFloat16），复用上面的
         *     VK12_FEATURES_STRUCT 即可，不会与独立结构冲突。
         *
         *   rt_test.rgen.spv（update 调用接进去之后）还会多出
         *     ShaderBufferInt64Atomics —— 哈希插入用 CAS 在 uint64 哈希键上，
         *     对应 VK12 的 shaderBufferInt64Atomics（同为 promoted 字段）。
         *     现在提前打开：next step 把 update 接上时不必再来一轮设备改动。
         */
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

        // 2026-10-29 OPT-EXT：可选能力。必须在下面那份"真正交给 vkCreateDevice 的
        // 清单"转储之前调用，否则日志与实际清单不一致（2026-10-14 那条诊断的初衷）。
        rtx$injectOptionalCapabilities(
                deviceExtensions,
                physicalDevice,
                vulkanFeatures
        );
        // 2026-10-14 诊断：这一段以前是**硬编码打印**（等于陈旧文档 —— 它列了 4 个
        // 扩展却没有 VK_KHR_ray_query，而代码里明明有条件 add，谁也说不清清单里到底
        // 有没有）。改成把**真正交给 vkCreateDevice 的那份清单**转储出来。
        //
        // 为什么值得查：`VK_KHR_ray_query` 没被启用时，compute 里的 rayQuery 会**静默
        // 落空**（编译不报错、校验层没开也不报）—— 那正是"洞里整片绿 / 怎么调都穿墙"
        // 的候选根因（近场太阳影图、远场图、地形高度场全是 compute 射线产的）。
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

    // ============================================================
// FG-2.9
// Streamline extra Vulkan queues
//
// Host queues remain unchanged.
//
// Runtime requirements:
//   graphics    +N
//   compute     +N
//   opticalFlow +N
//
// Native Optical Flow route selected.
// ============================================================

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

        // --------------------------------------------------------
        // Preserve Minecraft's original host queues.
        // --------------------------------------------------------

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

            // ----------------------------------------------------
            // Enumerate actual family capacities.
            // ----------------------------------------------------

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


            // ----------------------------------------------------
            // Streamline Graphics queues
            //
            // Must start immediately after host-created queues
            // in the selected graphics family.
            // ----------------------------------------------------

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


            // ----------------------------------------------------
            // Streamline Compute queues
            //
            // Read result AFTER graphics reservation so this is
            // also safe if graphics/compute share a family.
            // ----------------------------------------------------

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


            // ----------------------------------------------------
            // Native Optical Flow queue
            //
            // Select the first OF-capable family which is not
            // Minecraft's graphics / compute / transfer family.
            //
            // VK_QUEUE_OPTICAL_FLOW_BIT_NV = 0x00000100
            // ----------------------------------------------------

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


                    // Native OF must not use Minecraft's active
                    // graphics / compute / transfer family.
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


                    // Native OF family must remain dedicated.
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


            // ----------------------------------------------------
            // Validation logs
            // ----------------------------------------------------
// ============================================================
// FG-2.11
// Publish the exact queue plan to native Streamline state.
// ============================================================

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

    // ============================================================
    // 2026-10-29 OPT-EXT
    // 可选设备能力：按设备实际 advertised 门控启用
    //
    // 硬约束（来自 HANDOFF-2026-10-22 §7.4 的实测结论）：
    //   1. 每个扩展先 physicalDevice.hasDeviceExtension(...) 门控，否则
    //      vkCreateDevice 直接失败；
    //   2. 带特征字段的扩展必须同时 vulkanFeatures.add(...)，否则是
    //      "扩展启用但 feature=false"，管线创建时才报能力缺失；
    //   3. 这里**只启用能力，不改任何现有调用路径** ⇒ 画面/性能不应变化。
    //
    // 明确不在此列的项与原因：
    //   · VK_EXT_zero_initialize_device_memory：每次 vkAllocateMemory 变成写 0，
    //     几百 MB 的 section/BLAS 分配要多一遍显存写；它只掩盖"读未初始化显存"
    //     的 bug，先不开。
    //   · VK_EXT_device_memory_report：要 VkDeviceDeviceMemoryReportCreateInfoEXT
    //     回调（Java 侧没有 C 函数指针），只能 native 侧做。
    //   · VK_KHR_present_id/_wait、swapchain_maintenance1：swapchain 归 Minecraft。
    //   · VK_KHR_unified_image_layouts / internally_synchronized_queues：驱动 617
    //     属新实现，且不改代码等于没效果，留作单独实验项。
    // ============================================================

    // 2026-10-29：这里刻意**不放** `static final VulkanPNextStruct` 字段。
    // VulkanPNextStruct(Class) 的构造器靠反射校验 PNEXT/STYPE 偏移，一旦某个
    // 结构不满足就抛 IllegalArgumentException —— 发生在类初始化阶段会变成
    // ExceptionInInitializerError，绕开下面的 try/catch，客户端直接起不来。
    // 所以结构体在 rtx$addFeatureExtension 内部延迟构造，失败只跳过该项。

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
        // 2026-10-29 A/B 闸门（排查掉帧用，默认全开 = 与上线时行为一致）：
        //   ORION_EXT_OPTCAP=0   → 一次关掉下面全部 12 项（回到 24 扩展的旧设备）
        //   ORION_EXT_PAGEABLE=0 → 只关 pageable_device_local_memory
        // 两者都只在**设备创建时**读一次，改完必须重启客户端。
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

        // ① GPU 挂死可诊断：vkGetDeviceFaultInfoEXT + vkCmdSetCheckpointNV
        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_EXT_device_fault",
                VkPhysicalDeviceFaultFeaturesEXT.class,
                new String[]{"deviceFault"},
                new long[]{VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT}
        );

        // 只取 deviceFault，不取 deviceFaultVendorBinary：后者会让驱动把崩溃
        // 转储落盘（每次 DEVICE_LOST 一个 dump），占用不可控。
        rtx$addPlainExtension(
                deviceExtensions,
                physicalDevice,
                "VK_NV_device_diagnostic_checkpoints"
        );

        // ② 显存
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

        // ③ shader 内计时（补上"云段无子计时"的空白）
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

        // ④ wave 级 shader 能力（现成 shader 就能吃：histogram / ReSTIR / moments）
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

        // ⑤ buffer_reference 链（material_resolve 的 sectionVertexAddress 路径）
        rtx$addFeatureExtension(
                deviceExtensions,
                vulkanFeatures,
                physicalDevice,
                "VK_NV_raw_access_chains",
                VkPhysicalDeviceRawAccessChainsFeaturesNV.class,
                new String[]{"shaderRawAccessChains"},
                new long[]{VkPhysicalDeviceRawAccessChainsFeaturesNV.SHADERRAWACCESSCHAINS}
        );

        // ⑥ HDR 元数据（无特征结构体；调用点 vkSetHdrMetadataEXT 在 native 侧）
        rtx$addPlainExtension(
                deviceExtensions,
                physicalDevice,
                "VK_EXT_hdr_metadata"
        );

        // ⑦ 2026-10-29 T3-9 阶段 1：CLAS / partitioned AS。
        //
        // 刻意只做到"启用 + 查上限"，**不碰任何现有构建路径** —— 现有 BLAS 仍是
        // vkCmdBuildAccelerationStructuresKHR 直建，画面/性能不应有任何变化。
        //
        // 为什么必须先探测：SDK header 里 VK_BUILD_CLUSTER_ACCELERATION_STRUCTURE_MODE
        // 是 0 命中 ⇒ Vulkan 侧 CLAS 只能走
        // vkCmdBuildClusterAccelerationStructureIndirectNV（间接命令 + device buffer），
        // 且必须配 VK_NV_partitioned_acceleration_structure 当容器。阶段 2 的实现
        // 规模完全取决于 maxVerticesPerCluster / maxTrianglesPerCluster 这两个上限：
        // 上限越小，一个 16³ Section 被切出的 cluster 越多。
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
