package net.fissionmc.backend.mixin.world.level.material;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/material/FlowingFluid.java.patch
 * Done: BlockFromToEvent (down + sides), FluidLevelChangeEvent.
 * TODO(behaviour): "Prevent chunk loading from fluid flowing" (getBlockStateIfLoaded in spreadToSides/getSlopeDistance/getSpread + SpreadContext),
 * beforeDestroyingBlock(…, source) BlockBreakBlockEvent. A cancelled FluidLevelChangeEvent skips the block update but not the follow-up spread().
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    // spread(): downward flow
    @Inject(method = "spread", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V", ordinal = 0), cancellable = true)
    private void fission$fromToDown(final ServerLevel level, final BlockPos pos, final BlockState state, final FluidState fluidState, final CallbackInfo ci) {
        org.bukkit.block.Block source = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
        org.bukkit.event.block.BlockFromToEvent event = new org.bukkit.event.block.BlockFromToEvent(source, org.bukkit.block.BlockFace.DOWN);
        level.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    // spreadToSides()
    @WrapOperation(method = "spreadToSides", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void fission$fromToSide(final FlowingFluid self, final LevelAccessor level, final BlockPos neighborPos, final BlockState neighborState, final Direction spread, final FluidState newNeighborFluid, final Operation<Void> original) {
        final BlockPos pos = neighborPos.relative(spread.getOpposite());
        org.bukkit.block.Block source = org.bukkit.craftbukkit.block.CraftBlock.at((net.minecraft.world.level.LevelAccessor) level, pos);
        org.bukkit.event.block.BlockFromToEvent event = new org.bukkit.event.block.BlockFromToEvent(source, org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(spread));
        ((ServerLevel) level).getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return;
        }
        original.call(self, level, neighborPos, neighborState, spread, newNeighborFluid);
    }

    // tick(): FluidLevelChangeEvent
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$fluidLevelChange(final ServerLevel level, final BlockPos pos, BlockState blockState, final Operation<Boolean> original) {
        org.bukkit.event.block.FluidLevelChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callFluidLevelChangeEvent(level, pos, blockState);
        if (event.isCancelled()) {
            return false;
        }
        blockState = ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getNewData()).getState();
        return original.call(level, pos, blockState);
    }
}
