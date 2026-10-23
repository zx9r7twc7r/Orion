package com.example.client.mixin;

import com.example.client.cull.CullableRenderState;
import com.example.client.cull.EntityVisibilityCuller;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在实体渲染状态生成完成时，把遮挡剔除的判定结果写进状态。
 *
 * <p>挂点是 26.3 的 {@code EntityRenderDispatcher.extractEntity(Entity, float)} —— 这是
 * {@code LevelExtractor.extractVisibleEntities} 与本地玩家代理（{@code LevelRendererMixin}）
 * 共用的唯一入口，实体与状态都在手边。EntityCulling选择在
 * {@code LevelExtractor.extractEntity} 里**取消**提取；我们只标记、不取消，因为取消会连带
 * 打掉原版的名牌/阴影/描边等特征提交，而我们要省的只是 RT 模型几何那一份。</p>
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void orion$markCulledState(
            E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        if(!EntityVisibilityCuller.isCullingActive() || entity == null) {
            return;
        }
        if(cir.getReturnValue() instanceof CullableRenderState marked) {
            marked.orion$setCulled(EntityVisibilityCuller.isCulled(entity.getId()));
        }
    }
}
