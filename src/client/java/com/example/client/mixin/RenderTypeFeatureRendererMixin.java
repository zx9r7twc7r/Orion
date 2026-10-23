package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-10-06：把「所有基于 RenderType 的几何」都接进实体捕获。
 *
 * <p>26.3 的 {@code RenderTypeFeatureRenderer} 是 {@code ModelFeatureRenderer} /
 * {@code CustomFeatureRenderer} / {@code ItemFeatureRenderer} /
 * {@code BlockModelFeatureRenderer} / {@code MovingBlockFeatureRenderer} 的共同基类，
 * 它们的几何都通过同一个 {@code getVertexBuilder(RenderType)} 拿到写入目标。mod 原来
 * 只在 {@code ModelFeatureRenderer.prepareModel} 上开捕获窗口，所以另外四条链的几何
 * 一条都没进光追世界，而原版光栅世界又被整帧替换 ⇒ 这些内容直接消失：</p>
 *
 * <ul>
 *   <li>{@code Custom} → 画、地图、末地传送门/折跃门、信标光柱、物品展示实体、
 *       守卫者光束、龙……</li>
 *   <li>{@code Item} → 物品展示框里的物品、掉落物、第一人称手持物品</li>
 *   <li>{@code BlockModel} → 物品展示框的框体、移动方块模型</li>
 *   <li>{@code MovingBlock} → 活塞推动中的方块</li>
 * </ul>
 *
 * <p>模型路径保持原样：{@code ModelFeatureRendererMixin} 已经在 {@code prepareModel}
 * 的 HEAD 打开批次，{@link EntityRtCapture#beginCustomSubmit} 见到批次已开就让路。</p>
 *
 * <p>批次在 {@code prepareGroup} 返回时收尾（一个 group 就是一次 feature 提交的
 * 完整几何）；组内 RenderType 变化时也会先收尾再开新批次，保证每个顶点带着正确的
 * 贴图槽与材质。</p>
 */
@Mixin(RenderTypeFeatureRenderer.class)
public class RenderTypeFeatureRendererMixin {

    @Inject(method = "getVertexBuilder", at = @At("RETURN"))
    private void rtx$captureGenericGeometry(
            RenderType renderType,
            CallbackInfoReturnable<VertexConsumer> cir) {
        EntityRtCapture.beginCustomSubmit(renderType);
    }

    @Inject(method = "prepareGroup", at = @At("RETURN"))
    private void rtx$finishGenericGeometryGroup(
            FeatureFrameContext context,
            List<?> submits,
            boolean strictlyOrdered,
            CallbackInfo ci) {
        EntityRtCapture.endCustomGroup();
    }
}
