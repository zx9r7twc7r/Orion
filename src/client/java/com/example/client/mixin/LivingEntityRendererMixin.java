package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends only the synthetic first-person player body to RT, never to raster. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Shadow protected EntityModel<LivingEntityRenderState> model;
    @Shadow protected abstract boolean isBodyVisible(LivingEntityRenderState state);
    @Shadow protected abstract void setupRotations(
            LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale);
    @Shadow protected abstract void scale(LivingEntityRenderState state, PoseStack poseStack);
    @Shadow protected abstract RenderType getRenderType(
            LivingEntityRenderState state, boolean isBodyVisible, boolean forceTransparent, boolean appearGlowing);
    @Shadow protected abstract float getWhiteOverlayProgress(LivingEntityRenderState state);
    @Shadow protected abstract int getModelTint(LivingEntityRenderState state);

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void rtx$captureFirstPersonProxyOnly(
            LivingEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            CallbackInfo ci) {
        if (!rtx$isFirstPersonLocalPlayer(state)) return;

        poseStack.pushPose();
        if (state.hasPose(Pose.SLEEPING)) {
            Direction direction = state.bedOrientation;
            if (direction != null) {
                float headOffset = state.eyeHeight - 0.1F;
                poseStack.translate(-direction.getStepX() * headOffset, 0.0F,
                        -direction.getStepZ() * headOffset);
            }
        }

        float scale = state.scale;
        poseStack.scale(scale, scale, scale);
        this.setupRotations(state, poseStack, state.bodyRot, scale);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        this.scale(state, poseStack);
        poseStack.translate(0.0F, -1.501F, 0.0F);

        boolean bodyVisible = this.isBodyVisible(state);
        boolean forceTransparent = !bodyVisible && !state.isInvisibleToPlayer;
        RenderType renderType = this.getRenderType(
                state, bodyVisible, forceTransparent, state.appearsGlowing());
        if (EntityRtCapture.isDiagnosticFrame()) {
            System.err.println("[RTX-PROXY] submit gate hit, renderTypeNull=" + (renderType == null));
        }
        if (renderType != null) {
            int overlay = LivingEntityRenderer.getOverlayCoords(
                    state, this.getWhiteOverlayProgress(state));
            int baseColor = forceTransparent ? 654311423 : -1;
            int tinted = ARGB.multiply(baseColor, this.getModelTint(state));
            EntityRtCapture.captureLocalPlayerModel(
                    this.model, state, poseStack, renderType,
                    state.lightCoords, overlay, tinted);
        }
        poseStack.popPose();
        ci.cancel();
    }

    private static boolean rtx$isFirstPersonLocalPlayer(LivingEntityRenderState state) {
        boolean isAvatar = state instanceof AvatarRenderState;
        Minecraft minecraft = Minecraft.getInstance();
        boolean idMatches = isAvatar
                && minecraft.player != null
                && ((AvatarRenderState) state).id == minecraft.player.getId();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        if (EntityRtCapture.isDiagnosticFrame() && isAvatar) {
            System.err.println("[RTX-PROXY] gate check: isAvatar=" + isAvatar
                    + " idMatches=" + idMatches + " firstPerson=" + firstPerson);
        }
        if (!isAvatar) return false;
        if (minecraft.player == null || ((AvatarRenderState) state).id != minecraft.player.getId()) return false;
        return firstPerson;
    }
}
