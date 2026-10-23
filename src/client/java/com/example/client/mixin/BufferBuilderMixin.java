package com.example.client.mixin;

import com.example.client.EntityRtCapture;
import com.mojang.blaze3d.vertex.BufferBuilder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {

    @Shadow
    private boolean blockFormat;

    @Shadow
    private long vertexPointer;

    @Inject(
            method = "addVertex(FFFIFFIIFFF)V",
            at = @At("TAIL")
    )
    private void onAddVertex(
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlayCoords,
            int lightCoords,
            float nx,
            float ny,
            float nz,
            CallbackInfo ci
    ) {
        EntityRtCapture.captureVertex(x, y, z, color, u, v);

        if (!this.blockFormat) {
            return;
        }

        if (this.vertexPointer == -1L) {
            return;
        }

        if (Float.isNaN(nx) || Float.isInfinite(nx)) {
            return;
        }

        int materialId = Math.round(nx);

        if (materialId < 0) {
            materialId = 0;
        }

        if (materialId > 255) {
            materialId = 255;
        }

        MemoryUtil.memPutByte(
                this.vertexPointer + 31L,
                (byte) materialId
        );
    }

    // =====================================================================
    // 2026-10-06 已回退：短格式 addVertex(FFF) 的钩子会崩驱动。
    //
    // 背景（仍然成立，别丢）：末地门走 VertexConsumer.addVertex(Pose;Vector3fc;)，其默认
    // 实现归到短格式 BufferBuilder.addVertex(FFF)（字节码已核对），而本文件只钩全格式
    // ⇒ 顶点一个都进不来 ⇒ 批次空 ⇒ 不上传 ⇒ 门不可见。
    //
    // 但补上短格式钩子后，**靠近末地门时驱动崩溃**：
    //   hs_err_pid45160.log → EXCEPTION_ACCESS_VIOLATION in nvoglv64.dll，
    //   Render thread，in_native，latest.log 无 Java 异常 ⇒ 原生/Vulkan 层非法访问。
    // 最合理的解释：末地门按**每个面/每个方块**提交（submitCube 遍历方向 × 面），
    // 顶点数远超"实体模型批次"的容量假设 ⇒ 上传越界（Java 侧缓冲或 native 暂存缓冲）
    // ⇒ 驱动读越界 ⇒ 崩。这条路径**没有上界**，所以先回退，不再赌。
    //
    // 正确做法（下一轮）：**不拦截 BufferBuilder**，改为像第一人称手代理那样
    //（见 EntityRtCapture 里 addVertex(pose, …) 那段合成四边形的先例）
    // 由 Java 侧**自己合成末地门四边形** —— 顶点数与内容全部可控、有上界、零拦截风险。
    // =====================================================================
}
