package net.fissionmc.backend.mixin.server;

import net.fissionmc.backend.relay.PermissionBackend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FissionMC: the master's admins are operator level 4 on every backend. {@code getProfilePermissions} is the single place that turns a profile into a
 * permission set (ServerPlayer.permissions(), CommandSourceStack, the command tree); ops.json is not touched. New file.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void fission$masterAdmin(NameAndId nameAndId, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        if (PermissionBackend.isGranted(nameAndId.id())) {
            cir.setReturnValue(LevelBasedPermissionSet.OWNER);
        }
    }
}
