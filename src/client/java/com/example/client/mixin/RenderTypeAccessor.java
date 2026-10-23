package com.example.client.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.class)
public interface RenderTypeAccessor {
    @Accessor("state") RenderSetup rtx$getState();

    /**
     * 2026-10-06：RenderType 构造时的名字（26.3 字段 {@code protected final String name}）。
     * 屏幕特效（水下/火焰/方块遮罩）也走 submitCustomGeometry，而火焰那一档的
     * Sampler0 是**方块图集**（不能按贴图路径拒），只能按名字拒：
     * {@code block_screen_effect} / {@code fire_screen_effect}。
     */
    @Accessor("name") String rtx$getName();
}
