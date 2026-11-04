package net.fissionmc.backend.mixin.world;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/SimpleContainer.java.patch
 * TODO(behaviour): Paper's constructors SimpleContainer(SimpleContainer), SimpleContainer(Supplier, int) and
 * SimpleContainer(int, InventoryHolder) (which set bukkitOwner / bukkitOwnerCreator) cannot be added by Mixin.
 */
@Mixin(SimpleContainer.class)
public abstract class SimpleContainerMixin {
    @Shadow @Final private NonNullList<ItemStack> items;

    // Paper start - add fields and methods
    private List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;
    private org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder bukkitOwner; // Paper - annotation // FissionMC: protected in Paper; Mixin requires private (no subclass uses it yet)
    // Paper start - Add missing InventoryHolders
    private java.util.function.@org.jspecify.annotations.Nullable Supplier<? extends org.bukkit.inventory.InventoryHolder> bukkitOwnerCreator;
    // Paper end - Add missing InventoryHolders

    public List<ItemStack> getContents() {
        return this.items;
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
    }

    public List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    public org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder getOwner() {
        // Paper start - Add missing InventoryHolders
        if (this.bukkitOwner == null && this.bukkitOwnerCreator != null) {
            this.bukkitOwner = this.bukkitOwnerCreator.get();
        }
        // Paper end - Add missing InventoryHolders
        return this.bukkitOwner;
    }

    public org.bukkit.@org.jspecify.annotations.Nullable Location getLocation() {
        // Paper start - Fix inventories returning null Locations
        // When the block inventory does not have a tile state that implements getLocation, e. g. composters
        if (this.bukkitOwner instanceof org.bukkit.inventory.BlockInventoryHolder blockInventoryHolder) {
            return blockInventoryHolder.getBlock().getLocation();
        }
        // When the bukkit owner is a bukkit entity, but does not implement Container itself, e. g. horses
        if (this.bukkitOwner instanceof org.bukkit.entity.Entity entity) {
            return entity.getLocation();
        }
        // Paper end - Fix inventories returning null Locations
        return null;
    }
    // Paper end
}
