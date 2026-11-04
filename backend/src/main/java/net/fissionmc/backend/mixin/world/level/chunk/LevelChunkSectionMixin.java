package net.fissionmc.backend.mixin.world.level.chunk;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/chunk/LevelChunkSection.java.patch */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin {
    @Shadow private PalettedContainerRO<Holder<Biome>> biomes;

    // CraftBukkit start
    public void setNoiseBiome(int quartX, int quartY, int quartZ, Holder<Biome> biome) {
        // FissionMC: Paper retypes the field to PalettedContainer (read/write); copy a read-only container on first write
        // TODO(behaviour): Paper's constructors keep the biome container writable
        if (!(this.biomes instanceof PalettedContainer<Holder<Biome>>)) {
            this.biomes = this.biomes.copy();
        }
        ((PalettedContainer<Holder<Biome>>) this.biomes).set(quartX, quartY, quartZ, biome);
    }
    // CraftBukkit end
}
