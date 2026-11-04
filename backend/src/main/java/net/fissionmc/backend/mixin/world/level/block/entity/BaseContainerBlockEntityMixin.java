package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BaseContainerBlockEntity.java.patch
 * TODO(behaviour): canOpen calls BlockLockCheckEvent, sendChestLockedNotifications checks the event.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin {
    // CraftBukkit start
    public org.bukkit.@Nullable Location getLocation() {
        final BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() == null) return null;
        return org.bukkit.craftbukkit.util.CraftLocation.toBukkit(self.getBlockPos(), self.getLevel());
    }
    // CraftBukkit end
}
