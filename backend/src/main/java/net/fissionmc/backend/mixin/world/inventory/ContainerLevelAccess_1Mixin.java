package net.fissionmc.backend.mixin.world.inventory;

import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/inventory/ContainerLevelAccess.java.patch (ContainerLevelAccess.NULL) */
@Mixin(targets = "net.minecraft.world.inventory.ContainerLevelAccess$1")
public abstract class ContainerLevelAccess_1Mixin {
    // Paper start - fix menus with empty level accesses
    public org.bukkit.Location getLocation() {
        return null;
    }
    // Paper end - fix menus with empty level accesses
}
