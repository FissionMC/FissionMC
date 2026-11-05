package net.fissionmc.backend.mixin.server.level;

import com.google.common.collect.Lists;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import java.util.Optional;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/level/ServerLevel.java.patch (EntityCallbacks) */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class ServerLevel_EntityCallbacksMixin {
    @Shadow @Final ServerLevel this$0;

    @Inject(method = "onCreated(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void paper$onCreated(final Entity entity, final CallbackInfo ci) {
        entity.setOldPosAndRot(); // Paper - update old pos / rot for new entities as it will default to Vec3.ZERO
    }

    @Inject(method = "onTickingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void paper$onTickingStart(final Entity entity, final CallbackInfo ci) {
        if (entity instanceof net.minecraft.world.entity.Marker && !this.this$0.paperConfig().entities.markers.tick) ci.cancel(); // Paper - Configurable marker ticking
    }

    @Inject(method = "onTickingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void paper$onTickingEnd(final Entity entity, final CallbackInfo ci) {
        // Paper start - Reset pearls when they stop being ticked
        if (this.this$0.paperConfig().fixes.disableUnloadedChunkEnderpearlExploit && this.this$0.paperConfig().misc.legacyEnderPearlBehavior && entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl) {
            pearl.setOwner(null);
        }
        // Paper end - Reset pearls when they stop being ticked
    }

    // Paper - ignore and warn about illegal addEntity calls instead of crashing server; moved down below valid=true
    @WrapWithCondition(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;addEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private boolean paper$moveAddEntity(final ServerChunkCache chunkSource, final Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity register"); // Spigot
        return false;
    }

    // Paper - Remove unnecessary onTrackingStart during navigation warning
    @ModifyExpressionValue(method = {"onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V"}, at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z"))
    private boolean paper$noNavigationWarning(final boolean isUpdatingNavigations) {
        return false;
    }

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void paper$onTrackingStart(final Entity entity, final CallbackInfo ci) {
        entity.inWorld = true; // CraftBukkit - Mark entity as in world
        entity.valid = true; // CraftBukkit
        this.this$0.getChunkSource().addEntity(entity); // Paper - ignore and warn about illegal addEntity calls instead of crashing server
        // Paper start - Entity origin API
        if (entity.origin == null) {
            entity.origin = entity.position();
        }
        // Default to current world if unknown, gross assumption but entities rarely change world
        if (entity.originWorld == null) {
            entity.originWorld = this.this$0.getWorld().getUID();
        }
        // Paper end - Entity origin API
        new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(entity.getBukkitEntity(), this.this$0.getWorld()).callEvent(); // Paper - fire while valid
    }

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void paper$onTrackingEndHead(final Entity entity, final CallbackInfo ci) {
        org.spigotmc.AsyncCatcher.catchOp("entity unregister"); // Spigot
        // Spigot start
        if (entity instanceof Player player) {
            SavedDataStorage storage = this.this$0.getServer().getDataStorage();
            for (final Optional<net.minecraft.world.level.saveddata.SavedData> savedData : storage.cache.values()) {
                if (savedData.isEmpty() || !(savedData.get() instanceof MapItemSavedData map)) {
                    continue;
                }

                MapItemSavedData.HoldingPlayer holder = map.carriedByPlayers.remove(player);
                if (holder != null && map.carriedBy.remove(holder)) {
                    map.decorations.remove(player.getName().getString());
                }
            }
        }
        // Spigot end
        // Spigot start
        if (entity.getBukkitEntity() instanceof org.bukkit.inventory.InventoryHolder && (!(entity instanceof ServerPlayer) || entity.getRemovalReason() != Entity.RemovalReason.KILLED)) { // SPIGOT-6876: closeInventory clears death message
            // Paper start - Fix merchant inventory not closing on entity removal
            if (entity.getBukkitEntity() instanceof org.bukkit.inventory.Merchant merchant && merchant.getTrader() != null) {
                merchant.getTrader().closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED);
            }
            // Paper end - Fix merchant inventory not closing on entity removal
            for (org.bukkit.entity.HumanEntity h : Lists.newArrayList(((org.bukkit.inventory.InventoryHolder) entity.getBukkitEntity()).getInventory().getViewers())) {
                h.closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED); // Paper - Inventory close reason
            }
        }
        // Spigot end
    }

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void paper$onTrackingEndTail(final Entity entity, final CallbackInfo ci) {
        // CraftBukkit start
        entity.valid = false;
        if (!(entity instanceof ServerPlayer) && entity.getRemovalReason() != net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) {
            for (ServerPlayer player : this.this$0.getServer().getPlayerList().getPlayers()) { // Paper - call onEntityRemove for all online players
                player.getBukkitEntity().onEntityRemove(entity);
            }
        }
        // CraftBukkit end
        new com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent(entity.getBukkitEntity(), this.this$0.getWorld()).callEvent(); // Paper - fire while valid
    }
}
