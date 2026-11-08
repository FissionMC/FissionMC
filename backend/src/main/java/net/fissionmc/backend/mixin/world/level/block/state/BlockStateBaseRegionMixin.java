package net.fissionmc.backend.mixin.world.level.block.state;

import net.fissionmc.backend.relay.BorderForwardBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC static regions: every neighbour update funnels through handleNeighborChanged. For a block in a chunk another backend owns (a ghost copy here) the update is not run on the
 * ghost, it is sent to the owner, which runs it on its authoritative copy. New file.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseRegionMixin {
    @Inject(method = "handleNeighborChanged", at = @At("HEAD"), cancellable = true)
    private void fission$forwardGhostNeighbor(Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston, CallbackInfo ci) {
        if (BorderForwardBackend.forwardNeighbor(level, pos, block)) {
            ci.cancel();
        }
    }
}
