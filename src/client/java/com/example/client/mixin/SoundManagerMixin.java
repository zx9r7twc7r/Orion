package com.example.client.mixin;

import com.example.client.FrameBudget;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-15 第八段：sim 段四路细拆之三 —— 声音源更新。
 * runTick 在 renderFrame 之前调 soundManager.updateSource(mainCamera)
 * （反编译源 :1237），常规小额项，顺带摘出以闭合 sim 段。
 * 纯读时钟，不改任何行为。槽位 2，语义见 FrameBudget 第八段注释。
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @Inject(method = "updateSource", at = @At("HEAD"))
    private void frameBudget$soundHead(CallbackInfo ci) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.markSimSubStart(2);
        }
    }

    @Inject(method = "updateSource", at = @At("RETURN"))
    private void frameBudget$soundTail(CallbackInfo ci) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.endSimSub(2);
        }
    }
}
