package net.fissionmc.backend.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.dispenser.OptionalDispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;

/** Paper: DispenseItemBehavior.java.patch helpers - "Call BlockDispenseEvent" for the dispenser behaviours registered in DispenseItemBehavior#bootStrap. */
public final class PaperDispense {
    private PaperDispense() {
    }

    public static BlockPos target(final BlockSource source) {
        return source.pos().relative(source.state().getValue(DispenserBlock.FACING));
    }

    /** @return the stack to return from execute (event cancelled or rerouted), or null to continue with the vanilla behaviour */
    public static @Nullable ItemStack fire(final BlockSource source, final ItemStack dispensed, final Object behavior) {
        return CraftEventFactory.handleBlockDispenseEvent(source, target(source), dispensed, (DispenseItemBehavior) behavior);
    }

    /** {@code this.setSuccess(false)} for the OptionalDispenseItemBehavior subclasses. */
    public static void fail(final Object behavior) {
        if (behavior instanceof OptionalDispenseItemBehavior optional) {
            optional.setSuccess(false);
        }
    }
}
