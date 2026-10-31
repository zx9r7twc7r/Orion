package com.example.client.mixin;

import com.example.vulkan.NativeVulkan;
import com.example.client.DynamicLightCapture;
import com.example.client.EntityRtCapture;
import com.example.client.ParticleRtCapture;import com.example.client.compat.FarFieldGate;
import com.example.client.compat.VoxyLodManager;
import com.example.client.compat.dh.DhBridge;
import com.example.client.compat.dh.DhLodMeshSink;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.math.Axis;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.phys.Vec3;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Shadow
    private ViewArea viewArea;

    private static float rtx$partialTick;

    private static long rtx$memReportFrame;

    private static long rtx$nmtReportCount;

    private static void rtx$logJavaMemory() {
        if ((rtx$memReportFrame++ % 600L) != 0L) {
            return;
        }

        final Runtime runtime = Runtime.getRuntime();
        final long mb = 1024L * 1024L;

        System.out.println("[RT MEM JAVA]"
                + " heapUsedMB=" + ((runtime.totalMemory() - runtime.freeMemory()) / mb)
                + " heapTotalMB=" + (runtime.totalMemory() / mb)
                + " heapMaxMB=" + (runtime.maxMemory() / mb)
                + " | " + rtx$directMemoryStats()
                + " | dh=" + DhLodMeshSink.stats());

        if ((rtx$nmtReportCount++ % 1L) == 0L) {
            for (final String line : rtx$nativeMemorySummary().split("\\R")) {
                if (!line.isBlank()) {
                    System.out.println("[RT MEM NMT]" + line);
                }
            }
        }
    }

    private static String rtx$directMemoryStats() {
        long directCount = -1L;
        long directUsed = -1L;
        long mappedUsed = -1L;

        String failure = null;

        try {
            final javax.management.MBeanServer server =
                    java.lang.management.ManagementFactory.getPlatformMBeanServer();

            final java.util.Set<javax.management.ObjectName> names =
                    server.queryNames(
                            new javax.management.ObjectName("*:type=BufferPool,*"),
                            null);

            for (final javax.management.ObjectName objectName : names) {
                final String pool = String.valueOf(
                        server.getAttribute(objectName, "Name"));
                final long used = ((Number) server
                        .getAttribute(objectName, "MemoryUsed")).longValue();

                if ("direct".equalsIgnoreCase(pool)) {
                    directUsed = used;
                    directCount = ((Number) server
                            .getAttribute(objectName, "Count")).longValue();
                } else if ("mapped".equalsIgnoreCase(pool)) {
                    mappedUsed = used;
                }
            }
        } catch (final Throwable t) {
            failure = t.getClass().getSimpleName() + ":" + t.getMessage();
        }

        final long mb = 1024L * 1024L;

        return "direct=" + directCount + "/" + (directUsed / mb) + "MB"
                + " mapped=" + (mappedUsed / mb) + "MB"
                + " threads=" + java.lang.management.ManagementFactory
                        .getThreadMXBean().getThreadCount()
                + (failure == null ? "" : " poolErr=" + failure);
    }

    private static String rtx$nativeMemorySummary() {
        try {
            final javax.management.MBeanServer server =
                    java.lang.management.ManagementFactory.getPlatformMBeanServer();

            final Object report = server.invoke(
                    new javax.management.ObjectName(
                            "com.sun.management:type=DiagnosticCommand"),
                    "vmNativeMemory",
                    new Object[]{new String[]{"summary"}},
                    new String[]{String[].class.getName()});

            return report == null ? "" : String.valueOf(report);
        } catch (final Throwable t) {
            return "unavailable (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static int[] rtx$visibleSectionScratch = new int[0];

    private static void rtx$uploadVisibleSections(CameraRenderState cameraState) {
        if (cameraState != null && cameraState.isFrustumCaptured) {
            NativeVulkan.setVisibleSections(null, 0);
            return;
        }

        final LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        if (renderer == null) {
            NativeVulkan.setVisibleSections(null, 0);
            return;
        }

        final var sections = renderer.visibleSections();
        final int count = sections == null ? 0 : sections.size();
        if (count <= 0) {
            NativeVulkan.setVisibleSections(null, 0);
            return;
        }

        final int needed = count * 3;
        if (rtx$visibleSectionScratch.length < needed) {
            rtx$visibleSectionScratch = new int[Math.max(needed, 3 * 2048)];
        }

        final int[] out = rtx$visibleSectionScratch;
        int write = 0;
        for (int i = 0; i < count; i++) {
            final long node = sections.get(i).getSectionNode();
            out[write++] = SectionPos.x(node);
            out[write++] = SectionPos.y(node);
            out[write++] = SectionPos.z(node);
        }

        NativeVulkan.setVisibleSections(out, count);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void rtx$beginEntityRtFrame(
            com.mojang.blaze3d.resource.GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline,
            CameraRenderState cameraState,
            com.mojang.renderpearl.api.buffers.GpuBufferSlice terrainFog,
            org.joml.Vector4f fogColor,
            boolean shouldRenderSky,
            boolean renderDebug,
            CallbackInfo ci) {
        rtx$partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        rtx$logJavaMemory();
        VoxyLodManager.tick();
        long rtx$t0 = System.nanoTime();
        DhBridge.tick();
        long rtx$t1 = System.nanoTime();
        VoxyLodManager.perfAdd(VoxyLodManager.PERF_DH, rtx$t1 - rtx$t0);
        FarFieldGate.updateNearby(cameraState, viewArea);
        long rtx$t2 = System.nanoTime();
        VoxyLodManager.perfAdd(VoxyLodManager.PERF_FFG, rtx$t2 - rtx$t1);
        EntityRtCapture.endHandPhase();
        EntityRtCapture.beginEarlyFrame();
        ParticleRtCapture.beginFrame();
        DynamicLightCapture.submitFrame();
        rtx$uploadVisibleSections(cameraState);
        VoxyLodManager.perfAdd(VoxyLodManager.PERF_CAPTURE, System.nanoTime() - rtx$t2);
    }

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void rtx$submitLocalPlayerProxy(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector output,
            CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            EntityRtCapture.disarmLocalPlayerProxyFallback();
            return;
        }

        for (EntityRenderState state : levelRenderState.entityRenderStates) {
            if (state instanceof AvatarRenderState avatarState && avatarState.id == player.getId()) {
                EntityRtCapture.disarmLocalPlayerProxyFallback();
                return;
            }
        }

        EntityRenderState state = this.entityRenderDispatcher.extractEntity(player, rtx$partialTick);
        EntityRtCapture.markLocalPlayerProxyState(state);
        if (EntityRtCapture.isDiagnosticFrame()) {
            System.err.println("[RTX-PROXY] synthetic proxy submitted, state="
                    + (state == null ? "null" : state.getClass().getSimpleName()));
        }
        CameraRenderState cameraState = levelRenderState.cameraRenderState;
        var cameraPos = cameraState.pos;
        this.entityRenderDispatcher.submit(
                state, cameraState,
                state.x - cameraPos.x(), state.y - cameraPos.y(), state.z - cameraPos.z(),
                poseStack, output
        );
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderLevel(
            com.mojang.blaze3d.resource.GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline,
            CameraRenderState cameraState,
            com.mojang.renderpearl.api.buffers.GpuBufferSlice terrainFog,
            org.joml.Vector4f fogColor,
            boolean shouldRenderSky,
            boolean renderDebug,
            CallbackInfo ci) {

        DhBridge.markFrameEnded();

        if (NativeVulkan.handlesPending) {
            System.err.println("[HEART PROBE] LevelRenderer Triggering Init!");
            NativeVulkan.initDeviceHandles(
                    NativeVulkan.pendingInstanceHandle,
                    NativeVulkan.pendingPhysicalDeviceHandle,
                    NativeVulkan.pendingDeviceHandle,
                    NativeVulkan.pendingQueueHandle,
                    NativeVulkan.pendingQueueFamilyIndex
            );
            NativeVulkan.handlesPending = false;
        }

        ParticleRtCapture.endFrame();
    }
}
