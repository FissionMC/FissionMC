package net.fissionmc.backend.mixin.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/PathNavigationRegion.java.patch */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin {
    @Shadow @Final protected int centerX;
    @Shadow @Final protected int centerZ;
    @Shadow @Final protected ChunkAccess[][] chunks;

    // Paper start - if loaded util
    private @Nullable ChunkAccess getChunkIfLoaded(int x, int z) {
        // Based on getChunk(int, int)
        int xx = x - this.centerX;
        int zz = z - this.centerZ;

        if (xx >= 0 && xx < this.chunks.length && zz >= 0 && zz < this.chunks[xx].length) {
            return this.chunks[xx][zz];
        }
        return null;
    }

    public final @Nullable FluidState getFluidIfLoaded(BlockPos pos) {
        ChunkAccess chunk = getChunkIfLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getFluidState(pos);
    }

    public final @Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
        ChunkAccess chunk = getChunkIfLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }
    // Paper end
}
