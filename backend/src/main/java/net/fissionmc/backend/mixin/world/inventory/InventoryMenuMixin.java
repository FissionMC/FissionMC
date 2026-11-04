package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/inventory/InventoryMenu.java.patch
 * TODO(behaviour): constructor passes the inventory to the crafting container and sets the title "container.crafting".
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin extends AbstractCraftingMenu {
    @Shadow @Final private Player owner;

    private org.bukkit.craftbukkit.inventory.@Nullable CraftInventoryView view = null; // CraftBukkit

    private InventoryMenuMixin() {
        super(null, 0, 0, 0);
    }

    // CraftBukkit start
    @Override
    public org.bukkit.craftbukkit.inventory.CraftInventoryView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryCrafting inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryCrafting(this.craftSlots, this.resultSlots);
        this.view = new org.bukkit.craftbukkit.inventory.CraftInventoryView(this.owner.getBukkitEntity(), inventory, this);
        return this.view;
    }

    @Override
    public void forceHeldSlot(final net.minecraft.world.InteractionHand hand) {
        // If ever needed, a config option for instead synchronizing the full inventory can be added here to call this.sendAllDataToRemote();
        // Otherwise, only resync the hand slot
        final int slot = hand == net.minecraft.world.InteractionHand.MAIN_HAND ? this.owner.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
        this.forceSlot(this.owner.getInventory(), slot);
    }

    @Override
    public void forceHeldSlotAndArmor(final net.minecraft.world.InteractionHand hand) {
        this.forceHeldSlot(hand);

        final int size = net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE;
        final net.minecraft.world.entity.player.Inventory inventory = this.owner.getInventory();
        this.forceSlot(inventory, net.minecraft.world.entity.EquipmentSlot.FEET.getIndex(size));
        this.forceSlot(inventory, net.minecraft.world.entity.EquipmentSlot.LEGS.getIndex(size));
        this.forceSlot(inventory, net.minecraft.world.entity.EquipmentSlot.CHEST.getIndex(size));
        this.forceSlot(inventory, net.minecraft.world.entity.EquipmentSlot.HEAD.getIndex(size));
    }
    // CraftBukkit end
}
