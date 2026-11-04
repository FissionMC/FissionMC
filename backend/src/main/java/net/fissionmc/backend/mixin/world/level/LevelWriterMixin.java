package net.fissionmc.backend.mixin.world.level;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelWriter;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/LevelWriter.java.patch */
@Mixin(LevelWriter.class)
public interface LevelWriterMixin {
    // CraftBukkit start
    default boolean addFreshEntity(Entity entity, org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason reason) {
        return false;
    }
    // CraftBukkit end
}
