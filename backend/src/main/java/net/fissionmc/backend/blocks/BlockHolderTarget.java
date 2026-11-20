package net.fissionmc.backend.blocks;

import java.util.function.Supplier;
import org.bukkit.inventory.InventoryHolder;

/**
 * Duck interface implemented (via Mixin) by SimpleContainer and ResultContainer: stands in for Paper's added constructors
 * SimpleContainer(Supplier, int) / ResultContainer(Supplier) of the "Add missing InventoryHolders" patch, which Mixin cannot add.
 */
public interface BlockHolderTarget {
    void fission$setHolderCreator(Supplier<? extends InventoryHolder> creator);
}
