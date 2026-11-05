package net.fissionmc.backend.mixin.server;

import java.util.function.BooleanSupplier;
import net.fissionmc.backend.replication.Replication;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC replication: applies queued remote events on the server thread at the start of every tick. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerReplicationMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void fission$applyReplicatedEvents(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        Replication.tick((MinecraftServer) (Object) this);
    }
}
