package net.fissionmc.backend.mixin.world.level.block;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/ChestBlock.java.patch
 * TODO(behaviour): getMenuProvider(state, level, pos) delegates here with ignoreObstructions=false (same result),
 * MENU_PROVIDER_COMBINER wraps the CompoundContainer for identification.
 */
@Mixin(ChestBlock.class)
public abstract class ChestBlockMixin {
    @Shadow @Final private static DoubleBlockCombiner.Combiner<ChestBlockEntity, Optional<MenuProvider>> MENU_PROVIDER_COMBINER;

    @Shadow public abstract DoubleBlockCombiner.NeighborCombineResult<? extends ChestBlockEntity> combine(BlockState state, Level level, BlockPos pos, boolean ignoreBeingBlocked);

    // CraftBukkit start
    public @Nullable MenuProvider getMenuProvider(final BlockState state, final Level level, final BlockPos pos, final boolean ignoreObstructions) {
        return this.combine(state, level, pos, ignoreObstructions).apply(MENU_PROVIDER_COMBINER).orElse(null);
    }
    // CraftBukkit end
}
