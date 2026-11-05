package net.fissionmc.backend.mixin.server.network.config;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/network/config/PrepareSpawnTask.java.patch (Ready.spawn) */
@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Ready")
public abstract class PrepareSpawnTask_ReadyMixin {
    @Shadow @Final PrepareSpawnTask this$0;
    @Shadow @Final private ServerLevel spawnLevel;

    // Paper start - configuration api - possibly use legacy saved server player instance
    @WrapOperation(method = "spawn", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer paper$reuseLegacyPlayer(final MinecraftServer server, final ServerLevel level, final GameProfile profile, final ClientInformation information, final Operation<ServerPlayer> original, @Local(argsOnly = true) final Connection connection, @Local(argsOnly = true) final CommonListenerCookie cookie) {
        ServerPlayer player;
        if (connection.savedPlayerForLegacyEvents != null) {
            player = connection.savedPlayerForLegacyEvents;
            connection.savedPlayerForLegacyEvents = null;
            // Update the existing instance
            player.gameProfile = cookie.gameProfile();
            player.updateOptionsNoEvents(cookie.clientInformation());
            player.setServerLevel(this.spawnLevel);
        } else {
            player = original.call(server, level, profile, information);
        }
        // Paper end - configuration api - possibly use legacy saved server player instance
        this.this$0.fission$listener().paperConnection.applyPendingEntityId(player); // Paper - internal entity id api - possibly override player network id if plugins used internal API to configure it.
        return player;
    }

    // CraftBukkit start - first played (first Optional.map in spawn)
    @WrapOperation(method = "spawn", at = @At(value = "INVOKE", target = "Ljava/util/Optional;map(Ljava/util/function/Function;)Ljava/util/Optional;", ordinal = 0))
    private Optional<?> craftbukkit$firstPlayed(final Optional<net.minecraft.nbt.CompoundTag> data, final Function<?, ?> mapper, final Operation<Optional<?>> original, @Local final ServerPlayer player) {
        return original.call(data.map(tag -> {
            org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer = player.getBukkitEntity();
            // Only update first played if it is older than the one we have
            long modified = new java.io.File(this.this$0.fission$server().getPlayerList().playerIo.getPlayerDir(), player.getStringUUID() + ".dat").lastModified();
            if (modified < craftPlayer.getFirstPlayed()) {
                craftPlayer.setFirstPlayed(modified);
            }
            return tag;
        }), mapper);
    }
    // CraftBukkit end

    @Inject(method = "spawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;snapTo(Lnet/minecraft/world/phys/Vec3;FF)V"))
    private void craftbukkit$beforeSnap(final Connection connection, final CommonListenerCookie cookie, final CallbackInfoReturnable<ServerPlayer> cir, @Local final ServerPlayer player, @Local final Optional<ValueInput> input) {
        // CraftBukkit start - Better rename detection
        if (input.isPresent()) {
            player.lastKnownName = input.flatMap(t -> t.child("bukkit")).flatMap(t -> t.getString("lastKnownName")).orElse(null);
        }
        // CraftBukkit end - Better rename detection
        // Paper start - Entity#getEntitySpawnReason
        if (input.isEmpty()) {
            player.spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT; // set Player SpawnReason to DEFAULT on first login
        }
        // Paper end - Entity#getEntitySpawnReason
    }
}
