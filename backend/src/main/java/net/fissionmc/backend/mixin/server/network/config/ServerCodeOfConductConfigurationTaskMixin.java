package net.fissionmc.backend.mixin.server.network.config;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.server.network.config.ServerCodeOfConductConfigurationTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/network/config/ServerCodeOfConductConfigurationTask.java.patch (replaced body of start) */
@Mixin(ServerCodeOfConductConfigurationTask.class)
public abstract class ServerCodeOfConductConfigurationTaskMixin {
    @Shadow @Final private Supplier<String> codeOfConduct;

    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void paper$codeOfConductEvent(final Consumer<Packet<?>> connection, final CallbackInfo ci) {
        // Paper start - Code of Conduct event
        // Null code of conduct cancels the send
        // See ServerConfigurationPacketListenerImpl section where the task is immediately completed when a null is sent here
        String codeOC = this.codeOfConduct.get();
        if (codeOC != null) {
            connection.accept(new ClientboundCodeOfConductPacket(codeOC));
        }
        ci.cancel();
        // Paper end - Code of Conduct event
    }
}
