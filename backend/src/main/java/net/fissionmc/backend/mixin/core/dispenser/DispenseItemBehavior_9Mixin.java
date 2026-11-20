package net.fissionmc.backend.mixin.core.dispenser;

import net.fissionmc.backend.blocks.PaperDispense;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: DispenseItemBehavior.java.patch - BlockDispenseEvent for the glass bottle (honey from a beehive, water from a water block). */
@Mixin(targets = "net.minecraft.core.dispenser.DispenseItemBehavior$9")
public abstract class DispenseItemBehavior_9Mixin {
    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BeehiveBlock;releaseBeesAndResetHoneyLevel(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$BeeReleaseStatus;)V"), cancellable = true)
    private void paper$honeyEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack result = PaperDispense.fire(source, dispensed, this);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/alchemy/PotionContents;createItemStack(Lnet/minecraft/world/item/Item;Lnet/minecraft/core/Holder;)Lnet/minecraft/world/item/ItemStack;"), cancellable = true)
    private void paper$waterEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack result = PaperDispense.fire(source, dispensed, this);
        if (result != null) {
            PaperDispense.fail(this); // Paper fires the event before setSuccess(true)
            cir.setReturnValue(result);
        }
    }
}
