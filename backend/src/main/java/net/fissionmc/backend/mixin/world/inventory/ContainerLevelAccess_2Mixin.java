package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/inventory/ContainerLevelAccess.java.patch (ContainerLevelAccess.create) */
@Mixin(targets = "net.minecraft.world.inventory.ContainerLevelAccess$2")
public abstract class ContainerLevelAccess_2Mixin {
    @Shadow @Final private Level val$level;
    @Shadow @Final private BlockPos val$pos;

    // CraftBukkit start
    public Level getWorld() {
        return this.val$level;
    }

    public BlockPos getPosition() {
        return this.val$pos;
    }
    // CraftBukkit end
    // Paper start - Add missing InventoryHolders
    public boolean isBlock() {
        return true;
    }
    // Paper end - Add missing InventoryHolders
}
