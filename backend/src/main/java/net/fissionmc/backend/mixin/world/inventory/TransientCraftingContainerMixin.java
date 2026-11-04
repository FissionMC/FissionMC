package net.fissionmc.backend.mixin.world.inventory;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/inventory/TransientCraftingContainer.java.patch
 * TODO(behaviour): Paper's TransientCraftingContainer(menu, width, height, Player) constructor sets owner, and
 * AbstractCraftingMenu sets resultInventory; neither is wired yet (Mixin cannot add constructors).
 */
@Mixin(TransientCraftingContainer.class)
public abstract class TransientCraftingContainerMixin {
    @Shadow @Final private NonNullList<ItemStack> items;
    @Shadow @Final private AbstractContainerMenu menu;

    // CraftBukkit start - add fields
    private List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private net.minecraft.world.item.crafting.@org.jspecify.annotations.Nullable RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> currentRecipe;
    private net.minecraft.world.Container resultInventory;
    private Player owner;
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;

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

    public org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder getOwner() {
        return (this.owner == null) ? null : this.owner.getBukkitEntity();
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
        this.resultInventory.setMaxStackSize(size);
    }

    public org.bukkit.Location getLocation() {
        return this.menu instanceof CraftingMenu ? ((CraftingMenu) this.menu).access.getLocation() : this.owner.getBukkitEntity().getLocation();
    }

    public net.minecraft.world.item.crafting.@org.jspecify.annotations.Nullable RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> getCurrentRecipe() {
        return this.currentRecipe;
    }

    public void setCurrentRecipe(net.minecraft.world.item.crafting.@org.jspecify.annotations.Nullable RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> currentRecipe) {
        this.currentRecipe = currentRecipe;
    }
    // CraftBukkit end
}
