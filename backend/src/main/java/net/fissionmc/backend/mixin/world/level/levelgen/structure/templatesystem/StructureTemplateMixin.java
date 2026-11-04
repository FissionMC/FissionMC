package net.fissionmc.backend.mixin.world.level.levelgen.structure.templatesystem;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java.patch */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {
    // CraftBukkit start
    private static final org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry();
    private org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer persistentDataContainer = new org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer(DATA_TYPE_REGISTRY);
    // CraftBukkit end
    // TODO(behaviour): save/load (PDC), placeInWorld (TransformerGeneratorAccess) hunks
}
