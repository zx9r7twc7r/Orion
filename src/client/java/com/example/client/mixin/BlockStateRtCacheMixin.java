package com.example.client.mixin;

import com.example.client.access.RtStateCache;

import net.minecraft.world.level.block.state.BlockBehaviour;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;


/**
 * 2026-10-21 FerriteCore 式 per-state 缓存载体。
 *
 * <p>改动前，{@code SectionCompilerMixin} 用三张
 * {@code ConcurrentHashMap<BlockState|Block, Integer>} 记住一个 state 的
 * tracked-glass marker / occlusion fill / translucent marker。三张表都是
 * "值只由这个 state 决定"的纯函数表，却要按体素付一次哈希 + Integer 装箱。
 *
 * <p>这里在 {@code BlockStateBase} 上挂一个 int 字段（约 2.5 万个 state x 4B），
 * 把三份数据按位打包进同一个字，读写就是一次字段访问。位布局与语义归
 * {@code SectionCompilerMixin.rtStateCache()} 所有，本类只负责存储。
 *
 * <p>{@code volatile}：section 编译跑在多个 worker 线程上，首个命中的线程算完
 * 写回，其它线程必须看到；两边同时算的结果按构造相同（纯函数），所以不需要锁。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class BlockStateRtCacheMixin implements RtStateCache {

    @Unique
    private volatile int rt$stateCache;

    @Override
    public int rt$getStateCache() {
        return this.rt$stateCache;
    }

    @Override
    public void rt$setStateCache(int value) {
        this.rt$stateCache = value;
    }
}
