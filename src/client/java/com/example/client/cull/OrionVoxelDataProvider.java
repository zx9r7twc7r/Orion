package com.example.client.cull;

import com.example.client.cull.util.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 遮挡剔除的**体素数据源**：把原版世界回答成 {@link DataProvider} 要的两种问题。
 *
 * <p>来源与授权：接口与算法来自
 * <a href="https://github.com/LogisticsCraft/OcclusionCulling">LogisticsCraft/OcclusionCulling</a>
 * （已按作者授权移植，见 {@code com.example.client.cull} 下的文件头），
 * 编排策略参考 <a href="https://github.com/tr7zw/">tr7zw/</a>。
 * 本类是我们的适配层，不含第三方逻辑。
 *
 * <p>性能约定：{@code prepareChunk} 在**同一区块被连续询问
 * 时不得重复解析**，所以我们缓存最后一次命中的区块引用。
 */
public final class OrionVoxelDataProvider implements DataProvider {

    private LevelChunk cachedChunk;
    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;

    @Override
    public boolean prepareChunk(int chunkX, int chunkZ) {
        if(cachedChunk != null && chunkX == cachedChunkX && chunkZ == cachedChunkZ)
        {
            return true;
        }
        final Level level = Minecraft.getInstance().level;
        if(level == null || !level.hasChunk(chunkX, chunkZ))
        {
            cachedChunk = null;
            cachedChunkX = Integer.MIN_VALUE;
            cachedChunkZ = Integer.MIN_VALUE;
            return false;
        }
        cachedChunk = level.getChunk(chunkX, chunkZ);
        cachedChunkX = chunkX;
        cachedChunkZ = chunkZ;
        return true;
    }

    @Override
    public boolean isOpaqueFullCube(int x, int y, int z) {
        if(y < -64 || y > 319)
        {
            return false;
        }
        final Level level = Minecraft.getInstance().level;
        if(level == null)
        {
            return false;
        }
        final BlockPos pos = new BlockPos(x, y, z);
        final BlockState state = level.getBlockState(pos);
        return state.canOcclude();
    }

    @Override
    public void cleanup() {
        cachedChunk = null;
        cachedChunkX = Integer.MIN_VALUE;
        cachedChunkZ = Integer.MIN_VALUE;
    }

    @Override
    public void checkingPosition(Vec3d[] targetPoints, int size, Vec3d viewerPosition) {
    }
}
