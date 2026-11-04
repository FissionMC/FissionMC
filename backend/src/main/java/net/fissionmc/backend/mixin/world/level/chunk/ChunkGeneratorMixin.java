package net.fissionmc.backend.mixin.world.level.chunk;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/chunk/ChunkGenerator.java.patch */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Shadow public abstract void applyBiomeDecoration(final WorldGenLevel level, final ChunkAccess chunk, final StructureManager structureManager);

    // CraftBukkit start
    // TODO(behaviour): Paper renames vanilla applyBiomeDecoration(3) to addVanillaDecorations and makes it call this with true (block populators)
    public void applyBiomeDecoration(
        final WorldGenLevel level, final ChunkAccess chunk, final StructureManager structureManager, final boolean addVanillaDecorations
    ) {
        if (addVanillaDecorations) {
            this.applyBiomeDecoration(level, chunk, structureManager); // FissionMC: = Paper's addVanillaDecorations
        }

        org.bukkit.World world = level.getMinecraftWorld().getWorld();
        // only call when a populator is present (prevents unnecessary entity conversion)
        if (!world.getPopulators().isEmpty()) {
            org.bukkit.craftbukkit.generator.CraftLimitedRegion limitedRegion = new org.bukkit.craftbukkit.generator.CraftLimitedRegion(level, chunk.getPos());
            int x = chunk.getPos().x();
            int z = chunk.getPos().z();
            for (org.bukkit.generator.BlockPopulator populator : world.getPopulators()) {
                WorldgenRandom seededrandom = new WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(level.getSeed()));
                seededrandom.setDecorationSeed(level.getSeed(), x, z);
                populator.populate(world, new org.bukkit.craftbukkit.util.RandomSourceWrapper.RandomWrapper(seededrandom), x, z, limitedRegion);
            }
            limitedRegion.saveEntities();
            limitedRegion.breakLink();
        }
    }
    // CraftBukkit end
}
