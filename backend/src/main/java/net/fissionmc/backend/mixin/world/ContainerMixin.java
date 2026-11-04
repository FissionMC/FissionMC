package net.fissionmc.backend.mixin.world;

import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/Container.java.patch
 *
 * <p>Paper declares these members abstract and implements them in every vanilla container. FissionMC adds them as
 * defaults here and overrides them in the per-class mixins (as Paper's per-class patches do); the defaults are only
 * reached by containers that have no ported override yet.
 * TODO(behaviour): Paper makes getMaxStackSize() abstract and adds {@code int MAX_STACK = Item.ABSOLUTE_MAX_STACK_SIZE}
 * (Mixin cannot add interface fields; ported code uses Item.ABSOLUTE_MAX_STACK_SIZE instead).
 */
@Mixin(Container.class)
public interface ContainerMixin {
    // CraftBukkit start
    default List<ItemStack> getContents() {
        throw new UnsupportedOperationException("FissionMC: Container#getContents not ported for " + this.getClass().getName());
    }

    default void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        throw new UnsupportedOperationException("FissionMC: Container#onOpen not ported for " + this.getClass().getName());
    }

    default void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        throw new UnsupportedOperationException("FissionMC: Container#onClose not ported for " + this.getClass().getName());
    }

    default List<org.bukkit.entity.HumanEntity> getViewers() {
        throw new UnsupportedOperationException("FissionMC: Container#getViewers not ported for " + this.getClass().getName());
    }

    default org.bukkit.inventory.@Nullable InventoryHolder getOwner() {
        throw new UnsupportedOperationException("FissionMC: Container#getOwner not ported for " + this.getClass().getName());
    }

    default void setMaxStackSize(int size) {
        throw new UnsupportedOperationException("FissionMC: Container#setMaxStackSize not ported for " + this.getClass().getName());
    }

    default org.bukkit.@Nullable Location getLocation() {
        throw new UnsupportedOperationException("FissionMC: Container#getLocation not ported for " + this.getClass().getName());
    }
    // CraftBukkit end
}
