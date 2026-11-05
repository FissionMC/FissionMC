package net.fissionmc.backend.mixin.server.network.config;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/network/config/PrepareSpawnTask.java.patch (Preparing: PlayerSpawnLocationEvent / AsyncPlayerSpawnLocationEvent) */
@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Preparing")
public abstract class PrepareSpawnTask_PreparingMixin {
    @Shadow @Final PrepareSpawnTask this$0;
    @Shadow @Final @Mutable private ServerLevel spawnLevel; // Paper - remove final
    @Shadow @Final @Mutable private CompletableFuture<Vec3> spawnPosition; // Paper - remove final
    @Shadow @Final @Mutable private Vec2 spawnAngle; // Paper - remove final
    private @Nullable CompletableFuture<org.bukkit.Location> eventFuture; // Paper

    // Paper start - PlayerSpawnLocationEvent (at the start of `if (this.chunkLoadFuture == null)`)
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;containing(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;", ordinal = 0), cancellable = true)
    private void paper$spawnLocationEvent(final CallbackInfoReturnable<Object> cir, @Local final LocalRef<Vec3> spawnPositionRef) {
        final PrepareSpawnTask task = this.this$0;
        Vec3 spawnPosition = spawnPositionRef.get();
        if (this.eventFuture == null && org.spigotmc.event.player.PlayerSpawnLocationEvent.getHandlerList().getRegisteredListeners().length != 0) {
            ServerPlayer player;
            if (task.fission$listener().connection.savedPlayerForLegacyEvents != null) {
                player = task.fission$listener().connection.savedPlayerForLegacyEvents;
            } else {
                player = new ServerPlayer(
                    task.fission$server(),
                    task.fission$server().overworld(),
                    task.fission$profile(),
                    net.minecraft.server.level.ClientInformation.createDefault()
                );
                task.fission$listener().connection.savedPlayerForLegacyEvents = player;
            }
            org.spigotmc.event.player.PlayerSpawnLocationEvent ev = new org.spigotmc.event.player.PlayerSpawnLocationEvent(
                player.getBukkitEntity(),
                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPosition, this.spawnLevel, this.spawnAngle.x, this.spawnAngle.y)
            );
            ev.callEvent();
            spawnPosition = io.papermc.paper.util.MCUtil.toVec3(ev.getSpawnLocation());
            if (ev.getSpawnLocation().getWorld() != null) this.spawnLevel = ((org.bukkit.craftbukkit.CraftWorld) ev.getSpawnLocation().getWorld()).getHandle();
            this.spawnPosition = CompletableFuture.completedFuture(spawnPosition);
            this.spawnAngle = new Vec2(ev.getSpawnLocation().getYaw(), ev.getSpawnLocation().getPitch());
        }

        if (this.eventFuture == null && io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent.getHandlerList().getRegisteredListeners().length != 0) {
            final Vec3 spawnPositionFinal = spawnPosition;
            this.eventFuture = CompletableFuture.supplyAsync(() -> {
                io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent ev = new io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent(
                    task.fission$listener().paperConnection,
                    org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPositionFinal, this.spawnLevel, this.spawnAngle.x, this.spawnAngle.y),
                    task.fission$newPlayer()
                );
                ev.callEvent();
                return ev.getSpawnLocation();
            }, io.papermc.paper.connection.PaperConfigurationTask.CONFIGURATION_POOL);
        }
        if (this.eventFuture != null) {
            if (!this.eventFuture.isDone()) {
                spawnPositionRef.set(spawnPosition);
                cir.setReturnValue(null);
                return;
            }
            org.bukkit.Location location = this.eventFuture.join();
            spawnPosition = io.papermc.paper.util.MCUtil.toVec3(location);
            this.spawnLevel = ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle();
            this.spawnPosition = CompletableFuture.completedFuture(spawnPosition);
            this.spawnAngle = new Vec2(location.getYaw(), location.getPitch());
        }
        spawnPositionRef.set(spawnPosition);
    }
    // Paper end - PlayerSpawnLocationEvent
}
