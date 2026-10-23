package com.example.client.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;


/**
 * 2026-10-21 Lithium 式 section 级查询的入口。
 *
 * <p>{@code RenderSectionRegion} 是 3x3x3 的邻域快照，只公开
 * {@code getBlockState}；要回答"这一段的 palette 里有没有玻璃/发光/非整方块"
 * 必须拿到 {@code SectionCopy} 本身，因此这里把私有方法暴露出来。
 *
 * <p>参数语义（按 26.3 字节码核对）：{@code getSection(x, y, z)} 收的是
 * <b>绝对 section 坐标</b>，内部做
 * {@code index = (x-minX) + (y-minY)*3 + (z-minZ)*9}，不是相对偏移。
 */
@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionAccessor {

    @Invoker("getSection")
    SectionCopy invokeGetSection(int sectionX, int sectionY, int sectionZ);
}
