package com.example.client.compat.dh.mixin;

import com.example.client.compat.dh.DhLodMeshSink;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * 接管点：挂在 DH "每个 section 构建并上传 LOD 顶点缓冲"的那一步。
 *
 * <h2>为什么是这个方法</h2>
 * <p>{@code LodBufferContainer.tryMakeAndUploadBuffersAsync(long pos, IClientLevelWrapper,
 * ArrayList&lt;ByteBuffer&gt; opaque, ArrayList&lt;ByteBuffer&gt; transparent)} 是
 * "该 section 唯一一次、且在真正上传之前"的位置，而且它的 {@code pos} 能解出 section 原点
 * —— 这一点很关键：DH 交给上传的每个顶点只有 <b>section 内的局部 short 坐标</b>，
 * 没有原点，所以<b>不在这里拿 pos，后面就没有原点可用</b>。</p>
 *
 * <p>备选的 {@code LodQuadBuilder.makeOpaqueVertexBuffers(...)} 拿不到原点，因此不用。</p>
 *
 * <h2>为什么本类可以 import DH 类型</h2>
 * <p>{@code DhBridge} 的硬约束是"DH 类型只允许出现在 {@code compat.dh} 包内"，
 * 为的是未装 DH 时不触发 {@link NoClassDefFoundError}。混入类是这个约束的
 * <b>有门控的例外</b>：它由 {@link DhMixinPlugin} 拦在应用之前，
 * 未装 DH 时连加载都不会发生。所以本类放在 {@code compat.dh.mixin} 子包内
 * （同属 {@code compat.dh} 一族），而不是 {@code client.mixin}。</p>
 *
 * <p>⚠ 本混入<b>只读不改写</b>：{@code @At("HEAD")} + 不 cancel、不 setReturnValue，
 * DH 的构建与上传流程原样继续。它只把这一批顶点字节拷进
 * {@link DhLodMeshSink}（窗口过滤 + 暂存），真正的合并/上传由渲染线程的
 * {@code DhFarFieldUploader} 做。任何异常都在这里吞掉，绝不能影响 DH。</p>
 */
@Mixin(LodBufferContainer.class)
public abstract class DhLodBufferHookMixin {

    @Inject(method = "tryMakeAndUploadBuffersAsync", at = @At("HEAD"))
    private static void orion$observeLodBuffers(long pos, IClientLevelWrapper level,
                                                   ArrayList<ByteBuffer> opaque,
                                                   ArrayList<ByteBuffer> transparent,
                                                   CallbackInfoReturnable<?> cir) {
        try {
            DhLodMeshSink.onBuilt(pos,
                    DhSectionPos.getBlockWidth(pos),
                    DhSectionPos.getMinCornerBlockX(pos),
                    DhSectionPos.getMinCornerBlockZ(pos),
                    level == null ? 0 : level.getMinHeight(),
                    opaque, transparent);
        } catch (Throwable ignored) {
        }
    }
}
