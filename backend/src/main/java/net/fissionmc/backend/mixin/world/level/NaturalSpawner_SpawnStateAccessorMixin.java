package net.fissionmc.backend.mixin.world.level;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** FissionMC: access for Paper's canSpawnForCategoryGlobal(category, limit) (NaturalSpawner.java.patch). */
@Mixin(NaturalSpawner.SpawnState.class)
public interface NaturalSpawner_SpawnStateAccessorMixin {
    @Accessor("spawnableChunkCount")
    int fission$spawnableChunkCount();

    @Accessor("mobCategoryCounts")
    Object2IntOpenHashMap<MobCategory> fission$mobCategoryCounts();
}
