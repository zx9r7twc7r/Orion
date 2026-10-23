package com.example.client.cull;

/**
 * 实体渲染状态上的"本帧已被遮挡剔除"标记（由 {@code EntityRenderStateMixin} 实现）。
 *
 * <p>为什么需要这个标记而不是在消费点按 id 再查一次：26.3 的
 * {@code EntityRenderState} 只有 {@code AvatarRenderState} 带 {@code id}，
 * 通用实体状态里没有任何能反查实体的字段。而 RT 几何的唯一入口
 * {@code ModelFeatureRenderer.prepareModel(Submit)} 只拿得到 {@code submit.state()}。
 * 所以只能在 {@code EntityRenderDispatcher.extractEntity} 返回时（那里实体与状态都在手边）
 * 把判定结果写进状态，消费点只做一次字段读。</p>
 */
public interface CullableRenderState {

    void orion$setCulled(boolean culled);

    boolean orion$isCulled();
}
