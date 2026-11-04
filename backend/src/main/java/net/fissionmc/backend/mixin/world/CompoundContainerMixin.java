package net.fissionmc.backend.mixin.world;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/CompoundContainer.java.patch
 * TODO(behaviour): getMaxStackSize returns Math.min of both sides (CraftBukkit - check both sides).
 */
@Mixin(CompoundContainer.class)
public abstract class CompoundContainerMixin {
    @Shadow @Final private Container container1;
    @Shadow @Final private Container container2;

    @Shadow public abstract int getContainerSize();
    @Shadow public abstract ItemStack getItem(int slot);

    // CraftBukkit start - add fields and methods
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();

    public java.util.List<ItemStack> getContents() {
        java.util.List<ItemStack> result = new java.util.ArrayList<>(this.getContainerSize());
        for (int i = 0; i < this.getContainerSize(); i++) {
            result.add(this.getItem(i));
        }
        return result;
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.container1.onOpen(player);
        this.container2.onOpen(player);
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.container1.onClose(player);
        this.container2.onClose(player);
        this.transaction.remove(player);
    }

    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    public org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder getOwner() {
        return null; // This method won't be called since CraftInventoryDoubleChest doesn't defer to here
    }

    public void setMaxStackSize(int size) {
        this.container1.setMaxStackSize(size);
        this.container2.setMaxStackSize(size);
    }

    public org.bukkit.Location getLocation() {
        return this.container1.getLocation(); // TODO: right?
    }
    // CraftBukkit end
}
