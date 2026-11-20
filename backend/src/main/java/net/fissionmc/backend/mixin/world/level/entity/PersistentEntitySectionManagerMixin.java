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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.entity.ChunkEntities;

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

    // Paper - EntitiesLoadEvent / EntitiesUnloadEvent
    private boolean fission$callUnloadEvent;

    @Inject(method = "processPendingLoads", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;", shift = At.Shift.AFTER))
    private void fission$entitiesLoadEvent(final CallbackInfo ci, @Local final ChunkEntities<T> loadedChunk) {
        // CraftBukkit start - call entity load event
        final List<Entity> entities = this.getEntities(loadedChunk.getPos());
        org.bukkit.craftbukkit.event.CraftEventFactory.callEntitiesLoadEvent(((EntityStorageAccessorMixin) this.permanentStorage).fission$level(), loadedChunk.getPos(), entities);
        // CraftBukkit end
    }

    @Inject(method = "processChunkUnload", at = @At("HEAD"))
    private void fission$unloadStart(final long chunkKey, final CallbackInfoReturnable<Boolean> cir) {
        this.fission$callUnloadEvent = true;
    }

    @Inject(method = "processChunkUnload", at = @At("RETURN"))
    private void fission$unloadEnd(final long chunkKey, final CallbackInfoReturnable<Boolean> cir) {
        this.fission$callUnloadEvent = false;
    }

    // empty chunk case
    @Inject(method = "storeChunkSections", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;storeEntities(Lnet/minecraft/world/level/entity/ChunkEntities;)V", ordinal = 0))
    private void fission$unloadEventEmpty(final long chunkPos, final java.util.function.Consumer<T> visitor, final CallbackInfoReturnable<Boolean> cir) {
        if (this.fission$callUnloadEvent) {
            org.bukkit.craftbukkit.event.CraftEventFactory.callEntitiesUnloadEvent(((EntityStorageAccessorMixin) this.permanentStorage).fission$level(), ChunkPos.unpack(chunkPos), com.google.common.collect.ImmutableList.of()); // CraftBukkit
        }
    }

    // non-empty chunk case
    @Inject(method = "storeChunkSections", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;storeEntities(Lnet/minecraft/world/level/entity/ChunkEntities;)V", ordinal = 1))
    private void fission$unloadEventEntities(final long chunkPos, final java.util.function.Consumer<T> visitor, final CallbackInfoReturnable<Boolean> cir, @Local final List<T> rootEntitiesToSave) {
        if (this.fission$callUnloadEvent) {
            org.bukkit.craftbukkit.event.CraftEventFactory.callEntitiesUnloadEvent(((EntityStorageAccessorMixin) this.permanentStorage).fission$level(), ChunkPos.unpack(chunkPos),
                rootEntitiesToSave.stream().map(entity -> (Entity) entity).collect(Collectors.toList())); // CraftBukkit
        }
    }
}
