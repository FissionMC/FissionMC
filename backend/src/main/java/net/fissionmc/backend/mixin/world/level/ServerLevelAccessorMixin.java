package net.fissionmc.backend.mixin.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/ServerLevelAccessor.java.patch */
@Mixin(ServerLevelAccessor.class)
public interface ServerLevelAccessorMixin {
    // CraftBukkit start
    // TODO(behaviour): vanilla addFreshEntityWithPassengers(Entity) -> Paper delegates with SpawnReason.DEFAULT
    default void addFreshEntityWithPassengers(Entity entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        entity.getSelfAndPassengers().forEach((e) -> ((ServerLevelAccessor) this).addFreshEntity(e, reason));
    }

    default ServerLevel getMinecraftWorld() {
        return ((ServerLevelAccessor) this).getLevel();
    }
    // CraftBukkit end
}
