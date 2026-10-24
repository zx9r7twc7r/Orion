package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.example.client.FrameBudget;
import com.example.client.bench.BenchmarkSession;
import com.example.vulkan.NativeVulkan;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-06：每帧复位实体捕获的"已开窗"标记，并且**把光追合成提前到手之前**。
 *
 * <p>{@code GameRenderer.render()} 是**每个渲染帧**必然执行的最外层入口（26.3 无语参，
 * 已按字节码核对）。实体帧窗口由 {@code LevelRenderer} 打开并收帧；如果某一帧开了窗
 * 却没走到收帧（关卡未渲染：进世界加载中、全屏界面、第三人称且无实体提交等），标记会
 * 一直挂着，之后就不开窗了。这里只清 Java 侧标记，不产生任何原生调用。</p>
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void rtx$resetEntityFrameOpenFlag(CallbackInfo ci) {
        EntityRtCapture.resetEntityFrameOpen();
        // 2026-10-13 帧预算二分（见 FrameBudget）：纯读时钟，不动任何渲染状态。
        FrameBudget.head();
        // 2026-10-24 基准会话（见 com.example.client.bench.BenchmarkSession）：
        // 未开启时是一行 boolean 判空；开启后每帧两次 nanoTime + 一行 JSON。
        BenchmarkSession.frameStart();
    }

    /**
     * 2026-10-13：帧预算二分器的出口端（见 {@link FrameBudget}）。
     *
     * <p>与上面的 HEAD 配对，量的是 {@code GameRenderer.render()} **自身**的墙钟耗时。
     * 它与帧间隔之差就是"不在渲染里"的那一段（tick / GC / 呈现与帧生成排队 / 等待）——
     * 这是至今所有日志都读不出来的那个洞：帧间隔 90ms 而 GPU 只有 15ms、我们自己的
     * C++ 只有 7ms 时，那几十毫秒到底在 render 里还是外面。</p>
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void rtx$frameBudgetTail(CallbackInfo ci) {
        FrameBudget.tail();
        // 与 HEAD 配对：量的是 GameRenderer.render() 自身的墙钟（含我们的注入与 Java 钩子）。
        BenchmarkSession.frameEnd();
    }

    /**
     * 光追画面必须在**第一人称手之前**写进 main target —— 这是"手看不见"的根治点。
     *
     * <p>原先的注入点在 GUI 之前（{@code GuiRendererMixin} 上膛，见
     * {@code VulkanCommandEncoderMixin.vulkan$injectRtBeforeGui}）。而 MC 的绘制顺序是
     * 世界 → **手** → GUI：RT 在 GUI 前写入 ⇒ MC 画好的手被整片盖掉 ⇒ 表现为
     * "GUI 看得见、手看不见"（手的光栅在，只是没了）。</p>
     *
     * <p>主渲染路径也是同一个接缝（{@code VanillaRenderController} 里叫
     * "before-hand RT composite seam"），并且明确**不把第一人称手放进光追**、
     * 让原版光栅画它。这里照做：在手渲染前一刻上膛，于是 RT 落在手自己那一遍渲染
     * 作用域之前，手随后画在 RT 之上并被保留。</p>
     *
     * <p>上膛用 main render target 的颜色图（与 GUI 那条路径取的是同一张图），
     * 后面的注入逻辑（{@code VulkanCommandEncoderMixin}）完全不用改。第三人称或
     * 无手渲染时不会走到这里，{@code GuiRendererMixin} 的老路径仍然是兜底。</p>
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void rtx$armRtInjectionBeforeHand(CallbackInfo ci) {
        if (NativeVulkan.rtInjectedToMainTarget || NativeVulkan.guiRtPending) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return;
        }
        // 26.3 的主渲染目标挂在 GameRenderer 上（Minecraft.getMainRenderTarget()
        // 已不存在）：gameRenderer.mainRenderTarget()。与 GUI 那条路径取的是同一张图。
        final RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (mainTarget == null) {
            return;
        }
        final GpuTexture colorTexture = mainTarget.getColorTexture();
        if (!(colorTexture instanceof VulkanGpuTexture vulkanTexture)) {
            return;
        }
        if ((colorTexture.usage() & GpuTexture.USAGE_COPY_DST) == 0) {
            return;
        }
        final long image = vulkanTexture.vkImage();
        final int width = colorTexture.getWidth(0);
        final int height = colorTexture.getHeight(0);
        if (image == 0L || width <= 0 || height <= 0) {
            return;
        }
        NativeVulkan.pendingGuiTargetImage = image;
        NativeVulkan.pendingGuiTargetWidth = width;
        NativeVulkan.pendingGuiTargetHeight = height;
        NativeVulkan.guiRtPending = true;
        if (!rtSeamLogged) {
            rtSeamLogged = true;
            System.out.println("[RTX-SEAM] RT injection armed BEFORE first-person hand"
                    + " image=0x" + Long.toHexString(image)
                    + " " + width + "x" + height);
        }
    }

    private static boolean rtSeamLogged;
}
