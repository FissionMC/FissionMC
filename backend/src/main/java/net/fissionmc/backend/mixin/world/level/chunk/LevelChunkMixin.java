package net.fissionmc.backend.mixin.world.level.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/chunk/LevelChunk.java.patch */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow public abstract BlockState getBlockState(BlockPos pos);
    @Shadow public abstract FluidState getFluidState(BlockPos pos);

    // CraftBukkit start
    private boolean mustNotSave;
    private boolean needsDecoration;
    // CraftBukkit end
    // TODO(behaviour): load/unload callbacks (ChunkLoadEvent/ChunkUnloadEvent, mustNotSave, addLoadedChunk/removeLoadedChunk), isUnsaved hunk

    // Paper start - If loaded util
    public final FluidState getFluidIfLoaded(BlockPos pos) {
        return this.getFluidState(pos);
    }

    public final BlockState getBlockStateIfLoaded(BlockPos pos) {
        return this.getBlockState(pos);
    }
    // Paper end
}
