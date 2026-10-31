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
