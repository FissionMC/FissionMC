package net.fissionmc.backend.mixin.core.dispenser;

import net.fissionmc.backend.blocks.PaperDispense;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: DispenseItemBehavior.java.patch - BlockDispenseEvent for the anonymous behaviour DispenseItemBehavior$6. */
@Mixin(targets = "net.minecraft.core.dispenser.DispenseItemBehavior$6")
public abstract class DispenseItemBehavior_6Mixin {
    @Inject(method = "execute", at = @At(value = "NEW", target = "net/minecraft/world/entity/item/PrimedTnt"), cancellable = true)
    private void paper$dispenseEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack result = PaperDispense.fire(source, dispensed, this);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
