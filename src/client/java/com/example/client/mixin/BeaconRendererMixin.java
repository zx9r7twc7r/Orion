package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-21 信标光柱进入光追世界。
 *
 * <p>{@code BeaconRenderer} 的顶点同样走 {@code addVertex(pose,…)+setColor/setUv}
 * 链，全格式钩子收不到 ⇒ 之前光柱不可见。在 submit HEAD 由 Java 侧按原版
 * {@code submitBeaconBeam} 的几何/UV 滚动重建实心柱（见
 * {@link EntityRtCapture#captureBeaconBeam}）。</p>
 */
@Mixin(BeaconRenderer.class)
public class BeaconRendererMixin {

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BeaconRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD")
    )
    private void rtx$captureBeaconBeam(
            BeaconRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            CallbackInfo ci) {
        EntityRtCapture.captureBeaconBeam(state, poseStack);
    }
}
