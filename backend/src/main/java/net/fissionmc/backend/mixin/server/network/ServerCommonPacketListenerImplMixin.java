package net.fissionmc.backend.mixin.server.network;

import java.util.Optional;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Redirect;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;

/** Paper: sources/net/minecraft/server/network/ServerCommonPacketListenerImpl.java.patch */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Shadow @Final protected MinecraftServer server;
    @Shadow @Final protected Connection connection; // Surface: public (Paper)
    @Shadow @Final private boolean transferred;

    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private static Component TIMEOUT_DISCONNECTION_MESSAGE;
    @Shadow @Final static Component DISCONNECT_UNEXPECTED_QUERY;
    @Shadow private long keepAliveTime;
    @Shadow private boolean keepAlivePending;

    @Shadow public abstract void disconnect(DisconnectionDetails details);
    @Shadow protected abstract boolean isSingleplayerOwner();

    // Surface: public (final) fields. Mixin requires private, non-final-assigned-outside-<init> fields; the loader widens them.
    // CraftBukkit start
    private org.bukkit.craftbukkit.CraftServer cserver;
    // CraftBukkit end
    private java.util.Map<java.util.UUID, net.kyori.adventure.resource.ResourcePackCallback> packCallbacks = new java.util.concurrent.ConcurrentHashMap<>(); // Paper - adventure resource pack callbacks // TODO(behaviour): consumed in handleResourcePackResponse
    private static final long KEEPALIVE_LIMIT = Long.getLong("paper.playerconnection.keepalive", 30) * 1000; // Paper - provide property to set keepalive limit // TODO(behaviour): used in keepConnectionAlive
    private static final net.minecraft.resources.Identifier MINECRAFT_BRAND = net.minecraft.resources.Identifier.withDefaultNamespace("brand"); // Paper - Brand support
    // Paper start - retain certain values
    private @Nullable String clientBrand;
    private java.util.Set<String> pluginMessagerChannels;
    // Paper end - retain certain values

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$init(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie, final CallbackInfo ci) {
        // Paper start
        this.clientBrand = cookie.clientBrand();
        this.cserver = server.server;
        this.pluginMessagerChannels = cookie.channels();
        // Paper end
    }

    // Paper: createCookie passes clientBrand and pluginMessagerChannels to the new cookie (record components; see CommonListenerCookieMixin)
    @Inject(method = "createCookie", at = @At("RETURN"), cancellable = true) // FissionMC: cancellable is needed for setReturnValue on RETURN
    private void fission$createCookie(final ClientInformation clientInformation, final CallbackInfoReturnable<CommonListenerCookie> cir) {
        CommonListenerCookie cookie = cir.getReturnValue();
        cir.setReturnValue(CommonListenerCookie.create(cookie.gameProfile(), cookie.latency(), cookie.clientInformation(), cookie.transferred(), this.clientBrand, this.pluginMessagerChannels)); // Paper
    }

    // Paper start - configuration phase API
    // Paper: abstract; overridden in ServerGamePacketListenerImplMixin / ServerConfigurationPacketListenerImplMixin.
    public io.papermc.paper.connection.PlayerCommonConnection getApiConnection() {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }

    public net.kyori.adventure.audience.Audience getAudience() {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }
    // Paper end - configuration phase API

    // Paper start
    private static final net.minecraft.resources.Identifier CUSTOM_REGISTER = net.minecraft.resources.Identifier.withDefaultNamespace("register");
    private static final net.minecraft.resources.Identifier CUSTOM_UNREGISTER = net.minecraft.resources.Identifier.withDefaultNamespace("unregister");
    // Paper end

    // Paper: keepalive mismatch disconnects asynchronously
    @Redirect(method = "handleKeepAlive", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"))
    private void paper$keepAliveDisconnectAsync(final ServerCommonPacketListenerImpl self, final Component reason) {
        this.disconnectAsync(TIMEOUT_DISCONNECTION_MESSAGE, io.papermc.paper.connection.DisconnectionReason.TIMEOUT); // Paper - add proper async disconnect
    }

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void paper$handleCustomPayload(final ServerboundCustomPayloadPacket packet, final CallbackInfo ci) {
        // Paper start
        if (!(packet.payload() instanceof final net.minecraft.network.protocol.common.custom.DiscardedPayload discardedPayload)) {
            return;
        }

        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, (ServerCommonPacketListenerImpl) (Object) this, this.server.packetProcessor());

        final net.minecraft.resources.Identifier identifier = packet.payload().type().id();
        final byte[] data = discardedPayload.data();
        try {
            final boolean registerChannel = CUSTOM_REGISTER.equals(identifier);
            if (registerChannel || CUSTOM_UNREGISTER.equals(identifier)) {
                // Strings separated by zeros instead of length prefixes
                int startIndex = 0;
                for (int i = 0; i < data.length; i++) {
                    final byte b = data[i];
                    if (b != 0) {
                        continue;
                    }

                    readChannelIdentifier(data, startIndex, i, registerChannel);
                    startIndex = i + 1;
                }

                // Read the last one
                readChannelIdentifier(data, startIndex, data.length, registerChannel);
                return;
            }

            if (identifier.equals(MINECRAFT_BRAND)) {
                this.clientBrand = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)).readUtf(256);
            }

            this.cserver.getMessenger().dispatchIncomingMessage(paperConnection(), identifier.toString(), data);
        } catch (final Exception e) {
            LOGGER.error("Couldn't handle custom payload on channel {}", identifier, e);
            this.disconnect(Component.literal("Invalid custom payload payload!"), io.papermc.paper.connection.DisconnectionReason.INVALID_PAYLOAD); // Paper - kick event cause
        }
    }

    private void readChannelIdentifier(final byte[] data, final int from, final int to, final boolean register) {
        io.papermc.paper.connection.PluginMessageBridgeImpl bridge = switch ((Object) this) {
            case ServerGamePacketListenerImpl gamePacketListener -> gamePacketListener.player.getBukkitEntity();
            case ServerConfigurationPacketListenerImpl commonPacketListener -> commonPacketListener.paperConnection;
            default -> null;
        };
        if (bridge == null) {
            return;
        }


        final int length = to - from;
        if (length == 0) {
            return;
        }

        final String channel = new String(data, from, length, java.nio.charset.StandardCharsets.US_ASCII);
        if (register) {
            bridge.addChannel(channel);
        } else {
            bridge.removeChannel(channel);
        }
    // Paper end
    }

    @Inject(method = "handleCustomClickAction", at = @At("TAIL"))
    private void paper$handleCustomClickAction(final ServerboundCustomClickActionPacket packet, final CallbackInfo ci) {
        // Paper start - Implement click callbacks with custom click action
        final io.papermc.paper.event.player.PaperPlayerCustomClickEvent event = new io.papermc.paper.event.player.PaperPlayerCustomClickEvent(io.papermc.paper.adventure.PaperAdventure.asAdventure(packet.id()), this.getApiConnection(), packet.payload().orElse(null));
        event.callEvent();
        io.papermc.paper.adventure.providers.ClickCallbackProviderImpl.DIALOG_CLICK_MANAGER.tryRunCallback(this.getAudience(), packet.id(), packet.payload());
        io.papermc.paper.adventure.providers.ClickCallbackProviderImpl.ADVENTURE_CLICK_MANAGER.tryRunCallback(this.getAudience(), packet.id(), packet.payload());
        // Paper end - Implement click callbacks with custom click action
    }

    @Redirect(method = "handleResourcePackResponse", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"))
    private void paper$resourcePackRejectionCause(final ServerCommonPacketListenerImpl self, final Component reason) {
        this.disconnect(reason, io.papermc.paper.connection.DisconnectionReason.RESOURCE_PACK_REJECTION); // Paper - kick event cause
    }

    @Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
    private void paper$resourcePackCallbacks(final ServerboundResourcePackPacket packet, final CallbackInfo ci) {
        // Paper start - adventure pack callbacks
        // call the callbacks before the previously-existing event so the event has final say
        final net.kyori.adventure.resource.ResourcePackCallback callback;
        if (packet.action().isTerminal()) {
            callback = this.packCallbacks.remove(packet.id());
        } else {
            callback = this.packCallbacks.get(packet.id());
        }
        if (callback != null) {
            net.kyori.adventure.audience.Audience audience = switch ((Object) this) {
                case ServerGamePacketListenerImpl serverGamePacketListener -> serverGamePacketListener.getCraftPlayer();
                case ServerConfigurationPacketListenerImpl configurationPacketListener -> configurationPacketListener.paperConnection.getAudience();
                default -> throw new IllegalStateException("Unexpected value: " + this);
            };

            callback.packEventReceived(packet.id(), net.kyori.adventure.resource.ResourcePackStatus.valueOf(packet.action().name()), audience);
        }
        // Paper end
    }

    @Inject(method = "handleCookieResponse", at = @At("HEAD"), cancellable = true)
    private void paper$handleCookieResponse(final ServerboundCookieResponsePacket packet, final CallbackInfo ci) {
        ci.cancel();
        if (this.paperConnection().handleCookieResponse(packet)) return; // Paper
        this.disconnect(DISCONNECT_UNEXPECTED_QUERY, io.papermc.paper.connection.DisconnectionReason.INVALID_COOKIE); // Paper - kick event cause
    }

    // Paper start - give clients a longer time to respond to pings as per pre 1.12.2 timings
    // This should effectively place the keepalive handling back to "as it was" before 1.12.2
    @Redirect(method = "keepConnectionAlive", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"))
    private void paper$keepAliveLimit(final ServerCommonPacketListenerImpl self, final Component reason) {
        final long elapsedTime = net.minecraft.util.Util.getMillis() - this.keepAliveTime;
        if (elapsedTime >= KEEPALIVE_LIMIT) { // check keepalive limit, don't fire if already disconnected
            this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE, io.papermc.paper.connection.DisconnectionReason.TIMEOUT); // Paper - kick event cause
        }
    }
    // Paper end - give clients a longer time to respond to pings as per pre 1.12.2 timings

    @Redirect(method = "checkIfClosed", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"))
    private void paper$closedTimeoutCause(final ServerCommonPacketListenerImpl self, final Component reason) {
        this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE, io.papermc.paper.connection.DisconnectionReason.TIMEOUT); // Paper - kick event cause
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final CallbackInfo ci) {
        // CraftBukkit start
        if (packet == null) {
            ci.cancel();
            return;
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket defaultSpawnPositionPacket && (Object) this instanceof ServerGamePacketListenerImpl serverGamePacketListener) {
            serverGamePacketListener.player.compassTarget = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(defaultSpawnPositionPacket.respawnData().pos(), serverGamePacketListener.getPlayer().level());
        }
        // CraftBukkit end
    }

    // Paper start - kick event causes
    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void paper$disconnectUnknownCause(final Component reason, final CallbackInfo ci) {
        ci.cancel();
        this.disconnect(reason, io.papermc.paper.connection.DisconnectionReason.UNKNOWN);
    }
    // Paper end - kick event causes

    // Paper: replaced body (PlayerKickEvent, main-thread hop, leave message, deferred disconnection handling)
    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$disconnect(final DisconnectionDetails details, final CallbackInfo ci) {
        ci.cancel();
        // CraftBukkit start - fire PlayerKickEvent
        if (!this.connection.isConnected()) {
            return;
        }
        if (!this.cserver.isPrimaryThread()) {
            final ServerCommonPacketListenerImpl self = (ServerCommonPacketListenerImpl) (Object) this; // FissionMC: anonymous class must not capture the mixin instance
            org.bukkit.craftbukkit.util.Waitable<Object> waitable = new net.fissionmc.backend.network.SupplierWaitable<>(() -> { // FissionMC: named class instead of Paper's anonymous Waitable
                self.disconnect(details);
                return null;
            });

            this.server.processQueue.add(waitable);

            try {
                waitable.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException(e);
            }
            return;
        }

        Component reason;
        Component leaveMessage;
        if ((Object) this instanceof ServerGamePacketListenerImpl serverGamePacketListener) {
            net.kyori.adventure.text.Component rawLeaveMessage = net.kyori.adventure.text.Component.translatable("multiplayer.player.left", net.kyori.adventure.text.format.NamedTextColor.YELLOW, io.papermc.paper.configuration.GlobalConfiguration.get().messages.useDisplayNameInQuitMessage ? serverGamePacketListener.player.getBukkitEntity().displayName() : net.kyori.adventure.text.Component.text(serverGamePacketListener.player.getScoreboardName())); // Paper - Adventure

            net.minecraft.server.level.ServerPlayer player = serverGamePacketListener.player;
            org.bukkit.event.player.PlayerKickEvent.Cause cause = details.disconnectionReason().flatMap(io.papermc.paper.connection.DisconnectionReason::game).orElse(org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN); // FissionMC: Paper uses orElseThrow(); vanilla code paths may still create details without a reason
            org.bukkit.event.player.PlayerKickEvent event = new org.bukkit.event.player.PlayerKickEvent(
                    player.getBukkitEntity(),
                    io.papermc.paper.adventure.PaperAdventure.asAdventure(details.reason()),
                    rawLeaveMessage, cause

            );

            if (this.cserver.getServer().isRunning()) {
                this.cserver.getPluginManager().callEvent(event);
            }

            if (event.isCancelled()) {
                // Do not kick the player
                return;
            }

            reason = io.papermc.paper.adventure.PaperAdventure.asVanilla(event.reason());
            leaveMessage = io.papermc.paper.adventure.PaperAdventure.asVanilla(event.leaveMessage());
            serverGamePacketListener.player.quitReason = org.bukkit.event.player.PlayerQuitEvent.QuitReason.KICKED; // Paper - Add API for quit reason
            // Log kick to console *after* event was processed.
            switch (cause) {
                case FLYING_PLAYER -> LOGGER.warn("{} was kicked for floating too long!", player.getPlainTextName());
                case FLYING_VEHICLE -> LOGGER.warn("{} was kicked for floating a vehicle too long!", player.getPlainTextName());
            }
        } else {
            // TODO: Add event for config event
            reason = details.reason();
            leaveMessage = null;
        }

        // Send the possibly modified leave message
        this.disconnect0(DisconnectionDetails.create(reason, details.report(), details.bugReportLink(), java.util.Optional.ofNullable(leaveMessage), details.disconnectionReason()));
    }

    private void disconnect0(final DisconnectionDetails details) {
        this.connection.send(new ClientboundDisconnectPacket(details.reason()), PacketSendListener.thenRun(() -> this.connection.disconnect(details)));
        this.connection.setReadOnly();
        this.connection.handleConnectionDisconnectOnNextTick = true; // Paper - Force kill connection ticking. Let this close the connection
    }

    public void disconnect(Component reason, io.papermc.paper.connection.DisconnectionReason cause) {
        // Paper start - kick event causes
        this.disconnect(DisconnectionDetails.create(reason, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(cause)));
        // Paper end - kick event causes
    }

    // Paper start - add proper async disconnect
    public final void disconnectAsync(Component component, io.papermc.paper.connection.DisconnectionReason reason) {
        this.disconnectAsync(DisconnectionDetails.create(component, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason)));
    }

    @Deprecated // Paper: also @io.papermc.paper.annotation.DoNotUse
    public final void disconnectAsync(Component component) {
        this.disconnectAsync(component, io.papermc.paper.connection.DisconnectionReason.UNKNOWN);
    }

    // Paper: abstract
    public void disconnectAsync(DisconnectionDetails disconnectionInfo) {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }

    public boolean isTransferred() {
        return this.transferred;
    }

    // Paper: abstract
    public io.papermc.paper.connection.PaperCommonConnection<?> paperConnection() {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }
    // Paper end - add proper async disconnect
}
