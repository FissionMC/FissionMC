package net.fissionmc.backend.mixin.world.level.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/entity/EntitySectionStorage.java.patch */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    @Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;

    // Paper start - support retrieving all entities, regardless of whether they are accessible
    public Iterable<T> getAllEntities() {
        java.util.List<T> ret = new java.util.ArrayList<>();
        for (EntitySection<T> section : this.sections.values()) {
            section.getEntities().forEach(ret::add); // FissionMC: Paper adds EntitySection#getEntities(List)
        }
        return ret;
    }
    // Paper end - support retrieving all entities, regardless of whether they are accessible
}
