package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.example.client.FrameBudget;
import com.example.client.bench.BenchmarkSession;
import com.example.vulkan.NativeVulkan;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
        FrameBudget.head();
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
        BenchmarkSession.frameEnd();
    }

    /**
     * 光追画面必须在**第一人称手之前**写进 main target —— 否则光栅手会被整片盖掉。
     *
     * <p>原先的注入点在 GUI 之前（{@code GuiRendererMixin} 上膛，见
     * {@code VulkanCommandEncoderMixin.vulkan$injectRtBeforeGui}）。而 MC 的绘制顺序是
     * 世界 → **手** → GUI：RT 在 GUI 前写入 ⇒ MC 画好的手被整片盖掉 ⇒ 表现为
     * "GUI 看得见、手看不见"（手的光栅在，只是没了）。所以上膛点放在手之前：
     * RT 落在手那一遍渲染作用域之前，手随后画在 RT 之上并被保留。</p>
     *
     * <p>⚠ 2026-10-25 修正：上面的"保留"只对**光栅手**成立。手臂与手持物现在也会
     * 被捕获进 RT（帧窗口搬迁见 {@code EntityRtCapture.finishEntityFrame}），
     * 但 RT 那一份和光栅手画在同一屏幕位置、光栅手后画 ⇒ 看到的仍是原版光照版，
     * 不是 PBR/RT 版。要让 RT 手显形，必须另有一步取消光栅手的绘制；
     * 仅调整这里的上膛位置解决不了。</p>
     *
     * <p>上膛用 main render target 的颜色图（与 GUI 那条路径取的是同一张图），
     * 后面的注入逻辑（{@code VulkanCommandEncoderMixin}）完全不用改。第三人称或
     * 无手渲染时不会走到这里，{@code GuiRendererMixin} 的老路径仍然是兜底。</p>
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void rtx$armRtInjectionBeforeHand(CallbackInfo ci) {
        EntityRtCapture.beginEarlyFrame();
        if (NativeVulkan.rtInjectedToMainTarget || NativeVulkan.guiRtPending) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return;
        }
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

    

    @Inject(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;"
                            + "prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)"
                            + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
                    shift = At.Shift.AFTER
            )
    )
    private void rtx$finishEntityFrameAfterHandPrepare(CallbackInfo ci) {
        EntityRtCapture.finishEntityFrame();
    }

    /**
     * 无手帧兜底：{@code renderLevel} 正常返回时若实体帧还开着，在这里收。
     *
     * <p>幂等（{@code EntityRtCapture.finishEntityFrame} 内部按 entityFrameOpen 早退），
     * 所以与手路径叠加调用是安全的。这同时覆盖"手路径提前 return（第三人称 /
     * HUD 隐藏 / 旁观）"的帧。注意粒子层不在这里收，它仍在
     * {@code LevelRendererMixin} 的 render RETURN。</p>
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void rtx$finishEntityFrameFallback(CallbackInfo ci) {
        EntityRtCapture.finishEntityFrame();
    }

    

    @Redirect(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;"
                            + "renderAllFeatures(Lcom/mojang/renderpearl/api/commands/RenderPass;"
                            + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"
            )
    )
    private void rtx$skipRasterHand(RenderPass renderPass, FeatureRenderDispatcher.PreparedFrame frame) {
        if (!HAND_RT_ENABLED) {
            FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
            return;
        }
        if (!rasterHandSkipLogged) {
            rasterHandSkipLogged = true;
            System.out.println("[RTX-HAND] raster hand skipped; RT hand is the visible one"
                    + " (-Dorion.handRt=0 to revert)");
        }
    }

    private static boolean rasterHandSkipLogged;

    

    private static final boolean HAND_RT_ENABLED = resolveHandRtEnabled();

    private static boolean resolveHandRtEnabled() {
        String raw = System.getProperty("orion.handRt");
        if (raw == null) {
            raw = System.getenv("ORION_HAND_RT");
        }
        return raw == null || !"0".equalsIgnoreCase(raw.trim());
    }

    private static boolean rtSeamLogged;
}
