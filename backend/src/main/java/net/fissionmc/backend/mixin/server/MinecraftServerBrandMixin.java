package net.fissionmc.backend.mixin.server;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Server brand shown in F3 and the server list: the configuration phase sends {@code BrandPayload(getServerModName())}; vanilla says "vanilla". */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerBrandMixin {
    @Inject(method = "getServerModName", at = @At("HEAD"), cancellable = true)
    private void fission$brand(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue("FissionMC");
    }
}
