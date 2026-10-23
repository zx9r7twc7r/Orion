package com.example.client.mixin;

import com.example.client.FrameBudget;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-15 第八段：sim 段四路细拆之一 —— 主线程网络包。
 * 为什么量：sim 段 12-16ms 未归因（vanillaTick/task/JNI 全部 ~0.5ms），
 * 流式期区块数据包洪水在主线程 processQueuedPackets 里处理，是头号嫌疑。
 * 纯读时钟，不改任何行为。槽位 0，语义见 FrameBudget 第八段注释。
 */
@Mixin(PacketProcessor.class)
public class PacketProcessorMixin {

    @Inject(method = "processQueuedPackets", at = @At("HEAD"))
    private void frameBudget$packetsHead(CallbackInfo ci) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.markSimSubStart(0);
        }
    }

    @Inject(method = "processQueuedPackets", at = @At("RETURN"))
    private void frameBudget$packetsTail(CallbackInfo ci) {
        if (FrameBudget.isSimSubActive()) {
            FrameBudget.endSimSub(0);
        }
    }
}
