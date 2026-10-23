package com.example.client.mixin;

import com.example.client.config.RTXSettingsConfig;
import com.example.vulkan.NativeVulkan;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanInstance.class, remap = false)
public abstract class VulkanInstanceMixin {

    // =====================================================
    // 2026-10-15 诊断闸门（默认开；=0 / false 关闭）
    //
    // 现场：进程提交量有一条与玩法无关的固定速率增长（约 30MB/s，标题界面就有、
    // 把帧率限到 30fps 也一点不变、JVM 侧实测干净：Java 堆 committed 恒 536MB、
    // NMT total 只 +0.2MB/s）。本闸门把"Streamline + NGX"这一块在 Vulkan 设备
    // 创建之前就摘掉（不调 initStreamlineEarly ⇒ slInit 不跑 ⇒ sl.*/nvngx_* 根本
    // 不会加载），用于判别那 30MB/s 属不属于这条链。
    //
    // **只用于诊断**：关掉之后 FG / DLSS-RR 整条不可用（画面与性能都会退化）。
    // 写法照 DhFlags.flag()：先读 -D 属性，再读大写环境变量；"0" 与 "false" 都算关。
    // =====================================================
    private static final String EARLY_INIT_PROPERTY =
            "orion.streamline.earlyInit";

    private static boolean streamlineEarlyInitEnabled() {
        String raw =
                System.getProperty(EARLY_INIT_PROPERTY);

        if (raw == null) {
            raw = System.getenv(
                    EARLY_INIT_PROPERTY
                            .toUpperCase(java.util.Locale.ROOT)
                            .replace('.', '_')
            );
        }

        if (raw == null) {
            return true;
        }

        final String value = raw.trim();

        return !("0".equals(value) || "false".equalsIgnoreCase(value));
    }

    @Shadow
    @Final
    private Set<String> enabledExtensions;
    @Inject(
            method = "<init>(IZZ)V",
            at = @At("HEAD")
    )
    private static void initStreamlineBeforeVulkan(
            int debugVerbosity,
            boolean wantsDebugLabels,
            boolean validation,
            CallbackInfo ci
    ) {
        if (!streamlineEarlyInitEnabled()) {
            System.err.println(
                    "[Mixin] Streamline early init DISABLED by "
                            + EARLY_INIT_PROPERTY
                            + " -- sl.interposer/ngx stay unloaded (diagnostic mode)."
            );

            return;
        }

        if (NativeVulkan.isLoaded()) {
            System.out.println(
                    "[Mixin] Calling Streamline early init before VulkanInstance body."
            );

            // 2026-10-22（D3）：必须在 initStreamlineEarly 之前决定用哪套 DLL。
            // 打开时 native 会从 <natives>\sl_debug_dev\ 加载 development 构建
            // （带 sl.imgui）；目录缺失会自动回退生产构建并打日志。
            NativeVulkan.setStreamlineDevPluginsEnabled(
                    RTXSettingsConfig.isSLDevPluginsEnabled()
            );

            NativeVulkan.initStreamlineEarly();
            System.err.println(
                    "[FG-2.6 JAVA BRIDGE]"
                            + " ready="
                            + NativeVulkan.isFGRequirementsReady()
                            + " instanceExt="
                            + NativeVulkan.getFGInstanceExtensions().length
                            + " deviceExt="
                            + NativeVulkan.getFGDeviceExtensions().length
                            + " vk12="
                            + NativeVulkan.getFGVulkan12Features().length
                            + " vk13="
                            + NativeVulkan.getFGVulkan13Features().length
                            + " graphics="
                            + NativeVulkan.getFGGraphicsQueuesRequired()
                            + " compute="
                            + NativeVulkan.getFGComputeQueuesRequired()
                            + " opticalFlow="
                            + NativeVulkan.getFGOpticalFlowQueuesRequired()
            );


            System.err.println(
                    "[FG-2.6 JAVA INSTANCE EXT] "
                            + java.util.Arrays.toString(
                            NativeVulkan.getFGInstanceExtensions()
                    )
            );


            System.err.println(
                    "[FG-2.6 JAVA DEVICE EXT] "
                            + java.util.Arrays.toString(
                            NativeVulkan.getFGDeviceExtensions()
                    )
            );


            System.err.println(
                    "[FG-2.6 JAVA VK12] "
                            + java.util.Arrays.toString(
                            NativeVulkan.getFGVulkan12Features()
                    )
            );


            System.err.println(
                    "[FG-2.6 JAVA VK13] "
                            + java.util.Arrays.toString(
                            NativeVulkan.getFGVulkan13Features()
                    )
            );
        } else {
            System.err.println(
                    "[Mixin] NativeVulkan not loaded, cannot initialize Streamline early."
            );
        }
    }

    /**
     * 在 VulkanDebug.create 被调用前拦截，此时基础扩展已获取，且尚未生成指针缓冲区。
     * 向 enabledExtensions 集合强制注入 Streamline 必需的实例扩展。
     */
    @Inject(
            method = "<init>(IZZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;",
                    shift = At.Shift.BEFORE
            )
    )
    private void onInstanceCreatePre(int debugVerbosity, boolean wantsDebugLabels, boolean validation, CallbackInfo ci) {
        if (this.enabledExtensions != null) {
            this.enabledExtensions.add("VK_KHR_external_semaphore_capabilities");
            this.enabledExtensions.add("VK_KHR_get_physical_device_properties2");
            this.enabledExtensions.add("VK_KHR_external_memory_capabilities");
            System.out.println("[Mixin] Injected Streamline required Vulkan instance extensions successfully.");
        }
    }
}
