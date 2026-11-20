package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: BuddingAmethystBlock.java.patch - Have Amethyst throw both spread (small bud) and grow events. */
@Mixin(BuddingAmethystBlock.class)
public abstract class BuddingAmethystBlockMixin {
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$growOrSpread(final ServerLevel level, final BlockPos growPos, final BlockState targetState, final Operation<Boolean> original, @Local(argsOnly = true) final BlockPos pos) {
        if (targetState.is(Blocks.SMALL_AMETHYST_BUD)) {
            return CraftEventFactory.handleBlockSpreadEvent(level, pos, growPos, targetState, Block.UPDATE_ALL); // CraftBukkit
        }
        return CraftEventFactory.handleBlockGrowEvent(level, growPos, targetState, Block.UPDATE_ALL);
    }
}
