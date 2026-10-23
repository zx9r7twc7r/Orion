package com.example.client.mixin;

import com.example.client.FrameBudget;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.gizmos.Gizmos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-10-15 第八段：sim 段四路细拆之二 —— 26.3 新管线的主线程区块提取。
 * 为什么量：runTick 在 renderFrame 之前调 levelExtractor.collectPerFrameMainThreadGizmos()
 * （反编译源 :1239），这是新版区块提取的主线程步，流式期嫌疑第二位。
 * 纯读时钟，不改任何行为。槽位 1，语义见 FrameBudget 第八段注释。
 *
 * 2026-10-15 修启动崩溃（两次）：
 *   1) 目标方法**非 void** ⇒ 处理器必须用 CallbackInfoReturnable（用 CallbackInfo
 *      会报 Invalid descriptor）；
 *   2) TemporaryCollection 是泛型 ⇒ 存在 ()Ljava/lang/Object; 桥接方法 ⇒ method
 *      字符串会命中两个 ⇒ 写死精确描述符只命中真方法。
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Inject(
            method = "collectPerFrameMainThreadGizmos()Lnet/minecraft/gizmos/Gizmos$TemporaryCollection;",
            at = @At("HEAD")
    )
    private void frameBudget$extractHead(
            CallbackInfoReturnable<Gizmos.TemporaryCollection> cir
    ) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.markSimSubStart(1);
        }
    }

    @Inject(
            method = "collectPerFrameMainThreadGizmos()Lnet/minecraft/gizmos/Gizmos$TemporaryCollection;",
            at = @At("RETURN")
    )
    private void frameBudget$extractTail(
            CallbackInfoReturnable<Gizmos.TemporaryCollection> cir
    ) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.endSimSub(1);
        }
    }
}
