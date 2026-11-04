package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/entity/ShulkerBoxBlockEntity.java.patch */
@Mixin(ShulkerBoxBlockEntity.class)
public abstract class ShulkerBoxBlockEntityMixin {
    @Shadow private net.minecraft.core.NonNullList<ItemStack> itemStacks;

    // CraftBukkit start - add fields and methods
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;
    private boolean opened;

    public java.util.List<ItemStack> getContents() {
        return this.itemStacks;
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
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
    // CraftBukkit end

    // TODO(behaviour): startOpen/stopOpen return early when `opened` is set (only animate if not forced open by API)
}
