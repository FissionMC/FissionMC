package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/inventory/ResultContainer.java.patch
 * TODO(behaviour): Paper's ResultContainer(Supplier<InventoryHolder>) constructor (sets holderCreator) cannot be added by Mixin.
 */
@Mixin(ResultContainer.class)
public abstract class ResultContainerMixin {
    @Shadow @Final private NonNullList<ItemStack> itemStacks;

    // CraftBukkit start
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;

    public java.util.List<ItemStack> getContents() {
        return this.itemStacks;
    }

    public org.bukkit.inventory.@Nullable InventoryHolder getOwner() {
        // Paper start - Add missing InventoryHolders
        if (this.holder == null && this.holderCreator != null) {
            this.holder = this.holderCreator.get();
        }
        return this.holder; // Result slots don't get an owner
        // Paper end - Add missing InventoryHolders
    }

    // Don't need a transaction; the CraftingContainer keeps track of it for us
    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {}
    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {}
    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return new java.util.ArrayList<>();
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    public org.bukkit.@Nullable Location getLocation() {
        return null;
    }
    // CraftBukkit end
    // Paper start - Add missing InventoryHolders
    private java.util.function.@Nullable Supplier<? extends org.bukkit.inventory.InventoryHolder> holderCreator;
    private org.bukkit.inventory.@Nullable InventoryHolder holder;
    // Paper end - Add missing InventoryHolders
}
