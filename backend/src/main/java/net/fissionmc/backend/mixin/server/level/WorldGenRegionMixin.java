package net.fissionmc.backend.mixin.server.level;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/level/WorldGenRegion.java.patch */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Shadow public abstract @Nullable ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus, boolean loadOrGenerate);
    @Shadow public abstract boolean addFreshEntity(Entity entity);

    // Paper start - if loaded util
    public @Nullable ChunkAccess getChunkIfLoadedImmediately(int x, int z) {
        return this.getChunk(x, z, ChunkStatus.FULL, false);
    }

    public final @Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
        ChunkAccess chunk = this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    public final @Nullable FluidState getFluidIfLoaded(BlockPos pos) {
        ChunkAccess chunk = this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getFluidState(pos);
    }
    // Paper end

    // CraftBukkit start
    public boolean addFreshEntity(final Entity entity, final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        // CraftBukkit end
        return this.addFreshEntity(entity); // FissionMC: identical to the vanilla body (Paper moves it here)
    }
}
