package net.fissionmc.backend.blocks;

import java.util.function.Supplier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import org.bukkit.inventory.BlockInventoryHolder;

/** Paper: Add missing InventoryHolders - AbstractContainerMenu#createBlockHolder plus applying it to a menu's containers. */
public final class PaperInventoryHolders {
    private PaperInventoryHolders() {
    }

    /** AbstractContainerMenu#createBlockHolder(context): lazy, because the holder needs the menu's bukkit view. */
    public static Supplier<BlockInventoryHolder> createBlockHolder(final AbstractContainerMenu menu, final ContainerLevelAccess context) {
        return () -> context.createBlockHolder(menu);
    }

    /** Gives each container (SimpleContainer / ResultContainer) the lazily created block holder of the menu. */
    public static void apply(final AbstractContainerMenu menu, final ContainerLevelAccess access, final Object... containers) {
        if (access == null) {
            return;
        }
        for (final Object container : containers) {
            if (container instanceof BlockHolderTarget target) {
                target.fission$setHolderCreator(createBlockHolder(menu, access));
            }
        }
    }
}
