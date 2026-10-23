package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ExperienceOrbRenderer;
import net.minecraft.client.renderer.entity.state.ExperienceOrbRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceOrbRenderer.class)
public abstract class ExperienceOrbRendererMixin {
    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/ExperienceOrbRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD")
    )
    private void rtx$captureExperienceOrb(
            ExperienceOrbRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera,
            CallbackInfo ci) {
        int icon = state.icon;
        float u0 = (icon % 4 * 16) / 64.0F;
        float u1 = (icon % 4 * 16 + 16) / 64.0F;
        float v0 = (icon / 4 * 16) / 64.0F;
        float v1 = (icon / 4 * 16 + 16) / 64.0F;
        float phase = state.ageInTicks / 2.0F;
        int red = (int) ((Mth.sin(phase) + 1.0F) * 0.5F * 255.0F);
        int blue = (int) ((Mth.sin(phase + (float) (Math.PI * 4.0 / 3.0)) + 1.0F) * 0.1F * 255.0F);

        poseStack.pushPose();
        try {
            poseStack.translate(0.0F, 0.1F, 0.0F);
            poseStack.mulPose(new Matrix4f().rotation(camera.orientation));
            poseStack.scale(0.3F, 0.3F, 0.3F);
            EntityRtCapture.captureExperienceOrb(
                    poseStack.last(),
                    u0, u1, v0, v1,
                    red, 255, blue
            );
        } finally {
            poseStack.popPose();
        }
    }
}
