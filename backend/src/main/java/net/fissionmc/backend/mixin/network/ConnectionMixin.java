package net.fissionmc.backend.mixin.network;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.SkipPacketDecoderException;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/network/Connection.java.patch */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow public Channel channel;
    @Shadow public SocketAddress address;
    @Shadow private volatile @Nullable PacketListener packetListener;
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private Queue<Consumer<Connection>> pendingActions;
    @Shadow private int receivedPackets;

    @Shadow public abstract boolean isConnected();
    @Shadow public abstract void disconnect(Component reason);
    @Shadow public abstract void setReadOnly();
    @Shadow public abstract String getLoggableAddress(boolean logIPs);
    @Shadow public abstract @Nullable PacketListener getPacketListener();
    @Shadow private void flushQueue() { throw new AssertionError(); }
    @Shadow private void sendPacket(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) { throw new AssertionError(); }

    // Surface: public fields. Mixin requires private fields; the loader widens them.
    // Spigot start
    private java.util.UUID spoofedUUID;
    private com.mojang.authlib.properties.Property[] spoofedProfile;
    private boolean preparing = true; // TODO(behaviour): set false in channelActive/disconnect
    // Spigot end
    private String hostname = ""; // CraftBukkit - add field
    private boolean handleConnectionDisconnectOnNextTick = false; // Paper - Force kill connection ticking // TODO(behaviour): consumed in tick()
    // Paper start - NetworkClient implementation
    private int protocolVersion;
    private java.net.InetSocketAddress virtualHost;
    // Paper end
    // Paper start - add utility methods
    public final net.minecraft.server.level.ServerPlayer getPlayer() {
        if (this.packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl impl) {
            return impl.player;
        } else if (this.packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl impl) {
            return null;
        }
        return null;
    }
    // Paper end - add utility methods
    private static boolean enableExplicitFlush = Boolean.getBoolean("paper.explicit-flush"); // Paper - Disable explicit connection flushing
    // Paper start - packet limiter
    private final Object PACKET_LIMIT_LOCK = new Object();
    private final io.papermc.paper.util.@Nullable IntervalledCounter allPacketCounts = io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.allPackets.isEnabled() ? new io.papermc.paper.util.IntervalledCounter(
        (long)(io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.allPackets.interval() * 1.0e9)
    ) : null;
    private final java.util.Map<Class<? extends net.minecraft.network.protocol.Packet<?>>, io.papermc.paper.util.IntervalledCounter> packetSpecificLimits = new java.util.HashMap<>();

    private boolean stopReadingPackets;
    private void killForPacketSpam() {
        this.sendPacket(new ClientboundDisconnectPacket(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.kickMessage)), PacketSendListener.thenRun(() -> {
            this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.kickMessage));
        }), true);
        this.setReadOnly();
        this.stopReadingPackets = true;
    }
    // Paper end - packet limiter
    private java.net.@Nullable SocketAddress haProxyAddress; // Paper - Add API to get player's proxy address
    private java.util.@Nullable Optional<net.minecraft.network.chat.Component> legacySavedLoginEventResultOverride; // Paper - playerloginevent
    private boolean handledLegacyLoginEvent; // Paper - playerloginevent
    private net.minecraft.server.level.@Nullable ServerPlayer savedPlayerForLegacyEvents; // Paper - playerloginevent & PlayerSpawnLocationEvent
    private org.bukkit.event.player.PlayerResourcePackStatusEvent.@Nullable Status resourcePackStatus; // Paper

    // Paper start - add proper async disconnect
    public void enableAutoRead() {
        if (this.channel != null) {
            this.channel.config().setAutoRead(true);
        }
    }
    // Paper end - add proper async disconnect

    @Inject(method = "channelActive", at = @At("TAIL"))
    private void fission$channelActive(final ChannelHandlerContext ctx, final CallbackInfo ci) {
        this.preparing = false; // Spigot
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void fission$disconnect(final DisconnectionDetails details, final CallbackInfo ci) {
        this.preparing = false; // Spigot
    }

    // Paper: exceptionCaught
    // TODO(behaviour): Paper - Handle large packets disconnecting client (needs PacketEncoder.PacketTooLargeException, a nested class)
    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    private void fission$exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause, final CallbackInfo ci) {
        if (cause instanceof SkipPacketException) {
            // Paper start - packet limiter
            if (cause instanceof SkipPacketDecoderException && !this.stopReadingPackets) {
                this.receivedPackets++;
                if (this.allPacketCounts != null) {
                    synchronized (this.PACKET_LIMIT_LOCK) {
                        this.allPacketCounts.updateAndAdd(1, System.nanoTime());
                        if (this.allPacketCounts.getRate() >= io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.allPackets.maxPacketRate()) {
                            this.killForPacketSpam();
                        }
                    }
                }
            }
            // Paper end - packet limiter
        } else if (this.channel.isOpen()) {
            net.minecraft.server.level.ServerPlayer player = this.getPlayer(); // Paper - Add API for quit reason
            if (player != null) {
                player.quitReason = cause instanceof io.netty.handler.timeout.TimeoutException
                    ? org.bukkit.event.player.PlayerQuitEvent.QuitReason.TIMED_OUT // Paper - Add API for quit reason
                    : org.bukkit.event.player.PlayerQuitEvent.QuitReason.ERRONEOUS_STATE; // Paper - Add API for quit reason
            }
        }
    }

    @ModifyExpressionValue(method = "exceptionCaught", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;getSending()Lnet/minecraft/network/protocol/PacketFlow;"))
    private PacketFlow fission$doesDisconnectExist(final PacketFlow original) {
        boolean doesDisconnectExist = this.packetListener != null && this.packetListener.protocol() != ConnectionProtocol.STATUS && this.packetListener.protocol() != ConnectionProtocol.HANDSHAKING; // Paper
        return doesDisconnectExist ? original : PacketFlow.SERVERBOUND; // Paper - (getSending() == CLIENTBOUND && doesDisconnectExist)
    }

    @Inject(method = "exceptionCaught", at = @At("TAIL"))
    private void fission$exceptionCaughtTail(final ChannelHandlerContext ctx, final Throwable cause, final CallbackInfo ci) {
        if (net.minecraft.server.MinecraftServer.getServer().isDebugging()) io.papermc.paper.util.TraceUtil.printStackTrace(cause); // Spigot // Paper
    }

    // Paper start - packet limiter
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketListener;shouldHandleMessage(Lnet/minecraft/network/protocol/Packet;)Z"), cancellable = true)
    private void fission$packetLimiter(final ChannelHandlerContext ctx, final Packet<?> packet, final CallbackInfo ci) {
        if (this.stopReadingPackets) {
            ci.cancel();
            return;
        }
        if (this.allPacketCounts != null ||
            io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.overrides.containsKey(packet.getClass())) {
            long time = System.nanoTime();
            synchronized (PACKET_LIMIT_LOCK) {
                if (this.allPacketCounts != null) {
                    this.allPacketCounts.updateAndAdd(1, time);
                    if (this.allPacketCounts.getRate() >= io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.allPackets.maxPacketRate()) {
                        this.killForPacketSpam();
                        ci.cancel();
                        return;
                    }
                }

                for (Class<?> check = packet.getClass(); check != Object.class; check = check.getSuperclass()) {
                    io.papermc.paper.configuration.GlobalConfiguration.PacketLimiter.PacketLimit packetSpecificLimit =
                        io.papermc.paper.configuration.GlobalConfiguration.get().packetLimiter.overrides.get(check);
                    if (packetSpecificLimit == null || !packetSpecificLimit.isEnabled()) {
                        continue;
                    }
                    io.papermc.paper.util.IntervalledCounter counter = this.packetSpecificLimits.computeIfAbsent((Class)check, (clazz) -> {
                        return new io.papermc.paper.util.IntervalledCounter((long)(packetSpecificLimit.interval() * 1.0e9));
                    });
                    counter.updateAndAdd(1, time);
                    if (counter.getRate() >= packetSpecificLimit.maxPacketRate()) {
                        switch (packetSpecificLimit.action()) {
                            case DROP:
                                ci.cancel();
                                return;
                            case KICK:
                                String deobfedPacketName = check.getName();

                                String playerName;
                                if (this.packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl impl) {
                                    playerName = impl.getOwner().name();
                                } else {
                                    playerName = this.getLoggableAddress(net.minecraft.server.MinecraftServer.getServer().logIPs());
                                }

                                LOGGER.warn("{} kicked for packet spamming: {}", playerName, deobfedPacketName.substring(deobfedPacketName.lastIndexOf(".") + 1));
                                this.killForPacketSpam();
                                ci.cancel();
                                return;
                        }
                    }
                }
            }
        }
    }
    // Paper end - packet limiter

    // Paper: do not prematurely disconnect players on stop (catch ServerStopRejectedExecutionException before RejectedExecutionException)
    @Redirect(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void fission$genericsFtw(final Packet<?> packet, final PacketListener listener) {
        try {
            fission$handle(packet, listener);
        } catch (io.papermc.paper.util.ServerStopRejectedExecutionException ignored) { // Paper - do not prematurely disconnect players on stop
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends PacketListener> void fission$handle(final Packet<T> packet, final PacketListener listener) {
        packet.handle((T) listener);
    }

    // Paper start - Add netty task scheduling helper
    private void scheduleNettyTask(final Runnable task) {
        final boolean connected = this.isConnected();
        if (!connected && !this.preparing) {
            return;
        }

        if (connected && this.pendingActions.isEmpty()) {
            this.channel.eventLoop().execute(task);
        } else {
            this.pendingActions.add(connection -> connection.channel.eventLoop().execute(task));
            this.flushQueue();
        }
    }

    public void sendPacketAndScheduleNettyTask(final Packet<?> packet, final boolean flush, final Runnable after) {
        this.scheduleNettyTask(() -> {
            this.sendPacket(packet, null, flush);
            if (after != null) {
                after.run();
            }
        });
    }
    // Paper end - Add netty task scheduling helper

    // Paper - Disable explicit connection flushing; we don't need to explicit flush here, but allow opt in incase issues are found to a better version
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lio/netty/channel/Channel;flush()Lio/netty/channel/Channel;"))
    private Channel fission$tickFlush(final Channel channel) {
        if (enableExplicitFlush) this.channel.eventLoop().execute(() -> this.channel.flush());
        return channel;
    }
    // TODO(behaviour): Paper - Buffer joins to world (maxJoinsPerTick; needs MinecraftServer.currentTick to be maintained), detailed watchdog packetProcessing push/pop

    // Paper start - Add Channel initialization listeners
    @Inject(method = "setupCompression", at = @At("TAIL"))
    private void fission$setupCompression(final int threshold, final boolean validateDecompressed, final CallbackInfo ci) {
        this.channel.pipeline().fireUserEventTriggered(threshold >= 0 ? io.papermc.paper.network.ConnectionEvent.COMPRESSION_THRESHOLD_SET : io.papermc.paper.network.ConnectionEvent.COMPRESSION_DISABLED);
    }
    // Paper end - Add Channel initialization listeners

    @Inject(method = "handleDisconnection", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketListener;onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V", shift = At.Shift.AFTER))
    private void fission$handleDisconnection(final CallbackInfo ci) {
        PacketListener packetListener = this.getPacketListener();
        this.pendingActions.clear(); // Free up packet queue.
        // Paper start - Add PlayerConnectionCloseEvent
        if (packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl commonPacketListener) {
            /* Player was logged in, either game listener or configuration listener */
            final com.mojang.authlib.GameProfile profile = commonPacketListener.getOwner();
            new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(profile.id(),
                profile.name(), this.address, false).callEvent();
        } else if (packetListener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl loginListener) {
            /* Player is login stage */
            switch (loginListener.state) {
                case VERIFYING:
                case WAITING_FOR_DUPE_DISCONNECT:
                case PROTOCOL_SWITCHING:
                case ACCEPTED:
                    final com.mojang.authlib.GameProfile profile = loginListener.authenticatedProfile; /* Should be non-null at this stage */
                    new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(profile.id(), profile.name(),
                        this.address, false).callEvent();
            }
        }
        // Paper end - Add PlayerConnectionCloseEvent
    }
}
