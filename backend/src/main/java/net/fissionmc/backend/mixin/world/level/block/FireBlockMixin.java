package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/level/block/FireBlock.java.patch
 * Done: updateShape BlockFadeEvent, fireExtinguished in tick, BlockIgniteEvent/BlockSpreadEvent, BlockBurnEvent, TNTPrimeEvent.
 * config: fire-tick-delay (default equals vanilla). TODO(behaviour): onPlace(context).
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Shadow protected abstract boolean canSurvive(BlockState state, LevelReader level, BlockPos pos);
    @Shadow protected abstract BlockState getStateWithAge(LevelReader level, BlockPos pos, int age);

    // CraftBukkit - source position of the fire being ticked (Paper passes it as checkBurnOut sourcePos)
    private BlockPos fission$sourcePos;

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void fission$updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction directionToNeighbour, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random, final CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerLevel)) return; // Paper - don't fire events in world generation
        if (!this.canSurvive(state, level, pos)) {
            if (!(level instanceof Level world)) {
                cir.setReturnValue(Blocks.AIR.defaultBlockState());
                return;
            }
            org.bukkit.craftbukkit.block.CraftBlockState snapshot = org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(world, pos);
            snapshot.setBlock(Blocks.AIR.defaultBlockState());

            org.bukkit.event.block.BlockFadeEvent event = new org.bukkit.event.block.BlockFadeEvent(snapshot.getBlock(), snapshot);
            world.getCraftServer().getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                cir.setReturnValue(snapshot.getHandle());
                return;
            }
        }
        cir.setReturnValue(this.getStateWithAge(level, pos, state.getValue(FireBlock.AGE)));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fission$tickHead(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        this.fission$sourcePos = pos;
    }

    // level.removeBlock(pos, false) -> this.fireExtinguished(level, pos) // CraftBukkit
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean fission$extinguish(final ServerLevel level, final BlockPos pos, final boolean movedByPiston, final Operation<Boolean> original) {
        ((BaseFireBlock) (Object) this).fireExtinguished(level, pos);
        return true;
    }

    // spread: BlockIgniteEvent + BlockSpreadEvent
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$spread(final ServerLevel level, final BlockPos testPos, final BlockState newState, final Operation<Boolean> original) {
        final BlockPos pos = this.fission$sourcePos;
        if (!level.getBlockState(testPos).is(Blocks.FIRE)) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(level, testPos, pos).isCancelled()) {
                return false;
            }
            return org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockSpreadEvent(level, pos, testPos, newState, Block.UPDATE_ALL); // CraftBukkit
        }
        return false;
    }

    // BlockBurnEvent before the burn roll (after `random.nextInt(chance) < odds`)
    @Inject(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I", ordinal = 1), cancellable = true)
    private void fission$burnEvent(final Level level, final BlockPos pos, final int chance, final RandomSource random, final int age, final CallbackInfo ci) {
        final BlockPos sourcePos = this.fission$sourcePos == null ? pos : this.fission$sourcePos;
        BlockState oldState = level.getBlockState(pos);

        org.bukkit.block.Block burnBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
        org.bukkit.block.Block sourceBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, sourcePos);

        org.bukkit.event.block.BlockBurnEvent event = new org.bukkit.event.block.BlockBurnEvent(burnBlock, sourceBlock);
        if (!event.callEvent()) {
            ci.cancel();
            return;
        }

        if (oldState.getBlock() instanceof TntBlock && !org.bukkit.craftbukkit.event.CraftEventFactory.callTNTPrimeEvent(level, pos, org.bukkit.event.block.TNTPrimeEvent.PrimeCause.FIRE, null, sourcePos)) {
            ci.cancel();
        }
    }

    // Paper - TNTPrimeEvent; We might be cancelling it below, move the setAir down
    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean fission$delayTntRemove(final Level level, final BlockPos pos, final boolean movedByPiston, final Operation<Boolean> original) {
        if (level.getBlockState(pos).is(Blocks.TNT)) return false;
        return original.call(level, pos, movedByPiston);
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/TntBlock;prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean fission$primeTnt(final Level level, final BlockPos pos, final Operation<Boolean> original) {
        org.bukkit.block.Block tntBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
        if (!new com.destroystokyo.paper.event.block.TNTPrimeEvent(tntBlock, com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE, null).callEvent()) {
            return false;
        }
        level.removeBlock(pos, false);
        return original.call(level, pos);
    }
}
