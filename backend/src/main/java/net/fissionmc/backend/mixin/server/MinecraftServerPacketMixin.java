package net.fissionmc.backend.mixin.server;

import java.util.function.BooleanSupplier;
import net.fissionmc.backend.relay.PlayerRelayBackend;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC player relay: handles frames relayed from the master on the server thread at the start of every tick. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPacketMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void fission$relayTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        PlayerRelayBackend.tick((MinecraftServer) (Object) this);
    }
}
