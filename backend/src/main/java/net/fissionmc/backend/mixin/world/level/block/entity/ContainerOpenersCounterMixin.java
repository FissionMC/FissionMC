package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/ContainerOpenersCounter.java.patch
 * TODO(behaviour): recheckOpeners counts `opened` as a dummy opener; BlockRedstoneEvent for trapped chests;
 * decrementOpeners never goes negative.
 */
@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterMixin {
    @Shadow protected abstract void onOpen(final Level level, final BlockPos pos, final BlockState blockState);
    @Shadow protected abstract void onClose(final Level level, final BlockPos pos, final BlockState blockState);
    @Shadow protected abstract void openerCountChanged(final Level level, final BlockPos pos, final BlockState blockState, final int previous, final int current);

    // CraftBukkit start
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private boolean opened;

    public void onOpenAPI(Level level, BlockPos pos, BlockState blockState) {
        this.onOpen(level, pos, blockState);
    }

    public void onCloseAPI(Level level, BlockPos pos, BlockState blockState) {
        this.onClose(level, pos, blockState);
    }

    public void openerCountChangedAPI(Level level, BlockPos pos, BlockState blockState, int previous, int current) {
        this.openerCountChanged(level, pos, blockState, previous, current);
    }
    // CraftBukkit end
}
