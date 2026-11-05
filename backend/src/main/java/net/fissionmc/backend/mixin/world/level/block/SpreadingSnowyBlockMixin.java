package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SpreadingSnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/level/block/SpreadingSnowyBlock.java.patch (BlockFadeEvent, BlockSpreadEvent; config: grassSpread tick rate) */
@Mixin(SpreadingSnowyBlock.class)
public abstract class SpreadingSnowyBlockMixin {
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 0))
    private boolean fission$fade(final ServerLevel level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callBlockFadeEvent(level, pos, state).isCancelled()) {
            return false;
        }
        return original.call(level, pos, state);
    }

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 1))
    private boolean fission$spread(final ServerLevel level, final BlockPos testPos, final BlockState state, final Operation<Boolean> original, @Local(name = "pos") final BlockPos pos) {
        return org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockSpreadEvent(level, pos, testPos, state, Block.UPDATE_ALL); // CraftBukkit
    }
}
