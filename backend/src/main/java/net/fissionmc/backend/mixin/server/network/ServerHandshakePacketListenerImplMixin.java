package net.fissionmc.backend.mixin.server.network;

import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/network/ServerHandshakePacketListenerImpl.java.patch */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakePacketListenerImplMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private Connection connection;

    // Spigot start
    private static final com.google.gson.Gson gson = new com.google.gson.Gson();
    private static final java.util.regex.Pattern HOST_PATTERN = java.util.regex.Pattern.compile("[0-9a-f\\.:]{0,45}");
    // FissionMC: PROP_PATTERN is used from ServerLoginPacketListenerImplMixin (kept there)
    // Spigot end
    // Paper start - Connection throttle
    private static final java.util.Map<java.net.InetAddress, Long> throttleTracker = new java.util.HashMap<>();
    private static int throttleCounter = 0;
    // Paper end - Connection throttle
    private static final boolean BYPASS_HOSTCHECK = Boolean.getBoolean("Paper.bypassHostCheck"); // Paper

    @Inject(method = "handleIntention", at = @At("HEAD"))
    private void paper$setHostname(final ClientIntentionPacket packet, final CallbackInfo ci) {
        this.connection.hostname = packet.hostName() + ":" + packet.port(); // CraftBukkit  - set hostname
    }

    @Inject(method = "handleIntention", at = @At("TAIL"))
    private void paper$networkClient(final ClientIntentionPacket packet, final CallbackInfo ci) {
        // Paper start - NetworkClient implementation
        this.connection.protocolVersion = packet.protocolVersion();
        this.connection.virtualHost = com.destroystokyo.paper.network.PaperNetworkClient.prepareVirtualHost(packet.hostName(), packet.port());
        // Paper end
    }

    // Paper: replaced body (connection throttle, Spigot version messages, PlayerHandshakeEvent, BungeeCord forwarding)
    @Inject(method = "beginLogin", at = @At("HEAD"), cancellable = true)
    private void paper$beginLogin(ClientIntentionPacket packet, final boolean transfer, final CallbackInfo ci) {
        ci.cancel();
        this.connection.setupOutboundProtocol(LoginProtocols.CLIENTBOUND);
        // Paper start - Connection throttle
        try {
            final long connectionThrottle = this.server.server.getConnectionThrottle();
            final boolean isUnixDomainSocket = this.connection.channel.localAddress() instanceof io.netty.channel.unix.DomainSocketAddress; // Paper - Unix domain socket support; the connection throttle is useless when you have a Unix domain socket
            if (connectionThrottle > 0 && !isUnixDomainSocket && this.connection.getRemoteAddress() instanceof java.net.InetSocketAddress socketAddress && !socketAddress.isUnresolved() && !socketAddress.getAddress().isLoopbackAddress()) {
                long currentTime = System.currentTimeMillis();
                java.net.InetAddress address = socketAddress.getAddress();

                synchronized (throttleTracker) {
                    if (throttleTracker.containsKey(address) && currentTime - throttleTracker.get(address) < connectionThrottle) {
                        throttleTracker.put(address, currentTime);
                        Component chatmessage = io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().messages.kick.connectionThrottle);
                        this.connection.send(new ClientboundLoginDisconnectPacket(chatmessage));
                        this.connection.disconnect(chatmessage);
                        return;
                    }

                    throttleTracker.put(address, currentTime);
                    throttleCounter++;
                    if (throttleCounter > 200) {
                        throttleCounter = 0;

                        // Cleanup stale entries
                        throttleTracker.values().removeIf(time -> currentTime - time > connectionThrottle);
                    }
                }
            }
        } catch (Throwable t) {
            org.apache.logging.log4j.LogManager.getLogger().debug("Failed to check connection throttle", t);
        }
        // Paper end - Connection throttle
        if (packet.protocolVersion() != SharedConstants.getCurrentVersion().protocolVersion()) {
            net.kyori.adventure.text.Component adventureReason; // Paper - Fix hex colors not working in some kick messages
            if (packet.protocolVersion() < SharedConstants.getCurrentVersion().protocolVersion()) { // Spigot - SPIGOT-7546: Handle version check correctly for outdated client message
                adventureReason = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(java.text.MessageFormat.format(org.spigotmc.SpigotConfig.outdatedClientMessage.replaceAll("'", "''"), SharedConstants.getCurrentVersion().name())); // Spigot // Paper - Fix hex colors not working in some kick messages
            } else {
                adventureReason = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(java.text.MessageFormat.format(org.spigotmc.SpigotConfig.outdatedServerMessage.replaceAll("'", "''"), SharedConstants.getCurrentVersion().name())); // Spigot // Paper - Fix hex colors not working in some kick messages
            }
            Component reason = io.papermc.paper.adventure.PaperAdventure.asVanilla(adventureReason); // Paper - Fix hex colors not working in some kick messages

            this.connection.send(new ClientboundLoginDisconnectPacket(reason));
            this.connection.disconnect(reason);
        } else {
            this.connection.setupInboundProtocol(LoginProtocols.SERVERBOUND, new ServerLoginPacketListenerImpl(this.server, this.connection, transfer));
            // Paper start - PlayerHandshakeEvent
            boolean proxyLogicEnabled = org.spigotmc.SpigotConfig.bungee;
            boolean handledByEvent = false;
            // Try and handle the handshake through the event
            if (com.destroystokyo.paper.event.player.PlayerHandshakeEvent.getHandlerList().getRegisteredListeners().length != 0) { // Hello? Can you hear me?
                java.net.SocketAddress socketAddress = this.connection.getRemoteAddress();
                String hostnameOfRemote = socketAddress instanceof java.net.InetSocketAddress ? ((java.net.InetSocketAddress) socketAddress).getHostString() : java.net.InetAddress.getLoopbackAddress().getHostAddress();
                com.destroystokyo.paper.event.player.PlayerHandshakeEvent event = new com.destroystokyo.paper.event.player.PlayerHandshakeEvent(packet.hostName(), hostnameOfRemote, !proxyLogicEnabled);
                if (event.callEvent()) {
                    // If we've failed somehow, let the client know so and go no further.
                    if (event.isFailed()) {
                        Component message = io.papermc.paper.adventure.PaperAdventure.asVanilla(event.failMessage());
                        this.connection.send(new ClientboundLoginDisconnectPacket(message));
                        this.connection.disconnect(message);
                        return;
                    }

                    if (event.getServerHostname() != null) {
                        // change hostname
                        packet = new ClientIntentionPacket(
                            packet.protocolVersion(),
                            event.getServerHostname(),
                            packet.port(),
                            packet.intention()
                        );
                        this.connection.hostname = event.getServerHostname();
                    }
                    if (event.getSocketAddressHostname() != null) this.connection.address = new java.net.InetSocketAddress(event.getSocketAddressHostname(), socketAddress instanceof java.net.InetSocketAddress ? ((java.net.InetSocketAddress) socketAddress).getPort() : 0);
                    this.connection.spoofedUUID = event.getUniqueId();
                    this.connection.spoofedProfile = gson.fromJson(event.getPropertiesJson(), com.mojang.authlib.properties.Property[].class);
                    handledByEvent = true; // Hooray, we did it!
                }
            }
            // Paper end
            // Spigot start
            String[] split = packet.hostName().split("\00");
            if (!handledByEvent && proxyLogicEnabled) { // Paper
                // if (org.spigotmc.SpigotConfig.bungee) { // Paper - comment out, we check above!
                if ((split.length == 3 || split.length == 4) && (BYPASS_HOSTCHECK || HOST_PATTERN.matcher(split[1]).matches())) { // Paper - Add bypass host check
                    // Paper start - Unix domain socket support
                    java.net.SocketAddress socketAddress = this.connection.getRemoteAddress();
                    this.connection.hostname = split[0];
                    this.connection.address = new java.net.InetSocketAddress(split[1], socketAddress instanceof java.net.InetSocketAddress ? ((java.net.InetSocketAddress) socketAddress).getPort() : 0);
                    // Paper end - Unix domain socket support
                    this.connection.spoofedUUID = com.mojang.util.UndashedUuid.fromStringLenient(split[2]);
                } else {
                    Component message = Component.literal("If you wish to use IP forwarding, please enable it in your BungeeCord config as well!");
                    this.connection.send(new ClientboundLoginDisconnectPacket(message));
                    this.connection.disconnect(message);
                    return;
                }
                if (split.length == 4) {
                    this.connection.spoofedProfile = gson.fromJson(split[3], com.mojang.authlib.properties.Property[].class);
                }
            } else if ((split.length == 3 || split.length == 4) && (HOST_PATTERN.matcher(split[1]).matches())) {
                Component message = Component.literal("Unknown data in login hostname, did you forget to enable BungeeCord in spigot.yml?");
                this.connection.send(new ClientboundLoginDisconnectPacket(message));
                this.connection.disconnect(message);
            }
            // Spigot end
        }
    }
}
