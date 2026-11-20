package net.fissionmc.backend.mixin.world.level.redstone;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: NeighborUpdater.java.patch / CollectingNeighborUpdater.java.patch - BlockPhysicsEvent (with source block) before handleNeighborChanged. */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$FullNeighborUpdate")
public abstract class CollectingNeighborUpdater_FullNeighborUpdateMixin {
    @WrapOperation(method = "runNext", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeUpdate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V"))
    private void paper$physics(final Level level, final BlockState state, final BlockPos pos, final Block block, final Orientation orientation, final boolean movedByPiston, final Operation<Void> original) {
        if (PaperPhysics.physicsCancelled(level, state, pos, pos)) {
            return;
        }
        original.call(level, state, pos, block, orientation, movedByPiston);
    }
}
