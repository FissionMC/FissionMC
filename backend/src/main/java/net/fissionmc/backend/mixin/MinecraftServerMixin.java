package net.fissionmc.backend.mixin;

import net.fissionmc.backend.Hooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Plugin lifecycle: STARTUP plugins before worlds load, POSTWORLD after. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void fission$beforeLevel(CallbackInfo ci) {
        if ((Object) this instanceof DedicatedServer server) {
            Hooks.beforeLevel(server);
        }
    }

    @Inject(method = "loadLevel", at = @At("RETURN"))
    private void fission$afterLevel(CallbackInfo ci) {
        if ((Object) this instanceof DedicatedServer) {
            Hooks.afterLevel();
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void fission$beforeStop(CallbackInfo ci) {
        if ((Object) this instanceof DedicatedServer) {
            Hooks.beforeStop();
        }
    }
}
