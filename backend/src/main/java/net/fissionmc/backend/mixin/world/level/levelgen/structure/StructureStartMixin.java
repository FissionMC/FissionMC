package net.fissionmc.backend.mixin.world.level.levelgen.structure;

import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/levelgen/structure/StructureStart.java.patch */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    // CraftBukkit start
    private static final org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry();
    private org.bukkit.craftbukkit.persistence.DirtyCraftPersistentDataContainer persistentDataContainer = new org.bukkit.craftbukkit.persistence.DirtyCraftPersistentDataContainer(DATA_TYPE_REGISTRY);
    private org.bukkit.event.world.AsyncStructureGenerateEvent.Cause generationEventCause = org.bukkit.event.world.AsyncStructureGenerateEvent.Cause.WORLD_GENERATION;
    // CraftBukkit end
    // TODO(behaviour): createTag/loadStaticStart/placeInChunk hunks (PDC persistence, generationEventCause)
}
