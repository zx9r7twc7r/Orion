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
        // 2026-10-25：手提交前必须保证实体帧窗口是开的。
        // 世界阶段（LevelRenderer.render HEAD）开过 ⇒ 这里幂等空操作，不会清空已捕获
        // 的世界实体；若某帧世界没渲染而手仍提交，这里是唯一的开窗点。
        // 不开窗的后果不是丢手，而是手的 uploadEntityBatch 被原生
        // if(!g_entityFrameOpen) 静默丢弃（见 EntityRtCapture.beginEarlyFrame 的说明）。
        EntityRtCapture.beginEarlyFrame();
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

    /**
     * 2026-10-25：实体帧窗口的收口（手路径）—— 这是"手进光追"的关键一步。
     *
     * <p>26.3 的顺序是 世界（{@code LevelRenderer.render}）→ 手
     * （{@code renderItemInHand}）。手/手持物的 feature 批次在后者的
     * {@code featureRenderDispatcher.prepareFrame(handAndScreenSubmitNodeStorage)}
     * 里才 prepareGroup（mod 的捕获点就在 prepareGroup 内），所以实体帧必须等到
     * 这一次 prepareFrame 之后才能收。</p>
     *
     * <p>注入位置刻意选在 prepareFrame 的 INVOKE 之后、而不是方法 RETURN：
     * 同一个 try-with-resources 的下一项就是
     * {@code createCommandEncoder().createRenderPass("Item in hand", ...)}，而 RT 注入
     * （{@code VulkanCommandEncoderMixin}）发生在那个 render pass 的
     * {@code vkCmdBeginRendering} 之前 —— 只要 AS 在那之前重建完成，手就在<b>同一帧</b>
     * 出现在 RT 里，没有一帧延迟。</p>
     *
     * <p>条件分支（第三人称 / 隐藏 HUD / 旁观）走不到注入点，由
     * {@link #rtx$finishEntityFrameFallback} 兜底。</p>
     */
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

    /**
     * 2026-10-25 B1 最后一步：手进光追之后，<b>取消光栅手的绘制</b>，让 RT 那一份显形。
     *
     * <p>为什么 Redirect {@code renderAllFeatures} 而不是在 {@code renderItemInHand}
     * 上 {@code ci.cancel()}：RT 注入的触发点是这一个方法里
     * {@code createRenderPass("Item in hand", ...)} 的 {@code vkCmdBeginRenderingKHR}
     * 之前（见 {@code VulkanCommandEncoderMixin.vulkan$injectRtBeforeGui}）。整个方法
     * 一旦 cancel 掉，render pass 就不会被创建 ⇒ RT 永远不会写进 main target ⇒
     * 画面变成"只有光栅世界、没有光追"。而 handAndScreenSubmitNodeStorage 的
     * prepareFrame 仍必须跑（那是 mod 捕获手/手持物几何的唯一入口）。</p>
     *
     * <p>所以只砍"把批次画到屏幕"这一步：submit → prepareFrame（捕获进实体批次 +
     * {@link EntityRtCapture#finishEntityFrame()} 收帧）→ createRenderPass（触发 RT
     * 注入）→ <b>这里跳过 renderAllFeatures</b>（不画光栅手）→ 空 render pass 关闭。
     * frame.close() 仍会照常执行 finishExecute / endDraw 清理，未消费的 draw 只是
     * 浪费一次几 KB 的 staged 上传。</p>
     *
     * <p>回退：{@code -Dorion.handRt=0} 或环境变量 {@code ORION_HAND_RT=0} 恢复原版
     * 光栅手（此时 RT 里仍有手，只是被盖住，等同搬迁完成、未关光栅的状态）。</p>
     */
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

    /**
     * 手是否走光追（B1）。默认为真。
     *
     * <p>关掉后回到 2026-10-25 之前的外观：光栅手照旧画在 RT 之上。RT 里的手仍在
     * （帧窗口搬迁是独立的一步），只是被盖住 —— 这正是二分"看不见手到底是 RT 没画
     * 还是被盖"的开关。</p>
     */
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
