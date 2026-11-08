package net.fissionmc.backend.mixin.world.level.entity;

import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FissionMC static regions: exactly one backend owns an entity, the owner of the chunk it stands in. The entities stored with a chunk of ANOTHER backend's region (the chunk is only a ghost here) are
 * the owner's, not ours: loading them made a second, frozen copy that this backend's players saw (a lit TNT popping up again every time the viewer switched backends) and whose stale state
 * the backend pushed back to the master. They are skipped when they come from disk or from world generation; entities that appear at run time in foreign territory (an item dropped by a player
 * who breaks a block across the border) are real and are handed over to the owner by RegionsBackend.handOverStrays. New file.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerRegionMixin {
    @Inject(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At("HEAD"), cancellable = true)
    private void fission$noForeignEntities(EntityAccess access, boolean loadedFromDisk, CallbackInfoReturnable<Boolean> cir) {
        if (!loadedFromDisk || !(access instanceof Entity entity) || entity instanceof Player) return;
        if (!RegionsBackend.ownsEntityAtLoad(entity)) {
            RegionsBackend.foreignEntitiesSkipped.incrementAndGet();
            cir.setReturnValue(false);
        }
    }
}
