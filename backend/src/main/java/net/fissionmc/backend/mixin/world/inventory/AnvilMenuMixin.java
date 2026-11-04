package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/inventory/AnvilMenu.java.patch
 * TODO(behaviour): createResult uses DEFAULT_DENIED_COST / maximumRepairCost / bypassEnchantmentLevelRestriction,
 * PrepareAnvilEvent, getBukkitView (CraftAnvilView).
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    // CraftBukkit start
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private static final int DEFAULT_DENIED_COST = -1;
    private int maximumRepairCost = 40;
    // CraftBukkit end
    private boolean bypassEnchantmentLevelRestriction = false; // Paper - bypass anvil level restrictions
}
