package net.fissionmc.backend.mixin.world.level.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Paper's PersistentEntitySectionManager patch reads EntityStorage#level (private) for the EntitiesLoadEvent / EntitiesUnloadEvent. */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessorMixin {
    @Accessor("level")
    ServerLevel fission$level();
}
