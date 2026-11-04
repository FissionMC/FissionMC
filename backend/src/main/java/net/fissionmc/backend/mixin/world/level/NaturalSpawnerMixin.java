package net.fissionmc.backend.mixin.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/NaturalSpawner.java.patch */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Shadow @Final private static int MAGIC_NUMBER;

    // Paper start - Add mobcaps commands
    private static int globalLimitForCategory(final ServerLevel level, final MobCategory category, final int spawnableChunkCount) {
        final int categoryLimit = level.getWorld().getSpawnLimitUnsafe(org.bukkit.craftbukkit.util.CraftSpawnCategory.toBukkit(category));
        if (categoryLimit < 1) {
            return categoryLimit;
        }
        return categoryLimit * spawnableChunkCount / MAGIC_NUMBER;
    }
    // Paper end - Add mobcaps commands
}
