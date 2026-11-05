package net.fissionmc.backend.mixin.server.network;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin extends ServerCommonPacketListenerImpl {
    protected ServerConfigurationPacketListenerImplMixin(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    // Surface: public. Mixin requires private fields; the loader widens it.
    private io.papermc.paper.connection.PaperPlayerConfigurationConnection paperConnection; // Paper

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$init(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie, final CallbackInfo ci) {
        this.paperConnection = new io.papermc.paper.connection.PaperPlayerConfigurationConnection((ServerConfigurationPacketListenerImpl) (Object) this); // Paper
    }

    @Shadow @Final private com.mojang.authlib.GameProfile gameProfile;

    // Paper - pass full GameProfile & listener for events (Paper changes the PrepareSpawnTask constructor; here the extra args are set right after construction)
    @WrapOperation(method = "returnToWorld", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/server/network/config/PrepareSpawnTask;"))
    private PrepareSpawnTask paper$passProfileAndListener(final MinecraftServer server, final NameAndId nameAndId, final Operation<PrepareSpawnTask> original) {
        final PrepareSpawnTask task = original.call(server, nameAndId);
        task.fission$init(this.gameProfile, (ServerConfigurationPacketListenerImpl) (Object) this);
        return task;
    }

    // TODO(behaviour): configuration events (PlayerConnectionInitialConfigureEvent, PlayerLinksSendEvent, code of conduct, login result)

    // Paper start - configuration phase API
    @Override
    public io.papermc.paper.connection.PlayerCommonConnection getApiConnection() {
        return this.paperConnection;
    }

    @Override
    public net.kyori.adventure.audience.Audience getAudience() {
        return this.paperConnection.getAudience();
    }
    // Paper end - configuration phase API

    // Paper start
    @Override
    public void disconnectAsync(final DisconnectionDetails disconnectionInfo) {
        if (this.cserver.isPrimaryThread()) {
            this.disconnect(disconnectionInfo);
            return;
        }

        this.connection.setReadOnly();
        this.server.scheduleOnMain(() -> {
            this.disconnect(disconnectionInfo); // Currently you cannot cancel disconnect during the config stage
        });
    }

    @Override
    public io.papermc.paper.connection.PaperPlayerConfigurationConnection paperConnection() {
        return paperConnection;
    }
    // Paper end
}
