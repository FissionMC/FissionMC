package net.fissionmc.backend.mixin.server.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ServerChannel;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/server/network/ServerConnectionListener.java.patch */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private List<ChannelFuture> channels;
    @Shadow @Final private List<Connection> connections;

    // Paper - adventure: channel locale attribute
    @WrapOperation(method = "startTcpServerListener", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
    private io.netty.bootstrap.AbstractBootstrap<?, ?> paper$localeAttribute(final ServerBootstrap bootstrap, final Class<? extends ServerChannel> channelClass, final Operation<io.netty.bootstrap.AbstractBootstrap<?, ?>> original) {
        // Paper start - Warn people with console access that HAProxy is in use.
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.proxyProtocol) {
            LOGGER.warn("Using HAProxy, please ensure the server port is adequately firewalled.");
        }
        // Paper end - Warn people with console access that HAProxy is in use.
        return ((ServerBootstrap) original.call(bootstrap, channelClass)).childAttr(io.papermc.paper.adventure.PaperAdventure.LOCALE_ATTRIBUTE, java.util.Locale.US); // Paper - adventure
    }

    // Paper - Force kill connection ticking (skip connections that are being disconnected, see MC-307764);
    // Spigot - Fix a race condition where a Connection could be unregistered just before connection
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;isConnecting()Z"))
    private boolean paper$skipConnection(final Connection connection, final Operation<Boolean> original) {
        if (original.call(connection)) {
            return true;
        }
        if (connection.isConnected()) {
            return connection.handleConnectionDisconnectOnNextTick; // Paper - Force kill connection ticking
        }
        return connection.preparing; // Spigot
    }
    // TODO(behaviour): Spigot playerShuffle (config: settings.player-shuffle, default 0; also needs MinecraftServer.currentTick)

    // CraftBukkit start
    // FissionMC: Paper binds with AUTO_READ=false and calls this after plugins enabled; the AUTO_READ option is not ported
    // (nothing calls acceptConnections yet), so this is a harmless no-op until then.
    public void acceptConnections() {
        synchronized (this.channels) {
            for (ChannelFuture future : this.channels) {
                future.channel().config().setAutoRead(true);
            }
        }
    }
    // CraftBukkit end

    // Paper start
    public void handleAllDisconnections() {
        synchronized (this.connections) {
            // Paper start - Add maximum time to wait for channels to close
            for (final Connection connection : this.connections) {
                connection.channel.close();
            }

            final long startTime = System.nanoTime();
            for (final Connection connection : this.connections) {
                final ChannelFuture closeFuture = connection.channel.closeFuture();

                if (closeFuture.awaitUninterruptibly(TimeUnit.SECONDS.toNanos(30) - (System.nanoTime() - startTime), TimeUnit.NANOSECONDS)) {
                    connection.handleDisconnection();
                } else {
                    LOGGER.error("Timed out whilst waiting for player connection channel to close");
                }
            }
            // Paper end - Add maximum time to wait for channels to close
        }
    }
    // Paper end
}
