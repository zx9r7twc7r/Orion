package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-06 末地传送门：**自合成几何**入口。
 *
 * <p>为什么不用 BufferBuilder 钩子：末地门走短格式
 * {@code addVertex(Pose;Vector3fc;)}，而现有钩子只覆盖全格式 ⇒ 顶点一个都进不来
 * ⇒ 批次空 ⇒ 不渲染。补钩短格式会**崩驱动**，因为它按"方向 × 面 × 方块"提交、
 * 顶点数没有上界。所以这里只把**姿态**交给 Java 侧，由
 * {@link EntityRtCapture#captureEndPortal} 自己画两个交叉四边形（硬上界 16 顶点/
 * 方块）。原版光栅提交一字未动（照旧被整帧替换）。</p>
 *
 * <p>⚠ 2026-10-17 修：原先混入的是 {@code AbstractEndPortalRenderer.submitSpecial}，
 * 但 26.3 的 {@code TheEndPortalRenderer.submit} 已经**不再调用 submitSpecial**
 * （它直接调自己的 {@code submitCube}）。混入点从未命中 ⇒ 合成几何一次都没提交过
 * （日志里只有 "end portal geometry captured"、没有 "synthetic reached"）。现在改混
 * {@code TheEndPortalRenderer.submit} 的 HEAD：每个传送门方块一次，姿态已平移到
 * 该方块。</p>
 */
@Mixin(TheEndPortalRenderer.class)
public abstract class AbstractEndPortalRendererMixin {

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/blockentity/state/EndPortalRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD")
    )
    private void onEndPortalSubmit(
            EndPortalRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraState,
            CallbackInfo ci
    ) {
        EntityRtCapture.captureEndPortal(poseStack.last());
    }
}
