package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/LecternBlockEntity.java.patch (bookAccess, Paper's LecternInventory)
 * TODO(behaviour): setItem(0, stack) sets the book and resets the lectern block state; getMaxStackSize returns maxStack;
 * LecternBlockEntity's CraftBukkit CommandSource and createMenu passing the player inventory.
 */
@Mixin(targets = "net.minecraft.world.level.block.entity.LecternBlockEntity$1")
public abstract class LecternBlockEntity_1Mixin implements net.fissionmc.craft.compat.LecternInventory {
    @Shadow @Final private LecternBlockEntity this$0;

    // CraftBukkit start - add fields and methods
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = 1;

    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getContents() {
        return java.util.List.of(this.this$0.getBook());
    }

    @Override
    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    @Override
    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
    }

    @Override
    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    @Override
    public org.bukkit.@Nullable Location getLocation() {
        if (this.this$0.getLevel() == null) return null;
        return org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.this$0.getBlockPos(), this.this$0.getLevel());
    }

    @Override
    public org.bukkit.inventory.InventoryHolder getOwner() {
        return this.this$0.getOwner();
    }

    @Override
    public LecternBlockEntity getLectern() {
        return this.this$0;
    }
    // CraftBukkit end
}
