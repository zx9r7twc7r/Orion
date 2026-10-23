package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.client.renderer.entity.state.LightningBoltRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-21 闪电进入光追世界。
 *
 * <p>原版几何由 {@code addVertex(pose,…)+setColor} 链写入，不经过
 * {@code BufferBuilder.addVertex(FFFIFFIIFFF)}；同时 {@code RenderTypes.lightning()}
 * 没有 Sampler0 ⇒ 通用捕获在贴图解析处直接丢弃。两条路都收不到，闪电完全不可见。</p>
 *
 * <p>在 submit HEAD 由 Java 侧按同一算法合成几何（见
 * {@link EntityRtCapture#captureLightning}）。原版光栅提交保持不动。</p>
 */
@Mixin(LightningBoltRenderer.class)
public class LightningBoltRendererMixin {

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LightningBoltRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD")
    )
    private void rtx$captureLightning(
            LightningBoltRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            CallbackInfo ci) {
        EntityRtCapture.captureLightning(state.seed, poseStack);
    }
}
