package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/block/DispenserBlock.java.patch */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    // Paper start - Fix NPE with equippable and items without behavior
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static DispenseItemBehavior getDispenseBehavior(BlockSource pointer, ItemStack stack) {
        return ((DispenserBlockMixin) (Object) pointer.state().getBlock()).getDispenseMethod(pointer.level(), stack);
    }
    // Paper end - Fix NPE with equippable and items without behavior

    @org.spongepowered.asm.mixin.Shadow
    protected abstract DispenseItemBehavior getDispenseMethod(final Level level, final ItemStack itemStack);
}
