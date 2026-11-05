package net.fissionmc.backend.mixin.server.level;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity area: only the {@code valid}/{@code inWorld} flags of Paper's ServerLevel.EntityCallbacks hunks
 * (paper-server/patches/sources/net/minecraft/server/level/ServerLevel.java.patch). Many Entity hunks (teleport, canTeleport,
 * mount/dismount/air/swim events) depend on them. The rest of those hunks (origin, EntityAddToWorldEvent, onEntityRemove,
 * inventory closing, ...) belongs to the server area; when that area ports the callbacks this mixin can be dropped.
 */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class ServerLevel_EntityCallbacks_entityMixin {
    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void fission$markValid(Entity entity, CallbackInfo ci) {
        entity.inWorld = true; // CraftBukkit - Mark entity as in world
        entity.valid = true; // CraftBukkit
    }

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void fission$markInvalid(Entity entity, CallbackInfo ci) {
        entity.valid = false; // CraftBukkit
    }
}
