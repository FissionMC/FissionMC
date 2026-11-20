package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: BellBlock.java.patch - BlockRedstoneEvent for the powered state and the bell ring event. */
@Mixin(BellBlock.class)
public abstract class BellBlockMixin {
    // Paper - Call BlockRedstoneEvent: a cancelled change reports the unchanged signal, so neither ringing nor the POWERED update happens
    @ModifyExpressionValue(method = "neighborChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean paper$redstoneEvent(final boolean signal, @Local(argsOnly = true) final BlockState state, @Local(argsOnly = true) final Level level, @Local(argsOnly = true) final BlockPos pos) {
        if (signal != state.getValue(BellBlock.POWERED) && !CraftEventFactory.callBinaryRedstoneChange(level, pos, signal)) {
            return !signal;
        }
        return signal;
    }

    // CraftBukkit - BellRingEvent
    @Inject(method = "attemptToRing(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BellBlockEntity;onHit(Lnet/minecraft/core/Direction;)V"), cancellable = true)
    private void paper$ringEvent(final Entity ringingEntity, final Level level, final BlockPos pos, final Direction direction, final CallbackInfoReturnable<Boolean> cir) {
        if (!CraftEventFactory.handleBellRingEvent(level, pos, direction, ringingEntity)) {
            cir.setReturnValue(false);
        }
    }
}
