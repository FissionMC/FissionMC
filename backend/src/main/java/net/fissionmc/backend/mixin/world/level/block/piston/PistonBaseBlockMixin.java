package net.fissionmc.backend.mixin.world.level.block.piston;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/level/block/piston/PistonBaseBlock.java.patch
 * Done: BlockPistonExtend/RetractEvent (moveBlocks, retract paths), world border check.
 * config (default = vanilla-safe protection kept off): allowPermanentBlockBreakExploits / allowPistonDuplication.
 * TODO(behaviour): headless piston guard, bedrock protection in newMovingBlockEntity, dropResources(…, pistonPos) BlockBreakBlockEvent,
 * allowDesync=false block-state capture in the push loop.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @Shadow @Final private boolean isSticky;

    // Non-sticky piston retract: BlockPistonRetractEvent before replacing the piston with a moving piston
    @Inject(method = "triggerEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 2), cancellable = true)
    private void fission$retractEventNonSticky(final BlockState state, final Level level, final BlockPos pos, final int b0, final int b1, final CallbackInfoReturnable<Boolean> cir,
                                               @Local(name = "direction") Direction direction) {
        if (!this.isSticky) {
            if (!new org.bukkit.event.block.BlockPistonRetractEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), java.util.Collections.emptyList(), org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(direction)).callEvent()) {
                cir.setReturnValue(false);
            }
        }
    }

    // Sticky piston retracting with nothing to pull
    @Inject(method = "triggerEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z", ordinal = 0), cancellable = true)
    private void fission$retractEventAir(final BlockState state, final Level level, final BlockPos pos, final int b0, final int b1, final CallbackInfoReturnable<Boolean> cir,
                                         @Local(name = "direction") Direction direction, @Local(name = "movingState") BlockState movingState) {
        if (b0 == 1 && movingState.isAir()) { // TRIGGER_CONTRACT
            if (!new org.bukkit.event.block.BlockPistonRetractEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), java.util.Collections.emptyList(), org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(direction)).callEvent()) {
                cir.setReturnValue(false);
            }
        }
    }

    // BlockPistonExtendEvent / BlockPistonRetractEvent with the moved block list
    @Inject(method = "moveBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;getToDestroy()Ljava/util/List;", shift = At.Shift.AFTER), cancellable = true)
    private void fission$pistonEvent(final Level level, final BlockPos pistonPos, final Direction direction, final boolean extending, final CallbackInfoReturnable<Boolean> cir,
                                     @Local PistonStructureResolver resolver) {
        Direction pushDirection = extending ? direction : direction.getOpposite();
        final org.bukkit.block.Block bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, pistonPos);

        final List<BlockPos> moved = resolver.getToPush();
        final List<BlockPos> broken = resolver.getToDestroy();

        List<org.bukkit.block.Block> blocks = new net.fissionmc.backend.blocks.PistonBlockList(level, moved, broken);

        final org.bukkit.event.block.BlockPistonEvent event;
        if (extending) {
            event = new org.bukkit.event.block.BlockPistonExtendEvent(
                bukkitBlock, blocks, org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(pushDirection)
            );
        } else {
            event = new org.bukkit.event.block.BlockPistonRetractEvent(
                bukkitBlock, blocks, org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(pushDirection)
            );
        }
        if (!event.callEvent()) {
            for (BlockPos brokenPos : broken) {
                level.sendBlockUpdated(brokenPos, Blocks.AIR.defaultBlockState(), level.getBlockState(brokenPos), Block.UPDATE_ALL);
            }
            for (BlockPos movedPos : moved) {
                level.sendBlockUpdated(movedPos, Blocks.AIR.defaultBlockState(), level.getBlockState(movedPos), Block.UPDATE_ALL);
                movedPos = movedPos.relative(pushDirection);
                level.sendBlockUpdated(movedPos, Blocks.AIR.defaultBlockState(), level.getBlockState(movedPos), Block.UPDATE_ALL);
            }
            cir.setReturnValue(false);
        }
    }
}
