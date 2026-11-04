package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.java.patch */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
    @Shadow protected net.minecraft.core.NonNullList<ItemStack> items;

    // CraftBukkit start - add fields and methods
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;
    private double cookSpeedMultiplier = 1.0; // Paper - cook speed multiplier API

    public java.util.List<ItemStack> getContents() {
        return this.items;
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

    // TODO(behaviour): load/save "Paper.CookSpeedMultiplier", FurnaceBurnEvent/StartSmelt/Smelt events, cookSpeedMultiplier in cook time
}
