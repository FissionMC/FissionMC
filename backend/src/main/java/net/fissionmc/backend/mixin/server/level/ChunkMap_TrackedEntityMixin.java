package net.fissionmc.backend.mixin.server.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/level/ChunkMap.java.patch (TrackedEntity) */
@Mixin(ChunkMap.TrackedEntity.class)
public abstract class ChunkMap_TrackedEntityMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final ChunkMap this$0;

    @Inject(method = "removePlayer", at = @At("HEAD"))
    private void paper$asyncRemovePlayer(final ServerPlayer player, final CallbackInfo ci) {
        org.spigotmc.AsyncCatcher.catchOp("player tracker clear"); // Spigot
    }

    @Inject(method = "updatePlayer", at = @At("HEAD"))
    private void paper$asyncUpdatePlayer(final ServerPlayer player, final CallbackInfo ci) {
        org.spigotmc.AsyncCatcher.catchOp("player tracker update"); // Spigot
    }

    @ModifyVariable(method = "updatePlayer", at = @At("STORE"), ordinal = 0)
    private boolean paper$visibleToPlayer(boolean visibleToPlayer, @Local(argsOnly = true) final ServerPlayer player) {
        final net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) this.entity.level();
        // Paper start - Configurable entity tracking range by Y
        if (visibleToPlayer && level.paperConfig().entities.trackingRangeY.enabled) {
            double rangeY = level.paperConfig().entities.trackingRangeY.get(this.entity, -1);
            if (rangeY != -1) {
                double deltaToPlayerY = player.getY() - this.entity.getY();
                visibleToPlayer = deltaToPlayerY * deltaToPlayerY <= rangeY * rangeY;
            }
        }
        // Paper end - Configurable entity tracking range by Y
        // CraftBukkit start - respect vanish API
        if (visibleToPlayer && !player.getBukkitEntity().canSee(this.entity.getBukkitEntity())) { // Paper - only consider hits
            visibleToPlayer = false;
        }
        // CraftBukkit end
        return visibleToPlayer;
    }

    // Paper start - entity tracking events
    // FissionMC: a cancelled PlayerTrackEntityEvent keeps the connection in seenBy but skips the pairing, as in Paper
    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private boolean paper$trackEntityEvent(final Set<Object> seenBy, final Object connection, final Operation<Boolean> original, @Local(argsOnly = true) final ServerPlayer player) {
        if (!original.call(seenBy, connection)) {
            return false;
        }
        return io.papermc.paper.event.player.PlayerTrackEntityEvent.getHandlerList().getRegisteredListeners().length == 0 || new io.papermc.paper.event.player.PlayerTrackEntityEvent(player.getBukkitEntity(), this.entity.getBukkitEntity()).callEvent();
    }
    // Paper end - entity tracking events

    @ModifyVariable(method = "getEffectiveRange", at = @At("STORE"), ordinal = 1)
    private int paper$passengerTrackingRange(final int passengerRange, @Local(ordinal = 0) final Entity passenger) {
        return org.spigotmc.TrackingRange.getEntityTrackingRange(passenger, passengerRange); // Paper
    }
    // TODO(behaviour): seenBy as ReferenceOpenHashSet (perf), ServerEntity(..., seenBy) constructor argument (ServerEntity patch)
}
