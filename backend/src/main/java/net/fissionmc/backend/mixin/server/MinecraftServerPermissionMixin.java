package net.fissionmc.backend.mixin.server;

import net.fissionmc.backend.relay.PermissionBackend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FissionMC: the master's permission groups give a player an operator level on every backend (4 = the old admin). {@code getProfilePermissions} is the
 * single place that turns a profile into a permission set (ServerPlayer.permissions(), CommandSourceStack, the command tree); the higher of the backend's own
 * level (ops.json, untouched) and the master's wins. New file.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("RETURN"), cancellable = true)
    private void fission$masterLevel(NameAndId nameAndId, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        int granted = PermissionBackend.level(nameAndId.id());
        if (granted > cir.getReturnValue().level().ordinal()) {
            cir.setReturnValue(granted >= 4 ? LevelBasedPermissionSet.OWNER : LevelBasedPermissionSet.forLevel(PermissionLevel.byId(granted)));
        }
    }
}
