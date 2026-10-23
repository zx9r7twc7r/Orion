package com.example.client.mixin;

import com.example.client.FrameBudget;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-15 第八段：sim 段四路细拆之四 —— 鼠标累积位移处理。
 * runTick 在 renderFrame 之前调 mouseHandler.handleAccumulatedMovement()
 * （反编译源 :1238），常规小额项，顺带摘出以闭合 sim 段。
 * 纯读时钟，不改任何行为。槽位 3，语义见 FrameBudget 第八段注释。
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void frameBudget$mouseHead(CallbackInfo ci) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.markSimSubStart(3);
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("RETURN"))
    private void frameBudget$mouseTail(CallbackInfo ci) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.endSimSub(3);
        }
    }
}
