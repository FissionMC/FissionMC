package net.fissionmc.backend.mixin.core.dispenser;

import net.fissionmc.backend.blocks.PaperDispense;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.FlintAndSteelDispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: FlintAndSteelDispenseItemBehavior.java.patch - BlockDispenseEvent for fire / campfire / candle lighting and BlockIgniteEvent for fire.
 * TODO(behaviour): the TNT branch (BlockDispenseEvent + TNTPrimeEvent through TntBlock.prime(..., BooleanSupplier)) and the sulfur cube
 * branch are not ported; a cancelled event skips the tool damage here while Paper still damages it only on success (same).
 */
@Mixin(FlintAndSteelDispenseItemBehavior.class)
public abstract class FlintAndSteelDispenseItemBehaviorMixin {
    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 0), cancellable = true)
    private void paper$fireEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack result = PaperDispense.fire(source, dispensed, this);
        if (result != null) {
            PaperDispense.fail(this);
            cir.setReturnValue(result);
            return;
        }
        // CraftBukkit - Ignition by dispensing flint and steel
        if (CraftEventFactory.callBlockIgniteEvent(source.level(), PaperDispense.target(source), source.pos()).isCancelled()) {
            cir.setReturnValue(dispensed);
        }
    }

    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 1), cancellable = true)
    private void paper$lightEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack result = PaperDispense.fire(source, dispensed, this);
        if (result != null) {
            PaperDispense.fail(this);
            cir.setReturnValue(result);
        }
    }
}
