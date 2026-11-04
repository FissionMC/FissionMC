package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/inventory/MerchantContainer.java.patch */
@Mixin(MerchantContainer.class)
public abstract class MerchantContainerMixin {
    @Shadow @Final private Merchant merchant;
    @Shadow @Final private NonNullList<ItemStack> itemStacks;

    // CraftBukkit start - add fields and methods
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;

    public java.util.List<ItemStack> getContents() {
        return this.itemStacks;
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
        this.merchant.setTradingPlayer(null); // SPIGOT-4860
    }

    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    public org.bukkit.inventory.@Nullable InventoryHolder getOwner() {
        return (this.merchant instanceof net.minecraft.world.entity.npc.villager.AbstractVillager villager) ? (org.bukkit.craftbukkit.entity.CraftAbstractVillager) villager.getBukkitEntity() : null;
    }

    public org.bukkit.@Nullable Location getLocation() {
        return (this.merchant instanceof net.minecraft.world.entity.npc.villager.AbstractVillager villager) ? villager.getBukkitEntity().getLocation() : null; // Paper - Fix inventories returning null Locations
    }
    // CraftBukkit end
}
