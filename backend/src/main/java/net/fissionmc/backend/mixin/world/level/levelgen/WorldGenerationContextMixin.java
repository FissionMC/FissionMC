package net.fissionmc.backend.mixin.world.level.levelgen;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/levelgen/WorldGenerationContext.java.patch */
@Mixin(WorldGenerationContext.class)
public abstract class WorldGenerationContextMixin {
    // TODO(behaviour): Paper sets this through an added (ChunkGenerator, LevelHeightAccessor, Level) constructor (SurfaceSystem / NoiseBasedChunkGenerator)
    private @org.jspecify.annotations.Nullable Level level; // Paper - Flat bedrock generator settings

    // Paper start - Flat bedrock generator settings
    public Level level() {
        if (this.level == null) {
            throw new NullPointerException("WorldGenerationContext was initialized without a Level, but WorldGenerationContext#level was called");
        }
        return this.level;
    }
    // Paper end - Flat bedrock generator settings
}
