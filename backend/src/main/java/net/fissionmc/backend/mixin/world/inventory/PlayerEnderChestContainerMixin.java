package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/inventory/PlayerEnderChestContainer.java.patch
 * TODO(behaviour): Paper's PlayerEnderChestContainer(Player owner) constructor and getOwner() returning the owner's
 * Bukkit entity need the owner captured where Player creates its ender chest inventory.
 */
@Mixin(PlayerEnderChestContainer.class)
public abstract class PlayerEnderChestContainerMixin {
    @Shadow private @Nullable EnderChestBlockEntity activeChest;

    public org.bukkit.@Nullable Location getLocation() {
        return this.activeChest != null ? org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.activeChest.getBlockPos(), this.activeChest.getLevel()) : null;
    }
}
