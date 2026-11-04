package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/inventory/ContainerLevelAccess.java.patch */
@Mixin(ContainerLevelAccess.class)
public interface ContainerLevelAccessMixin {
    // CraftBukkit start
    default Level getWorld() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    default BlockPos getPosition() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    default org.bukkit.Location getLocation() {
        return org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.getPosition(), this.getWorld());
    }
    // CraftBukkit end
    // Paper start - Add missing InventoryHolders
    default boolean isBlock() {
        return false;
    }

    default org.bukkit.inventory.@org.jspecify.annotations.Nullable BlockInventoryHolder createBlockHolder(AbstractContainerMenu menu) {
        if (!this.isBlock()) {
            return null;
        }
        return new org.bukkit.craftbukkit.inventory.CraftBlockInventoryHolder((ContainerLevelAccess) this, menu.getBukkitView().getTopInventory());
    }
    // Paper end - Add missing InventoryHolders
}
