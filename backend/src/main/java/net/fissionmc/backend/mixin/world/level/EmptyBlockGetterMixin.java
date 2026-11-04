package net.fissionmc.backend.mixin.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/EmptyBlockGetter.java.patch */
@Mixin(EmptyBlockGetter.class)
public abstract class EmptyBlockGetterMixin {
    // Paper start - If loaded util
    public final FluidState getFluidIfLoaded(BlockPos pos) {
        return Fluids.EMPTY.defaultFluidState();
    }

    public final BlockState getBlockStateIfLoaded(BlockPos pos) {
        return Blocks.AIR.defaultBlockState();
    }
    // Paper end
}
