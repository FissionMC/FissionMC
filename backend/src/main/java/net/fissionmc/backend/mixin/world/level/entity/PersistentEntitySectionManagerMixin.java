package net.fissionmc.backend.mixin.world.level.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/entity/PersistentEntitySectionManager.java.patch */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;
    @Shadow @Final private Long2ObjectMap<?> chunkLoadStatuses; // FissionMC: element type is package-private (ChunkLoadStatus)
    @Shadow @Final private EntityPersistentStorage<T> permanentStorage;
    @Shadow public abstract void saveAll();

    // CraftBukkit start - add method to get all entities in chunk
    public List<Entity> getEntities(ChunkPos chunkPos) {
        return this.sectionStorage.getExistingSectionsInChunk(chunkPos.pack()).flatMap(EntitySection::getEntities).map(entity -> (Entity) entity).collect(Collectors.toList());
    }

    public boolean isPending(long pair) {
        final Object status = this.chunkLoadStatuses.get(pair);
        return status != null && ((Enum<?>) status).name().equals("PENDING"); // FissionMC: == ChunkLoadStatus.PENDING (package-private enum)
    }
    // CraftBukkit end

    // CraftBukkit start - add save boolean
    // TODO(behaviour): vanilla close() -> close(true)
    public void close(boolean save) throws IOException {
        if (save) this.saveAll();
        // CraftBukkit end
        this.permanentStorage.close();
    }
}
