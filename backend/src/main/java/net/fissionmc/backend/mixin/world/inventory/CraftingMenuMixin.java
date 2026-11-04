package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/inventory/CraftingMenu.java.patch
 * TODO(behaviour): slotChangedCraftingGrid current-recipe caching + PrepareItemCraftEvent, checkReachable in stillValid.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin extends AbstractCraftingMenu {
    @Shadow @Final private Player player;

    private org.bukkit.craftbukkit.inventory.@Nullable CraftInventoryView view = null; // CraftBukkit

    private CraftingMenuMixin() {
        super(null, 0, 0, 0);
    }

    // CraftBukkit start
    @Override
    public org.bukkit.craftbukkit.inventory.CraftInventoryView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryCrafting inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryCrafting(this.craftSlots, this.resultSlots);
        this.view = new org.bukkit.craftbukkit.inventory.CraftInventoryView(this.player.getBukkitEntity(), inventory, this);
        return this.view;
    }
    // CraftBukkit end
}
