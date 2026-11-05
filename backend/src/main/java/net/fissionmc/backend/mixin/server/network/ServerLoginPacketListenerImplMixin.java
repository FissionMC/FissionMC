package net.fissionmc.backend.mixin.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.services.ProfileResult;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.notifications.ServerActivityMonitor;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import org.apache.commons.lang3.Validate;
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/server/network/ServerLoginPacketListenerImpl.java.patch
 * requestedUsername and transferred are widened to public via surface/members/player.txt.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private byte[] challenge;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Connection connection;
    @Shadow @Final private ServerActivityMonitor serverActivityMonitor;
    @Shadow public volatile ServerLoginPacketListenerImpl.State state;
    @Shadow private int tick;
    @Shadow private @Nullable String requestedUsername;
    @Shadow public @Nullable GameProfile authenticatedProfile;
    @Shadow @Final private boolean transferred;

    @Shadow public abstract void disconnect(Component component);
    @Shadow public abstract String getUserName();
    @Shadow private void startClientVerification(final GameProfile profile) { throw new AssertionError(); }
    @Shadow private void finishLoginAndWaitForClient(final GameProfile gameProfile) { throw new AssertionError(); }

    private static final java.util.concurrent.ExecutorService authenticatorPool = java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("User Authenticator #", 0).uncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER)).factory()); // Paper - Virtual authenticator threads
    // FissionMC: ServerHandshakePacketListenerImpl.PROP_PATTERN (package-private in Paper)
    private static final java.util.regex.Pattern fission$PROP_PATTERN = java.util.regex.Pattern.compile("\\w{0,16}");

    // Surface: public. Mixin requires private fields; the loader widens them.
    private boolean iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation = false; // Paper - username validation overriding
    private int velocityLoginMessageId = -1; // Paper - Add Velocity IP Forwarding Support
    private java.util.@Nullable UUID requestedUuid; // Paper
    private io.papermc.paper.connection.PaperPlayerLoginConnection paperLoginConnection; // Paper - Config API

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$init(final MinecraftServer minecraftserver, final Connection connection, final boolean transferred, final CallbackInfo ci) {
        this.paperLoginConnection = new io.papermc.paper.connection.PaperPlayerLoginConnection((ServerLoginPacketListenerImpl) (Object) this); // Paper
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void paper$tickCookies(final CallbackInfo ci) {
        // Paper start - login cookie API
        // Don't block the connection
        if (this.paperLoginConnection.isAwaitingCookies()) {
            this.tickTimeout(); // Ensure we tick timeout logic
            ci.cancel();
        }
        // Paper end - login cookie API
    }

    // Paper: tick() ends with tickTimeout()
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"))
    private void paper$tickDisconnectAsync(final ServerLoginPacketListenerImpl self, final Component reason) {
        this.disconnectAsync(reason); // Paper
    }

    // Paper start - login cookie API
    public void tickTimeout() {
        if (this.tick++ == 600) {
            this.disconnectAsync(Component.translatable("multiplayer.disconnect.slow_login")); // Paper
        }
    }
    // Paper end - login cookie API

    // CraftBukkit start
    @Deprecated
    public void disconnect(String reason) {
        this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(reason))); // Paper - Fix hex colors not working in some kick messages
    }
    // CraftBukkit end

    // Paper start
    public void disconnectAsync(Component reason) {
        try {
            LOGGER.info("Disconnecting {}: {}", this.getUserName(), reason.getString());
            this.connection.send(new ClientboundLoginDisconnectPacket(reason), PacketSendListener.thenRun(() -> this.connection.disconnect(reason)));
            this.connection.handleConnectionDisconnectOnNextTick = true;
        } catch (Exception var3) {
            LOGGER.error("Error whilst disconnecting player", (Throwable)var3);
        }
    }
    // Paper end

    // Paper: replaced body (username validation, Velocity forwarding, virtual authenticator threads + pre-login events)
    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void paper$handleHello(final ServerboundHelloPacket packet, final CallbackInfo ci) {
        ci.cancel();
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet");
        // Paper start - Validate usernames
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode()
            && io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.performUsernameValidation
            && !this.iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation) {
            Validate.validState(fission$isReasonablePlayerName(packet.name()), "Invalid characters in username");
        }
        this.requestedUuid = packet.profileId();
        // Paper end - Validate usernames
        this.requestedUsername = packet.name();
        GameProfile singleplayerProfile = this.server.getSingleplayerProfile();
        if (singleplayerProfile != null && this.requestedUsername.equalsIgnoreCase(singleplayerProfile.name())) {
            this.startClientVerification(singleplayerProfile);
        } else {
            if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                this.connection.send(new ClientboundHelloPacket("", this.server.getKeyPair().getPublic().getEncoded(), this.challenge, true));
            } else {
                // Paper start - Add Velocity IP Forwarding Support
                if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) {
                    this.velocityLoginMessageId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
                    net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    buf.writeByte(com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION);
                    net.minecraft.network.protocol.login.ClientboundCustomQueryPacket packet1 = new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(this.velocityLoginMessageId, new net.fissionmc.backend.network.PlayerInfoChannelPayload(com.destroystokyo.paper.proxy.VelocityProxy.PLAYER_INFO_CHANNEL, buf)); // FissionMC: Paper's ClientboundCustomQueryPacket.PlayerInfoChannelPayload
                    this.connection.send(packet1);
                    return;
                }
                // Paper end - Add Velocity IP Forwarding Support
                // Paper start - Virtual authenticator threads
                authenticatorPool.execute(() -> {
                    try {
                        GameProfile gameprofile = this.createOfflineProfile(this.requestedUsername); // Spigot

                        gameprofile = this.callPlayerPreLoginEvents(gameprofile); // Paper - Add more fields to AsyncPlayerPreLoginEvent
                        LOGGER.info("UUID of player {} is {}", gameprofile.name(), gameprofile.id());
                        this.startClientVerification(gameprofile);
                    } catch (Exception ex) {
                        this.disconnect("Failed to verify username!");
                        this.server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + this.requestedUsername, ex);
                    }
                });
                // Paper end - Virtual authenticator threads
            }
        }
    }

    // Paper start - Username validation (StringUtil.isReasonablePlayerName, inlined: StringUtil belongs to another area)
    private static boolean fission$isReasonablePlayerName(final String name) {
        if (name.isEmpty() || name.length() > 16) {
            return false;
        }

        for (int i = 0, len = name.length(); i < len; ++i) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c == '_' || c == '.')) {
                continue;
            }

            return false;
        }

        return true;
    }
    // Paper end - Username validation

    // Paper: replaced body (login result event, compression in one netty task, username-aware duplicate check)
    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), cancellable = true)
    private void paper$verifyLoginAndFinishConnectionSetup(final GameProfile profile, final CallbackInfo ci) {
        ci.cancel();
        PlayerList playerList = this.server.getPlayerList();
        Component error = org.bukkit.craftbukkit.event.CraftEventFactory.handleLoginResult(playerList.fission$canPlayerLogin(this.connection.getRemoteAddress(), new NameAndId(profile)), this.paperLoginConnection, this.connection, profile, this.server, true); // Paper
        if (error != null) {
            this.disconnectAsync(error); // Paper
        } else {
            if (this.server.getCompressionThreshold() >= 0 && !this.connection.isMemoryConnection()) {
                // Paper start - Keep compression packet write and setup in the same Netty task
                final int compressionThreshold = this.server.getCompressionThreshold();
                this.connection.sendPacketAndScheduleNettyTask(
                    new ClientboundLoginCompressionPacket(compressionThreshold), true,
                    () -> {
                        this.connection.setupCompression(compressionThreshold, true);
                    }
                );
                // Paper end - Keep compression packet write and setup in the same Netty task
            }

            boolean waitForDisconnection = playerList.disconnectAllPlayersWithProfile(profile); // Paper - validate usernames
            if (waitForDisconnection) {
                this.state = ServerLoginPacketListenerImpl.State.WAITING_FOR_DUPE_DISCONNECT;
            } else {
                this.finishLoginAndWaitForClient(profile);
            }
        }
    }

    @Inject(method = "finishLoginAndWaitForClient", at = @At("TAIL"))
    private void paper$updateProfileCache(final GameProfile gameProfile, final CallbackInfo ci) {
        this.server.services().paper().filledProfileCache().add(gameProfile); // Paper - update profile cache
    }

    // Paper: replaced body (virtual authenticator threads, pre-login events, catch-all, configurable kick message)
    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void paper$handleKey(final ServerboundKeyPacket packet, final CallbackInfo ci) {
        ci.cancel();
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.KEY, "Unexpected key packet");

        final String digest;
        try {
            PrivateKey serverPrivateKey = this.server.getKeyPair().getPrivate();
            if (!packet.isChallengeValid(this.challenge, serverPrivateKey)) {
                throw new IllegalStateException("Protocol error");
            }

            SecretKey secretKey = packet.getSecretKey(serverPrivateKey);
            digest = new BigInteger(Crypt.digestData("", this.server.getKeyPair().getPublic(), secretKey)).toString(16);
            this.state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;
            Cipher decryptCipher = Crypt.getCipher(2, secretKey);
            Cipher encryptCipher = Crypt.getCipher(1, secretKey);
            this.connection.setEncryptionKey(decryptCipher, encryptCipher);
        } catch (CryptException e) {
            throw new IllegalStateException("Protocol error", e);
        }

        // Paper start - Virtual authenticator threads
        authenticatorPool.execute(() -> {
            String name = Objects.requireNonNull(this.requestedUsername, "Player name not initialized");

            try {
                ProfileResult result = this.server.services().sessionService().hasJoinedServer(name, digest, this.fission$getAddress());
                if (result != null) {
                    GameProfile profile = result.profile();
                    // CraftBukkit start - fire PlayerPreLoginEvent
                    if (!this.connection.isConnected()) {
                        return;
                    }
                    profile = this.callPlayerPreLoginEvents(profile); // Paper - Add more fields to AsyncPlayerPreLoginEvent
                    // CraftBukkit end
                    LOGGER.info("UUID of player {} is {}", profile.name(), profile.id());
                    this.serverActivityMonitor.reportLoginActivity();
                    this.startClientVerification(profile);
                } else if (this.server.isSingleplayer()) {
                    LOGGER.warn("Failed to verify username but will let them in anyway!");
                    this.startClientVerification(this.createOfflineProfile(name)); // Spigot
                } else {
                    this.disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
                    LOGGER.error("Username '{}' tried to join with an invalid session", name);
                }
            } catch (AuthenticationUnavailableException ignored) {
                if (this.server.isSingleplayer()) {
                    LOGGER.warn("Authentication servers are down but will let them in anyway!");
                    this.startClientVerification(this.createOfflineProfile(name)); // Spigot
                } else {
                    this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().messages.kick.authenticationServersDown)); // Paper - Configurable kick message
                    LOGGER.error("Couldn't verify username because servers are unavailable");
                }
                // CraftBukkit start - catch all exceptions
            } catch (Exception ex) {
                this.disconnect("Failed to verify username!");
                LOGGER.warn("Exception verifying {}", name, ex);
                // CraftBukkit end
            }
        });
        // Paper end - Virtual authenticator threads
    }

    private @Nullable InetAddress fission$getAddress() {
        return this.server.getPreventProxyConnections() && this.connection.getRemoteAddress() instanceof InetSocketAddress inetSocketAddress
            ? inetSocketAddress.getAddress()
            : null;
    }

    // CraftBukkit start
    private GameProfile callPlayerPreLoginEvents(GameProfile gameprofile) throws Exception { // Paper - Add more fields to AsyncPlayerPreLoginEvent
        // Paper start - Add Velocity IP Forwarding Support
        if (this.velocityLoginMessageId == -1 && io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) {
            this.disconnect("This server requires you to connect with Velocity.");
            return gameprofile;
        }
        // Paper end - Add Velocity IP Forwarding Support
        String playerName = gameprofile.name();
        java.net.InetAddress address = ((java.net.InetSocketAddress) this.connection.getRemoteAddress()).getAddress();
        java.util.UUID uniqueId = gameprofile.id();
        final org.bukkit.craftbukkit.CraftServer server = this.server.server;

        // Paper start - Add more fields to AsyncPlayerPreLoginEvent
        final java.net.SocketAddress rawAddress = this.connection.channel.remoteAddress();
        com.destroystokyo.paper.profile.PlayerProfile profile = com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitCopy(gameprofile); // Paper - setPlayerProfileAPI
        AsyncPlayerPreLoginEvent asyncEvent = new AsyncPlayerPreLoginEvent(playerName, address, rawAddress, uniqueId, this.transferred, profile, this.connection.hostname, this.paperLoginConnection); // Paper
        server.getPluginManager().callEvent(asyncEvent);
        profile = asyncEvent.getPlayerProfile();
        profile.complete(true); // Paper - setPlayerProfileAPI
        gameprofile = com.destroystokyo.paper.profile.CraftPlayerProfile.asAuthlibCopy(profile);
        playerName = gameprofile.name();
        uniqueId = gameprofile.id();
        // Paper end - Add more fields to AsyncPlayerPreLoginEvent

        if (PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) {
            final PlayerPreLoginEvent event = new PlayerPreLoginEvent(playerName, address, uniqueId);
            if (asyncEvent.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                event.disallow(asyncEvent.getResult(), asyncEvent.kickMessage()); // Paper - Adventure
            }
            Waitable<PlayerPreLoginEvent.Result> waitable = new net.fissionmc.backend.network.SupplierWaitable<>(() -> { // FissionMC: named class instead of Paper's anonymous Waitable
                server.getPluginManager().callEvent(event);
                return event.getResult();
            });
            this.server.processQueue.add(waitable);
            if (waitable.get() != PlayerPreLoginEvent.Result.ALLOWED) {
                this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.kickMessage())); // Paper - Adventure
            }
        } else {
            if (asyncEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(asyncEvent.kickMessage())); // Paper - Adventure
            }
        }
        return gameprofile; // Paper - Add more fields to AsyncPlayerPreLoginEvent
    }
    // CraftBukkit end

    // Paper: replaced body (Velocity forwarding answer)
    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void paper$handleCustomQueryPacket(final ServerboundCustomQueryAnswerPacket packet, final CallbackInfo ci) {
        ci.cancel();
        // Paper start - Add Velocity IP Forwarding Support
        try {
            this.handleCustomQueryPacket0(packet);
        } finally {
            if (packet.payload() instanceof net.fissionmc.backend.network.QueryAnswerPayload payload) { // FissionMC: Paper's ServerboundCustomQueryAnswerPacket.QueryAnswerPayload
                payload.buffer.release();
            }
        }
    }

    private void handleCustomQueryPacket0(final ServerboundCustomQueryAnswerPacket packet) {
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled && packet.transactionId() == this.velocityLoginMessageId) {
            net.fissionmc.backend.network.QueryAnswerPayload payload = (net.fissionmc.backend.network.QueryAnswerPayload)packet.payload();
            if (payload == null) {
                this.disconnect("This server requires you to connect with Velocity.");
                return;
            }

            net.minecraft.network.FriendlyByteBuf buf = payload.buffer;
            if (!com.destroystokyo.paper.proxy.VelocityProxy.checkIntegrity(buf)) {
                this.disconnect("Unable to verify player details");
                return;
            }

            int version = buf.readVarInt();
            if (version > com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION) {
                throw new IllegalStateException("Unsupported forwarding version " + version + ", wanted upto " + com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION);
            }

            java.net.SocketAddress listening = this.connection.getRemoteAddress();
            int port = 0;
            if (listening instanceof java.net.InetSocketAddress) {
                port = ((java.net.InetSocketAddress) listening).getPort();
            }
            this.connection.address = new java.net.InetSocketAddress(com.destroystokyo.paper.proxy.VelocityProxy.readAddress(buf), port);

            this.authenticatedProfile = com.destroystokyo.paper.proxy.VelocityProxy.createProfile(buf);

            //TODO Update handling for lazy sessions, might not even have to do anything?

            // Proceed with login
            authenticatorPool.execute(() -> {
                try {
                    final GameProfile gameprofile = this.callPlayerPreLoginEvents(this.authenticatedProfile);
                    LOGGER.info("UUID of player {} is {}", gameprofile.name(), gameprofile.id());
                    this.startClientVerification(gameprofile);
                } catch (Exception ex) {
                    disconnect("Failed to verify username!");
                    server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + this.authenticatedProfile.name(), ex);
                }
            });
            return;
        }
        // Paper end - Add Velocity IP Forwarding Support
        this.disconnect(Component.translatable("multiplayer.disconnect.unexpected_query_response")); // FissionMC: ServerCommonPacketListenerImpl.DISCONNECT_UNEXPECTED_QUERY is package-private
    }

    @Inject(method = "handleLoginAcknowledgement", at = @At("HEAD"))
    private void craftbukkit$ensureMainThread(final ServerboundLoginAcknowledgedPacket packet, final CallbackInfo ci) {
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, (ServerLoginPacketListenerImpl) (Object) this, this.server.packetProcessor()); // CraftBukkit
    }

    @Inject(method = "handleCookieResponse", at = @At("HEAD"), cancellable = true)
    private void paper$handleCookieResponse(final ServerboundCookieResponsePacket packet, final CallbackInfo ci) {
        if (this.paperLoginConnection.handleCookieResponse(packet)) ci.cancel(); // Paper
    }

    // Spigot start
    private GameProfile createOfflineProfile(String s) {
        java.util.UUID uuid;
        if (this.connection.spoofedUUID != null) {
            uuid = this.connection.spoofedUUID;
        } else {
            uuid = UUIDUtil.createOfflinePlayerUUID(s);
        }

        final var props = com.google.common.collect.ImmutableMultimap.<String, com.mojang.authlib.properties.Property>builder();

        if (this.connection.spoofedProfile != null) {
            for (com.mojang.authlib.properties.Property property : this.connection.spoofedProfile) {
                if (!fission$PROP_PATTERN.matcher(property.name()).matches()) continue;
                props.put(property.name(), property);
            }
        }

        return new GameProfile(uuid, s, new com.mojang.authlib.properties.PropertyMap(props.build()));
    }
    // Spigot end
}
