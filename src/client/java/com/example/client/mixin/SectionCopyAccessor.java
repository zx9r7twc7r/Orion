package com.example.client.mixin;

import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


/**
 * 2026-10-21 拿到 section 编译快照的 palette 容器。
 *
 * <p>{@code SectionCopy.section} 是
 * {@code LevelChunkSection.getStates().copy()}；为 {@code null} 表示该区块是
 * {@code EmptyLevelChunk}（即整段空气，见 SectionCopy 构造与 getBlockState 的
 * 空判断）。有了它就能用 {@code forEachInPalette} / {@code maybeHas} 做
 * O(palette) 的前置判据，而不用扫 4096 格。
 */
@Mixin(SectionCopy.class)
public interface SectionCopyAccessor {

    @Accessor("section")
    PalettedContainer<BlockState> getSectionPalette();
}
