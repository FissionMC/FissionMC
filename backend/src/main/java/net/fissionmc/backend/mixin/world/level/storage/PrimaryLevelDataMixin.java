package net.fissionmc.backend.mixin.world.level.storage;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/storage/PrimaryLevelData.java.patch */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataMixin {
    @Shadow private LevelSettings settings;

    private static final String PAPER_RESPAWN_DIMENSION = "paperSpawnDimension"; // Paper
    private ResourceKey<Level> respawnDimension = Level.OVERWORLD; // Paper
    // TODO(behaviour): codec/write hunks persist respawnDimension under PAPER_RESPAWN_DIMENSION

    // CraftBukkit start
    public void checkName(String name) {
        if (!this.settings.levelName().equals(name)) {
            this.settings = this.settings.withLevelName(name);
        }
    }
    // CraftBukkit end
}
