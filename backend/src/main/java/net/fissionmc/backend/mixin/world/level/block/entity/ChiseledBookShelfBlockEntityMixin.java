package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/entity/ChiseledBookShelfBlockEntity.java.patch */
@Mixin(ChiseledBookShelfBlockEntity.class)
public abstract class ChiseledBookShelfBlockEntityMixin {
    @Shadow @Final private net.minecraft.core.NonNullList<ItemStack> items;

    // CraftBukkit start - add fields and methods
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = 1;

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

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    public org.bukkit.@Nullable Location getLocation() {
        final net.minecraft.world.level.block.entity.BlockEntity self = (net.minecraft.world.level.block.entity.BlockEntity) (Object) this;
        if (self.getLevel() == null) return null;
        return org.bukkit.craftbukkit.util.CraftLocation.toBukkit(self.getBlockPos(), self.getLevel());
    }
    // CraftBukkit end
}
