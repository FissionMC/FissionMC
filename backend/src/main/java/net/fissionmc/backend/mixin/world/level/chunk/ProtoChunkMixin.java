package net.fissionmc.backend.mixin.world.level.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/chunk/ProtoChunk.java.patch */
@Mixin(ProtoChunk.class)
public abstract class ProtoChunkMixin {
    @Shadow public abstract BlockState getBlockState(BlockPos pos);
    @Shadow public abstract FluidState getFluidState(BlockPos pos);

    // Paper start - If loaded util
    public final FluidState getFluidIfLoaded(BlockPos pos) {
        return this.getFluidState(pos);
    }

    public final BlockState getBlockStateIfLoaded(BlockPos pos) {
        return this.getBlockState(pos);
    }
    // Paper end
}
