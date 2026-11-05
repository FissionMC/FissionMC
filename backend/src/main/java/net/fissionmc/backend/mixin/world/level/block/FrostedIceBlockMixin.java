package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/FrostedIceBlock.java.patch (BlockFadeEvent; config: frosted-ice options default = vanilla) */
@Mixin(FrostedIceBlock.class)
public abstract class FrostedIceBlockMixin {
    @Inject(method = "slightlyMelt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 0), cancellable = true)
    private void fission$fade(final BlockState state, final Level level, final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        int age = state.getValue(FrostedIceBlock.AGE);
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callBlockFadeEvent(level, pos, state.setValue(FrostedIceBlock.AGE, age + 1)).isCancelled()) {
            cir.setReturnValue(false);
        }
    }
}
