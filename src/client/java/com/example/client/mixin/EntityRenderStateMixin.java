package com.example.client.mixin;

import com.example.client.cull.CullableRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给通用实体渲染状态加一个"本帧已被遮挡剔除"标记。
 *
 * <p>26.3 的 {@code EntityRenderState} 没有 {@code id}（只有 {@code AvatarRenderState} 有），
 * 而 RT 几何入口 {@code ModelFeatureRenderer.prepareModel} 只拿得到状态对象，所以判定结果
 * 必须在 {@code extractEntity} 返回时写进状态。字段是 {@code @Unique} 且每帧新状态默认 false，
 * 不会跨帧残留。</p>
 */
@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements CullableRenderState {

    @Unique
    private boolean orion$culled;

    @Override
    public void orion$setCulled(boolean culled) {
        this.orion$culled = culled;
    }

    @Override
    public boolean orion$isCulled() {
        return this.orion$culled;
    }
}
