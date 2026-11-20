package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnifferEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: SnifferEggBlock.java.patch - BlockGrowEvent (crack) and BlockFadeEvent (hatch); a cancelled event reschedules the tick. */
@Mixin(SnifferEggBlock.class)
public abstract class SnifferEggBlockMixin {
    // Paper - Call BlockFadeEvent: rescheduleTick (spawn delay config not ported; vanilla 24000/12000 ticks)
    private void paper$rescheduleTick(final ServerLevel level, final BlockPos pos) {
        final int baseDelay = SnifferEggBlock.hatchBoost(level, pos) ? 12000 : 24000;
        level.scheduleTick(pos, (Block) (Object) this, (baseDelay / 3) + level.getRandom().nextInt(300));
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean paper$grow(final ServerLevel level, final BlockPos pos, final BlockState newState, final int flags, final Operation<Boolean> original) {
        if (!CraftEventFactory.handleBlockGrowEvent(level, pos, newState, flags)) {
            this.paper$rescheduleTick(level, pos);
            return false;
        }
        return true;
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"), cancellable = true)
    private void paper$fade(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        if (CraftEventFactory.callBlockFadeEvent(level, pos, state.getFluidState().createLegacyBlock()).isCancelled()) {
            this.paper$rescheduleTick(level, pos);
            ci.cancel();
        }
    }
}
