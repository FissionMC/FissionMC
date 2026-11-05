package net.fissionmc.backend.blocks;

import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Implemented by DispenserBlock through DispenserBlockMixin; gives mixins access to the protected getDispenseMethod. */
public interface DispenserBlockAccess {
    DispenseItemBehavior fission$getDispenseMethod(Level level, ItemStack itemStack);
}
