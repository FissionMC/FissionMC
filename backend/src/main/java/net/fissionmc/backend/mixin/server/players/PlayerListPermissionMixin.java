package net.fissionmc.backend.mixin.server.players;

import net.fissionmc.backend.relay.PermissionBackend;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FissionMC: {@code isOp} (Bukkit's {@code isOp()}, plugins, the op list checks) answers true for the master's admins, and the permission level that
 * vanilla sends a player (join, /op, /deop) is reported to the master. New file next to the ported PlayerListMixin.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListPermissionMixin {
    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
    private void fission$masterAdminIsOp(NameAndId nameAndId, CallbackInfoReturnable<Boolean> cir) {
        if (PermissionBackend.isGranted(nameAndId.id())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "sendPlayerPermissionLevel(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/permissions/LevelBasedPermissionSet;)V", at = @At("HEAD"))
    private void fission$reportLevel(ServerPlayer player, LevelBasedPermissionSet permissions, CallbackInfo ci) {
        PermissionBackend.report(player.getUUID(), permissions.level().ordinal());
    }
}
