package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.blocks.PaperGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: BambooStalkBlock.java.patch - BlockSpreadEvent for the new stalk. The two leaf updates of the blocks below are moved behind
 * the event (executed only when it was not cancelled); calls 0 and 1 of growBamboo's setBlockAndUpdate are deferred, call 2 is the stalk.
 */
@Mixin(BambooStalkBlock.class)
public abstract class BambooStalkBlockMixin {
    @WrapOperation(method = "growBamboo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 0))
    private boolean paper$deferBelow(final Level level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        PaperGrowth.bambooBelowPos = pos;
        PaperGrowth.bambooBelowState = state;
        return true;
    }

    @WrapOperation(method = "growBamboo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 1))
    private boolean paper$deferTwoBelow(final Level level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        PaperGrowth.bambooTwoBelowPos = pos;
        PaperGrowth.bambooTwoBelowState = state;
        return true;
    }

    @WrapOperation(method = "growBamboo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 2))
    private boolean paper$spread(final Level level, final BlockPos target, final BlockState state, final Operation<Boolean> original, @Local(argsOnly = true) final BlockPos pos) {
        final BlockPos belowPos = PaperGrowth.bambooBelowPos;
        final BlockState belowState = PaperGrowth.bambooBelowState;
        final BlockPos twoBelowPos = PaperGrowth.bambooTwoBelowPos;
        final BlockState twoBelowState = PaperGrowth.bambooTwoBelowState;
        PaperGrowth.bambooBelowPos = null;
        PaperGrowth.bambooBelowState = null;
        PaperGrowth.bambooTwoBelowPos = null;
        PaperGrowth.bambooTwoBelowState = null;
        // CraftBukkit start
        if (CraftEventFactory.handleBlockSpreadEvent(level, pos, target, state, Block.UPDATE_ALL)) {
            if (belowPos != null && belowState != null && twoBelowPos != null && twoBelowState != null) {
                level.setBlock(belowPos, belowState, Block.UPDATE_ALL);
                level.setBlock(twoBelowPos, twoBelowState, Block.UPDATE_ALL);
            }
            return true;
        }
        return false;
        // CraftBukkit end
    }

    // CraftBukkit - If the BlockSpreadEvent was cancelled, we have no bamboo here: bonemeal must not read STAGE of a non-bamboo block
    @WrapOperation(method = "performBonemeal", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"))
    private Comparable<?> paper$stageOfNonBamboo(final BlockState topState, final Property<?> property, final Operation<Comparable<?>> original) {
        if (!topState.is(net.minecraft.world.level.block.Blocks.BAMBOO)) {
            return 1; // makes the vanilla `STAGE == 1` check return, like Paper's !topState.is(BAMBOO) condition
        }
        return original.call(topState, property);
    }
}
