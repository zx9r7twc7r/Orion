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
