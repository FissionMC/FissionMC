package net.fissionmc.backend.entitytypes;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Paper - pass custom pickup item: Paper changes {@code AbstractArrow.tryPickup(Player)} to
 * {@code tryPickup(Player, ItemStack)}. The vanilla methods (and the ThrownTrident override) read the
 * custom item from here while {@code playerTouch} runs them.
 */
public final class PickupItem {
    public static final ThreadLocal<@Nullable ItemStack> CURRENT = new ThreadLocal<>();

    private PickupItem() {
    }

    public static ItemStack orElse(final ItemStack fallback) {
        final ItemStack custom = CURRENT.get();
        return custom != null ? custom : fallback;
    }
}
