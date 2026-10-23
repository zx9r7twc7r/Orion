package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 告示牌与实体名牌的文字。
 *
 * <p>26.3 里这些文字由 SubmitNodeCollector.submitText 提交，随
 * TextFeatureRenderer 在 prepareFrame 阶段由 GlyphRenderer 逐个字形发射顶点；
 * 它既不经过 ModelFeatureRenderer，也不是区块网格，因此 RT 侧两条捕获路径都
 * 收不到它，而原版光栅世界输出又被原生交换链整帧替换 —— 结果就是告示牌上
 * 看不见字符。这里在原版发射之后把同一批顶点镜像进实体批次。
 *
 * <p>注入点选在字形发射处而不是 submitText，是因为只有这里同时握有相机相对的
 * pose、光照坐标与字形图集（renderType 的 Sampler0），三者都是 RT 顶点 ABI
 * 需要的。prepareFrame 在 LevelRenderer#render 内部执行，正好落在
 * EntityRtCapture 的帧窗口内，所以 GUI 文字不会被误收。
 *
 * <p><b>2026-10-13 新增 HEAD 注入（只计时 + 可选的"跳过原版发射"）：</b>城市图招牌
 * 密集区的实测把这一段的账算清了 —— 原版 profiler 报 {@code Text} 占整帧 74.44%
 * （而 {@code uploadSharedVertexBuffer} 只有 1.16%、{@code executeFrameGraph} 只有
 * 2.99%），我们自己的分项只有 1.9~3.4ms/帧，而"相邻字形回调之间的墙钟之和"
 * （{@code textPathUs}）达 67~101ms/帧。也就是说这几十毫秒全落在原版**逐字形**
 * 这一段里，但到底是落在 {@code acceptRenderable} 的函数体内（顶点发射），还是
 * 落在两次调用之间的间隙里（调用方的布局/姿态工作），TAIL 一个点分不出来 ——
 * 所以在 HEAD 再补一个点，两者相减即可切开。这是纯读时钟，零行为变化。
 *
 * <p>取消那一支默认**关闭**（见 {@link EntityRtCapture#SKIP_VANILLA_WORLD_TEXT}）：
 * 若上面切出来的结果是"函数体占大头"，再打开它就能省掉白造的那份顶点
 * （原版世界文字的这份输出在 RT 模式下整帧被替换）。视觉上不变，因为镜像那份仍在。</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.TextFeatureRenderer$GlyphRenderer")
public class TextGlyphRendererMixin {

    @Shadow
    @Final
    private Matrix4f pose;

    @Shadow
    private int lightCoords;

    @Shadow
    private Font.DisplayMode displayMode;

    @Inject(method = "acceptRenderable", at = @At("HEAD"), cancellable = true)
    private void rtx$captureTextGlyph(TextRenderable renderable, CallbackInfo ci) {
        // 2026-10-13：从 TAIL 移到 HEAD，并且**先镜像、再按需取消原版这一遍**。
        //
        // 位置为什么可以移到 HEAD：三个字段里 pose 是 @Final（原版改不了它），
        // lightCoords/displayMode 是调用方在进来之前设好的"当前文字元素"状态 ——
        // 所以 HEAD 读到的东西与 TAIL 逐位相同，镜像几何不会偏。
        //
        // 取消的条件是"本字形**镜像成功**"：镜像失败的早退路径（texture 解析不出、
        // 槽号为 0）一律不取消，那些字形照旧由原版画 —— 所以不会出现"两边都没有"。
        //
        // 收益（2026-10-13 实测，城市图招牌区）：textEmitUs（原版函数体内）63.9~89.6ms
        // 占 textPathUs 的 96%，而字形之间的间隙只有 1.5~3.4ms ⇒ 这一遍几乎全是
        // "造一份马上被整帧替换掉的顶点"。原版 profiler 同期报 Text = 整帧 76.24%。
        //
        // ⚠ 必须 HEAD+cancel：Mixin 的 ci.cancel() 会让方法立刻返回，因此原方法的
        // TAIL 注入点不会再执行 —— 若还把镜像留在 TAIL，取消之后镜像也没了。
        final boolean mirrored = EntityRtCapture.captureTextGlyph(
                renderable, this.pose, this.lightCoords, this.displayMode);

        if (mirrored && EntityRtCapture.SKIP_VANILLA_WORLD_TEXT) {
            ci.cancel();
        }
    }
}
