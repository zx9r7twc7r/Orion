package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.example.client.cull.CullableRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {
    @Shadow @Final
    private PoseStack poseStack;

    @Inject(method = "prepareModel", at = @At("HEAD"), cancellable = true)
    private <S> void rtx$beginModelCapture(ModelFeatureRenderer.Submit<S> submit, CallbackInfo ci) {
        // 2026-10-20 实体遮挡剔除的消费点（生产路径唯一的模型几何入口）。
        // 判定见 EntityVisibilityCuller，标记由 EntityRenderDispatcherMixin 写进状态。
        // 只是不捕获几何：原版的状态提取、名牌、其它 feature 都不受影响；
        // EntityRtCapture.endModel() 对空批次是安全的（RETURN 注入仍会走到）。
        if (submit.state() instanceof CullableRenderState culledState && culledState.orion$isCulled()) {
            ci.cancel();
            return;
        }
        boolean proxy = EntityRtCapture.isLocalPlayerProxyState(submit);
        if (EntityRtCapture.isDiagnosticFrame()
                && !proxy
                && submit.state() instanceof AvatarRenderState avatarState) {
            var player = Minecraft.getInstance().player;
            if (player != null && avatarState.id == player.getId()) {
                System.err.println("[RTX-PROXY] prepareModel proxy MISS for local player");
            }
        }
        if (proxy) {
            // 合成代理:复刻原版 prepareModel 的 pose 起始点后直接捕获,
            // 不进普通实体层,避免主光线看到自己的脸。
            this.poseStack.last().set(submit.pose());
            EntityRtCapture.captureLocalPlayerProxy(submit, this.poseStack);
            ci.cancel();
            return;
        }
        EntityRtCapture.beginModel(submit);
    }

    @Inject(method = "prepareModel", at = @At("RETURN"))
    private <S> void rtx$endModelCapture(ModelFeatureRenderer.Submit<S> submit, CallbackInfo ci) {
        EntityRtCapture.endModel();
    }
}
