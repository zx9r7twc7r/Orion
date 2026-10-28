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
    // Vanilla precipitation is cancelled in WeatherEffectRendererMixin.
    // LevelRenderer no longer owns a renderSnowAndRain helper as of 26.3, so
    // the legacy inject that used to live here silently never applied.
    @Shadow @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Shadow
    private ViewArea viewArea;

    private static float rtx$partialTick;

    // 2026-10-09：Java 侧内存记账的帧计数（见 rtx$logJavaMemory 的说明）。
    private static long rtx$memReportFrame;

    // 2026-10-11：NMT 摘要的打印计数（每 6 次 [RT MEM JAVA] ≈60s 一份，
    // 见 rtx$nativeMemorySummary 的说明）。
    private static long rtx$nmtReportCount;

    // =====================================================
    // 2026-10-09：Java 侧内存记账（与原生 [RT MEM] 同一诊断）。
    //
    // 用户报"玩久了会直接占满内存"。原生那一行只能看到**进程工作集**与原生各结构；
    // 若工作集在涨而原生各结构平稳，差额就在 Java 堆或图形驱动里 —— 这一行就是把
    // Java 堆那一半补齐。每 600 帧（≈10s）一行，与原生 [RT MEM] 同频，
    // 落到 latest.log（带时间戳），与 rt_debug.log 按时间对齐即可比对。
    //
    // 只看两件事：
    //   · heapUsedMB 是否单调逼近 heapMaxMB ⇒ 堆在缓慢吃满（泄漏，或某个缓存无上限）；
    //   · dh= 里的"暂存/NNMB"⇒ DH 远场顶点暂存区（Java 侧最大的一块固定占用，
    //     上限 512MB，见 DhLodMeshSink.MAX_STORED_BYTES），它**有界**，是拿来当
    //     "基线占用"参照的 —— 若它长期贴合上限，说明内存高的一大部分是设计如此。
    // =====================================================
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

        // 2026-10-11：每次（≈25s，与原生 [RT MEM] 同频）都附一份 JVM 原生内存摘要。
        // 原先每 6 次才打一份，实测那一局只落了 1 份样本 ⇒ 算不出斜率，等于白测。
        // 现在每次采样都能与 [RT MEM] 的 rssMB/privateMB 直接配对，斜率一眼可比。
        if ((rtx$nmtReportCount++ % 1L) == 0L) {
            for (final String line : rtx$nativeMemorySummary().split("\\R")) {
                if (!line.isBlank()) {
                    System.out.println("[RT MEM NMT]" + line);
                }
            }
        }
    }

    // =====================================================
    // 2026-10-11：JVM **直接内存**记账（ByteBuffer.allocateDirect / FileChannel.map）。
    //
    // 为什么要补这一项。2026-10-11 15:54:49→15:55:40 那一局，进程私有提交以
    // +42MB/s 单调上涨，而当时**所有已有口径都是平的**：
    //   · Java 堆：heapUsedMB 455/612/527/660，heapTotalMB 1116→1084（甚至缩了）；
    //   · 原生 [RT MEM]：vkLiveMB 1921.9→1927.8、vkLiveN 111→111；
    //   · slab：slabAllocMB 1152.0 冻结、slabFreeMB 0.0；
    //   · 几何记账：mesh/raw/omm 合计 ~250MB 平；vramUsedMB 也平（没进显存）。
    // 直接内存正好是这种形状：它**不计入 Java 堆**（所以 heapUsed 看不到它），只在
    // Cleaner 被 GC 触发时才归还（所以堆平而进程提交线性涨），而 JNI 传大数据恰恰
    // 常用它。
    //
    // 判据：
    //   · direct= 的 MB 单调上涨 ⇒ 漏在 Java 侧。去找那个每帧/每批 allocateDirect
    //     的调用点改成复用固定缓冲；也可临时加 -XX:MaxDirectMemorySize=256m 兜住
    //     （会立刻变成 OOM 或频繁 GC，从而反证）。
    //   · direct=/mapped= 平稳而进程仍涨 ⇒ 不在 JVM 堆也不在 JVM 直接内存，转去查
    //     第三方原生模块：nrd.dll / nvngx_dlss*.dll 是在**自己模块内**分配
    //     VkDeviceMemory 与主机内存的，本模组的分配总账（rtxTrackedAllocateMemory）
    //     只覆盖本 DLL，看不见它们。
    //
    // 用反射而不是直接引用 java.nio.BufferPoolMXBean：它在 JDK 9 才从
    // java.lang.management 迁到 java.nio，反射可以保证换 JDK 也不会编译不过。
    // =====================================================
    private static String rtx$directMemoryStats() {
        long directCount = -1L;
        long directUsed = -1L;
        long mappedUsed = -1L;

        String failure = null;

        try {
            // 走平台 MBeanServer 而不是 ManagementFactory.getPlatformMXBeans(Class)：
            // 2026-10-11 16:01 那一局后者在本 JDK 上没返回池子，而且异常被我当时的
            // 静默 catch 吞掉，只留下 direct=-1/0MB —— 一次没有信息量的测量。
            // 这里改成按 ObjectName 查询（域用通配符，兼收 java.nio / 旧 JDK 的域名），
            // 并把失败原因打出来，避免再出现"测了但没说为什么"。
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

    // =====================================================
    // 2026-10-11：JVM 原生内存摘要（NMT）。
    //
    // 启动器（tools/perf_step0.py）已注入 -XX:NativeMemoryTracking=summary，所以
    // 这里可以直接经 com.sun.management:type=DiagnosticCommand 的 vmNativeMemory 把
    // 摘要取回来，每 6 次 [RT MEM JAVA]（≈60s）打一份，前缀 [RT MEM NMT]。
    //
    // 为什么需要它：进程私有提交 +40MB/s 这条曲线已经排除了 Java 堆（heapTotal 平）、
    // 本模组的 Vulkan 分配总账（vkLiveMB/vkLiveN 平）、slab（字节计数冻结）与几何
    // 记账，也排除了vram（vramUsedMB 平）；剩下的可能只有两类，而 NMT 恰好能把它们
    // 一刀分开：
    //   · NMT 的 total committed 同步在涨 ⇒ 增长在 **JVM 内部**（JNI/直接内存/
    //     Arena/Thread/Code...，看哪一类在涨）；
    //   · NMT 平而进程仍涨 ⇒ 增长在 **JVM 之外**：图形驱动，或第三方原生模块
    //     （nrd.dll / nvngx_dlss*.dll 在各自模块内分配，任何本模组的包装都看不见）。
    // =====================================================
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

    // =====================================================
    // 2026-10-24 项 1：原版可见性图 -> native（TLAS 主射线掩码剔除）
    //
    // 数据源是 LevelRenderer.visibleSections()（26.3 源码 :1289），由
    // LevelExtractor.applyFrustum（:472）经
    // SectionOcclusionGraph.addSectionsInFrustum 的 Octree 遍历填充 ——
    // 只包含"从相机出发可达"的 Section。native 侧拿它去清 TLAS 实例 mask 的
    // bit0（主射线位），实例本身保留 ⇒ 反射 / GI / 阴影 / 体积光 / 雨探针的
    // 行为逐位不变。
    //
    // 两个必须照抄原版的边界条件：
    //   · 相机处于 frustum 捕获状态（第三人称冻结 / 调试相机）时，
    //     LevelExtractor:140 的 `getCapturedFrustum() == null` 分支不成立
    //     ⇒ visibleSections 不刷新、可能陈旧 ⇒ 本帧上报 0，让 native 退回
    //     改动前的行为；
    //   · 列表为空同样上报 0（刚进世界时的空窗口不能变成"全剔"）。
    // =====================================================
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
        // 2026-10-09：内存记账（每 600 帧一次，见 rtx$logJavaMemory）。
        rtx$logJavaMemory();
        // Render-thread poll: the optional bridge snapshots Voxy output and publishes only changed meshes.
        VoxyLodManager.tick();
        // 远场接入探针（Distant Horizons）。与上面的远场通路同一时序位置，
        // 但内部完全独立：DH 缺失/版本不符时是一次空返回，不影响任何渲染状态。
        long rtx$t0 = System.nanoTime();
        DhBridge.tick();
        long rtx$t1 = System.nanoTime();
        VoxyLodManager.perfAdd(VoxyLodManager.PERF_DH, rtx$t1 - rtx$t0);
        // voxy 远场接管:推进压制/恢复状态表(靠近缝的 section 重编译上传,
        // 远离缝的已上传 section 从原生侧擦除)。
        FarFieldGate.updateNearby(cameraState, viewArea);
        long rtx$t2 = System.nanoTime();
        VoxyLodManager.perfAdd(VoxyLodManager.PERF_FFG, rtx$t2 - rtx$t1);
        // 2026-10-25：实体帧窗口在这里打开（世界实体）。
        // ⚠ 修正旧注释：26.3 的实际顺序是
        //   LevelRenderer.render（世界） → GameRenderer.render3dHud → renderItemInHand（手）
        // 即手**晚于**世界，而不是旧注释写的"手在世界之前"。
        // 因此窗口在这里只开、不收：必须一直开到手的 feature 批次提交完
        // （见 EntityRtCapture.finishEntityFrame 与 GameRendererMixin 的 prepareFrame
        // 注入点），否则手会撞上原生门禁 if(!g_entityFrameOpen) 被静默丢弃。
        // 幂等：无手帧同样由这里开；原生 beginEntityFrame()（清空顶点数组）由
        // beginEarlyFrame 内部的标记保证每帧恰好一次。
        EntityRtCapture.endHandPhase();
        EntityRtCapture.beginEarlyFrame();
        ParticleRtCapture.beginFrame();
        // 2026-10-07 动态光源（移动光源）：与粒子/实体同一个帧窗口、渲染线程。
        // 站着不动举火把时 native 侧整段跳过上传；没有移动光源时零开销。
        // 应急总闸：-Dorion.dynamicLights=0。
        DynamicLightCapture.submitFrame();
        // 2026-10-24 项 1：可见性图上报。必须在 RT 提交之前 —— native 的
        // rebuildWorldGeometry 读的是"最近一次上报的集合"，同帧次序无所谓，
        // 但放在这里能与实体/粒子采集共用同一个帧窗口。
        rtx$uploadVisibleSections(cameraState);
        VoxyLodManager.perfAdd(VoxyLodManager.PERF_CAPTURE, System.nanoTime() - rtx$t2);
    }

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void rtx$submitLocalPlayerProxy(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector output,
            CallbackInfo ci) {
        // 26.3 的 LevelExtractor 在第一人称下把相机实体从 entityRenderStates
        // 里剔除(睡眠除外),所以本地玩家的模型永远不会进普通实体层。这里在
        // 普通列表之后合成提交一次,由 ModelFeatureRendererMixin 的代理分支
        // 捕获进 LOCAL_PLAYER 代理层(实例 mask 0x02,次级光线 0xff 命中)。
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

    // 拦截 26.2 核心的 render 方法
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

        // 标记"我们这帧的 RT 提交已经收尾"。远场探针用它判定 DH 的渲染事件
        // 落在我们提交之前还是之后（决定读 DH 图像是否晚一帧）。
        DhBridge.markFrameEnded();

        // === 绝对起搏器：将安全获取的设备句柄在渲染线程一次性打入 C++ ===
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

        // Submit every frame after vanilla has prepared the current animated
        // entity models; device setup is a one-time concern only.
        ParticleRtCapture.endFrame();
        // 2026-10-25：实体帧**不在这里收**。
        // 手/手持物的 feature 批次要到 GameRenderer.render3dHud → renderItemInHand
        // 里才 prepareGroup（26.3 顺序：世界 → 手）。在这里调 endEntityFrame 会让手的
        // 上传撞上原生门禁被丢弃 —— 那就是"手完全不参与光追"的直接原因。
        // 实体帧的收口在 GameRendererMixin：
        //   · 手 prepareFrame 之后（同帧、早于 RT 注入的 createRenderPass）；
        //   · renderLevel RETURN 兜底（第三人称 / 隐藏 HUD / 无手帧）。
        // 粒子层与实体层是两个独立窗口，粒子的 endFrame 留在这里不变。
    }
}
