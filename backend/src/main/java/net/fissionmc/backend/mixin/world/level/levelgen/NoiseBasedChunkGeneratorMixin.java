package net.fissionmc.backend.mixin.world.level.levelgen;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java.patch */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;
    @Shadow public abstract CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager, BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes);

    public CompletableFuture<ChunkAccess> buildTerrain(
        final ChunkAccess chunk,
        final Blender blender,
        final RandomState randomState,
        final StructureManager structureManager,
        final BiomeManager biomeManager,
        final @Nullable WorldGenRegion carverBiomeRegion,
        final Set<Holder<Biome>> possibleBiomes,
        final org.bukkit.craftbukkit.generator.@Nullable CustomChunkGenerator customGenerator
    ) {
        // Paper end - Bukkit chunk generator callbacks
        if (customGenerator == null) {
            return this.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
        }
        NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        // Paper start - Bukkit chunk generator callbacks
        if (noiseSettings.height() <= 0 || SharedConstants.debugVoidTerrain(chunk.getPos())) {
            return CompletableFuture.supplyAsync(() -> {
                customGenerator.fillFromNoise(chunk);
                customGenerator.buildSurface(chunk);
                customGenerator.applyCarvers(chunk);
                return chunk;
            }, io.papermc.paper.FeatureHooks.getWorldgenExecutor());
        }
        // Paper end - Bukkit chunk generator callbacks
        // TODO(behaviour): Paper interleaves the callbacks with doFill/buildSurface/generateCarvers inside the vanilla body and
        //  honours shouldGenerateNoise/Surface/Caves; here they run after the vanilla stages.
        return this.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes).thenApply(built -> {
            customGenerator.fillFromNoise(built);
            customGenerator.buildSurface(built);
            customGenerator.applyCarvers(built);
            return built;
        });
    }
}
