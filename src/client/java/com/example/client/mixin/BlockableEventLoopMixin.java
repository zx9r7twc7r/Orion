package com.example.client.mixin;

import com.example.client.FrameBudget;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-15 帧预算第五段：主线程任务泵计时（纯读时钟，不改任何行为）。
 *
 * <p>javap 实证 Minecraft.runTick 的字节码里有 3 处 {@code runAllTasks:()V} 调用，
 * 区块编译完成后的消费/上传任务就在这个主线程队列里跑 —— 它是
 * "simMs − vanillaTickMs"（移动时 20.28 − 0.56 ms）的大头嫌疑。</p>
 *
 * <p>{@code runAllTasks} 计整段抽干；{@code doRunTask} 计单个任务（次数/合计/
 * 单任务最大值，用于抓离群任务）。BlockableEventLoop 也被 worker 循环继承，
 * 线程过滤在 FrameBudget 里做（tickHead 记录主线程引用，非主线程不累计）。</p>
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin {

    @Inject(method = "runAllTasks", at = @At("HEAD"))
    private void fb$taskPumpHead(CallbackInfo ci) {
        FrameBudget.taskPumpHead();
    }

    @Inject(method = "runAllTasks", at = @At("RETURN"))
    private void fb$taskPumpTail(CallbackInfo ci) {
        FrameBudget.taskPumpTail();
    }

    @Inject(method = "doRunTask(Ljava/lang/Runnable;)V", at = @At("HEAD"))
    private void fb$taskRunHead(Runnable task, CallbackInfo ci) {
        FrameBudget.taskRunHead();
    }

    @Inject(method = "doRunTask(Ljava/lang/Runnable;)V", at = @At("RETURN"))
    private void fb$taskRunTail(Runnable task, CallbackInfo ci) {
        FrameBudget.taskRunTail();
    }
}
