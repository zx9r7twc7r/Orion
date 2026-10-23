package com.example.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 读取 {@code EntityRenderer} 的**剔除语义**，对齐其
 * {@code NMSCullingHelper.getCullingBox} / {@code EntityRendererMixin.entityCullingIgnoresCulling}。
 *
 * <p>为什么必须走 renderer 而不是直接 {@code entity.getBoundingBox()}：26.3 的
 * {@code getBoundingBoxForCulling} 默认返回 {@code entity.getInterpolatedBoundingBox}，
 * 但各类渲染器会覆写它（鱼线、末影龙部件、展示框、告示牌……）。用碰撞箱判可见性会把
 *
 * <p>两个方法在 {@code EntityRenderer} 里都是 {@code protected}，所以用 {@code @Invoker}
 * 生成调用，不引入 access widener。</p>
 */
@Mixin(EntityRenderer.class)
public interface EntityRendererCullAccess {

    @Invoker("getBoundingBoxForCulling")
    AABB orion$getBoundingBoxForCulling(Entity entity, float partialTicks);

    @Invoker("affectedByCulling")
    boolean orion$affectedByCulling(Entity entity);
}
