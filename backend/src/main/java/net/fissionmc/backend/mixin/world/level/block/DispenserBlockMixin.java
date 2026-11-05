package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.blocks.DispenserBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/block/DispenserBlock.java.patch
 * Done: getDispenseBehavior, BlockFailedDispenseEvent, BlockPreDispenseEvent. TODO(behaviour): useWithoutItem openMenu().isPresent() (needs ServerPlayer#openMenu result).
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin implements DispenserBlockAccess {
    // Paper start - Fix NPE with equippable and items without behavior
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static DispenseItemBehavior getDispenseBehavior(BlockSource pointer, ItemStack stack) {
        return ((DispenserBlockAccess) pointer.state().getBlock()).fission$getDispenseMethod(pointer.level(), stack);
    }
    // Paper end - Fix NPE with equippable and items without behavior

    @org.spongepowered.asm.mixin.Shadow
    protected abstract DispenseItemBehavior getDispenseMethod(final Level level, final ItemStack itemStack);

    @Override
    public DispenseItemBehavior fission$getDispenseMethod(final Level level, final ItemStack itemStack) {
        return this.getDispenseMethod(level, itemStack);
    }

    // Paper - Add BlockFailedDispenseEvent (skips levelEvent + gameEvent when cancelled)
    @Inject(method = "dispenseFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;levelEvent(ILnet/minecraft/core/BlockPos;I)V", ordinal = 0), cancellable = true)
    private void fission$failedDispense(final ServerLevel level, final BlockState state, final BlockPos pos, final CallbackInfo ci) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFailedDispenseEvent(level, pos)) {
            ci.cancel();
        }
    }

    // Paper - Add BlockPreDispenseEvent
    @Inject(method = "dispenseFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;dispense(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;", ordinal = 0), cancellable = true)
    private void fission$preDispense(final ServerLevel level, final BlockState state, final BlockPos pos, final CallbackInfo ci, @Local(name = "slot") int slot, @Local(name = "itemStack") ItemStack itemStack) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockPreDispenseEvent(level, pos, itemStack, slot)) ci.cancel();
    }
}
