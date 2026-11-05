package net.fissionmc.backend.mixin.server.network;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.Set;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin extends ServerCommonPacketListenerImpl {
    @Shadow @Final private static Logger LOGGER;
    @Shadow public ServerPlayer player;

    @Shadow public abstract void teleport(PositionMoveRotation destination, Set<Relative> relatives);
    @Shadow private void performUnsignedChatCommand(final String command) { throw new AssertionError(); }
    @Shadow @Final private net.minecraft.util.FutureChain chatMessageChain;
    @Shadow private boolean waitingForSwitchToConfig;

    protected ServerGamePacketListenerImplMixin(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    private boolean justTeleported = false; // CraftBukkit // TODO(behaviour): read in handleMovePlayer
    private io.papermc.paper.event.packet.ClientTickEndEvent tickEndEvent; // Paper - add client tick end event // TODO(behaviour): fired in handleClientTickEnd
    // Surface: public final. Mixin requires private fields; the loader widens it.
    private io.papermc.paper.connection.PaperPlayerGameConnection playerGameConnection; // Paper

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$init(final MinecraftServer server, final Connection connection, final ServerPlayer player, final CommonListenerCookie cookie, final CallbackInfo ci) {
        // TODO(behaviour): Paper uses server.chatExecutor for the chat FutureChain (async chat)
        this.tickEndEvent = new io.papermc.paper.event.packet.ClientTickEndEvent(player.getBukkitEntity()); // Paper - add client tick end event
        this.playerGameConnection = new io.papermc.paper.connection.PaperPlayerGameConnection((ServerGamePacketListenerImpl) (Object) this); // Paper
    }

    // Paper start - configuration phase API
    @Override
    public io.papermc.paper.connection.PlayerCommonConnection getApiConnection() {
        return this.playerGameConnection;
    }

    @Override
    public net.kyori.adventure.audience.Audience getAudience() {
        return this.getCraftPlayer();
    }
    // Paper end - configuration phase API

    // CraftBukkit start
    // TODO(behaviour): vanilla teleport(double x5) / teleport(PositionMoveRotation, Set) should delegate here with TeleportCause.UNKNOWN
    public boolean teleport(double x, double y, double z, float yRot, float xRot, PlayerTeleportEvent.TeleportCause cause) {
        return this.teleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Collections.emptySet(), cause);
        // CraftBukkit end
    }

    public boolean teleport(PositionMoveRotation destination, Set<Relative> relatives, PlayerTeleportEvent.TeleportCause cause) { // CraftBukkit - Return event status
        org.bukkit.entity.Player player = this.getCraftPlayer();
        Location from = player.getLocation();
        PositionMoveRotation absolutePosition = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(this.player), destination, relatives);
        Location to = CraftLocation.toBukkit(absolutePosition.position(), this.player.level(), absolutePosition.yRot(), absolutePosition.xRot());
        // SPIGOT-5171: Triggered on join
        if (from.equals(to)) {
            this.internalTeleport(destination, relatives);
            return true; // CraftBukkit - Return event status
        }

        // Paper start - Teleport API
        final Set<io.papermc.paper.entity.TeleportFlag.Relative> relativeFlags = java.util.EnumSet.noneOf(io.papermc.paper.entity.TeleportFlag.Relative.class);
        for (final Relative relativeArgument : relatives) {
            final io.papermc.paper.entity.TeleportFlag.Relative flag = org.bukkit.craftbukkit.entity.CraftEntity.deltaRelativeToAPI(relativeArgument);
            if (flag != null) relativeFlags.add(flag);
        }
        PlayerTeleportEvent event = new PlayerTeleportEvent(player, from.clone(), to.clone(), cause, java.util.Set.copyOf(relativeFlags));
        // Paper end - Teleport API
        this.cserver.getPluginManager().callEvent(event);

        if (event.isCancelled() || !to.equals(event.getTo())) {
            relatives = Set.of(); // target pos is absolute
            to = event.isCancelled() ? event.getFrom() : event.getTo();
            destination = new PositionMoveRotation(CraftLocation.toVec3(to), Vec3.ZERO, to.getYaw(), to.getPitch());
        }

        this.internalTeleport(destination, relatives);
        return !event.isCancelled(); // CraftBukkit - Return event status
    }

    public void internalTeleport(Location dest) {
        this.internalTeleport(dest.getX(), dest.getY(), dest.getZ(), dest.getYaw(), dest.getPitch());
    }

    public void internalTeleport(double x, double y, double z, float yRot, float xRot) {
        this.internalTeleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Collections.emptySet());
    }

    public void internalTeleport(PositionMoveRotation destination, Set<Relative> relatives) {
        org.spigotmc.AsyncCatcher.catchOp("teleport"); // Paper
        // Paper start - Prevent teleporting dead entities
        if (this.player.isRemoved()) {
            LOGGER.info("Attempt to teleport removed player {} restricted", this.player.getScoreboardName());
            if (this.server.isDebugging()) io.papermc.paper.util.TraceUtil.dumpTraceForThread("Attempt to teleport removed player");
            return;
        }
        // Paper end - Prevent teleporting dead entities
        if (Float.isNaN(destination.yRot())) {
            destination = new PositionMoveRotation(destination.position(), destination.deltaMovement(), 0, destination.xRot());
        }
        if (Float.isNaN(destination.xRot())) {
            destination = new PositionMoveRotation(destination.position(), destination.deltaMovement(), destination.yRot(), 0);
        }

        this.justTeleported = true;
        // CraftBukkit end
        // TODO(behaviour): Paper moves the vanilla teleport(PositionMoveRotation, Set) body here and makes that method fire
        // PlayerTeleportEvent. Until then the vanilla (event-free) method is the internal teleport; once it fires the event,
        // inline its body here instead.
        this.teleport(destination, relatives);
    }

    // CraftBukkit start
    public void chat(String msg, PlayerChatMessage original, boolean async) {
        if (msg.isEmpty() || this.player.getChatVisibility() == ChatVisiblity.HIDDEN) {
            return;
        }

        if (this.player.getChatVisibility() == ChatVisiblity.SYSTEM) {
            // Do nothing, this is coming from a plugin
            // Paper start
        } else {
            if (!async && !org.bukkit.Bukkit.isPrimaryThread()) {
                org.spigotmc.AsyncCatcher.catchOp("Asynchronous player chat is not allowed here");
            }
            new io.papermc.paper.adventure.ChatProcessor(this.server, this.player, original, async).process();
            // Paper end
        }
    }

    @Deprecated // Paper
    public void handleCommand(String command) {
        // Paper start - Remove all this old duplicated logic
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        /*
        It should be noted that this represents the "legacy" command execution path.
        Api can call commands even if there is no additional context provided.
        This method should ONLY be used if you need to execute a command WITHOUT
        an actual player's input.
        */
        this.performUnsignedChatCommand(command);
        // Paper end
    }
    // CraftBukkit end

    // Spigot start
    public SocketAddress getRawAddress() {
        // Paper start - Unix domain socket support; this can be nullable in the case of a Unix domain socket, so if it is, fake something
        if (this.connection.channel.remoteAddress() == null) {
            return new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0);
        }
        // Paper end - Unix domain socket support
        return this.connection.channel.remoteAddress();
    }
    // Spigot end

    // Paper start - Inventory close reason
    // TODO(behaviour): vanilla handleContainerClose(packet) should delegate here with Reason.PLAYER
    public void handleContainerClose(ServerboundContainerClosePacket packet, org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        // Paper end - Inventory close reason
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, this.player.level());

        if (this.player.isImmobile()) return; // CraftBukkit
        CraftEventFactory.handleInventoryCloseEvent(this.player, reason); // CraftBukkit // Paper
        this.player.doCloseContainer();
    }

    // Paper start - add utilities
    public org.bukkit.craftbukkit.entity.CraftPlayer getCraftPlayer() {
        return this.player == null ? null : this.player.getBukkitEntity();
    }

    @Override
    public void disconnectAsync(final DisconnectionDetails disconnectionInfo) {
        if (this.cserver.isPrimaryThread()) {
            this.disconnect(disconnectionInfo);
            return;
        }

        this.connection.setReadOnly();
        this.server.scheduleOnMain(() -> {
            this.disconnect(disconnectionInfo);
            if (this.player.quitReason == null) {
                // cancelled
                this.connection.enableAutoRead();
            }
        });
    }

    public final boolean isDisconnected() {
        return (!this.player.joining && !this.connection.isConnected());
    }

    @Override
    public void handleResourcePackResponse(final ServerboundResourcePackPacket packet) {
        super.handleResourcePackResponse(packet);
        // Paper start - store last pack status
        org.bukkit.event.player.PlayerResourcePackStatusEvent.Status packStatus = org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.values()[packet.action().ordinal()];
        this.connection.resourcePackStatus = packStatus;
        this.cserver.getPluginManager().callEvent(new org.bukkit.event.player.PlayerResourcePackStatusEvent(this.getCraftPlayer(), packet.id(), packStatus)); // CraftBukkit
        // Paper end - store last pack status
    }

    // Paper start - adventure
    public void disconnect(final net.kyori.adventure.text.Component reason) {
        this.disconnect(reason, org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN);
    }

    public void disconnect(final net.kyori.adventure.text.Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(reason), io.papermc.paper.connection.DisconnectionReason.game(cause));
    }

    public void disconnect(final Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnect(reason, io.papermc.paper.connection.DisconnectionReason.game(cause));
    }

    public void disconnectAsync(net.kyori.adventure.text.Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnectAsync(io.papermc.paper.adventure.PaperAdventure.asVanilla(reason), cause);
    }

    public void disconnectAsync(Component reason, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
        this.disconnectAsync(reason, io.papermc.paper.connection.DisconnectionReason.game(cause));
    }

    @Override
    public io.papermc.paper.connection.PaperPlayerGameConnection paperConnection() {
        return this.playerGameConnection;
    }
    // Paper end

    // Paper: onDisconnect / removePlayerFromWorld (quit message from PlayerQuitEvent / kick event)
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void paper$onDisconnect(final DisconnectionDetails details, final CallbackInfo ci) {
        ci.cancel();
        LOGGER.info("{} lost connection: {}", this.player.getPlainTextName(), details.reason().getString());
        // Paper start - Fix kick event leave message not being sent
        final net.kyori.adventure.text.Component quitMessage = details.quitMessage().map(io.papermc.paper.adventure.PaperAdventure::asAdventure).orElse(null);
        if (!this.waitingForSwitchToConfig) this.removePlayerFromWorld(quitMessage); // Paper - Don't remove player twice when disconnected during config switch
        // Paper end - Fix kick event leave message not being sent
        super.onDisconnect(details);
    }

    @Inject(method = "removePlayerFromWorld()V", at = @At("HEAD"), cancellable = true)
    private void paper$removePlayerFromWorld(final CallbackInfo ci) {
        ci.cancel();
        // Paper start - Fix kick event leave message not being sent
        this.removePlayerFromWorld(null);
    }

    private void removePlayerFromWorld(net.kyori.adventure.text.@org.jspecify.annotations.Nullable Component quitMessage) {
        // Paper end - Fix kick event leave message not being sent
        this.chatMessageChain.close();
        // CraftBukkit start - Replace vanilla quit message handling with our own.
        /*
        this.server.invalidateStatus();
        this.server
            .getPlayerList()
            .broadcastSystemMessage(Component.translatable("multiplayer.player.left", this.player.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
         */
        this.player.disconnect();
        // Paper start - Adventure
        quitMessage = quitMessage == null ? this.server.getPlayerList().fission$remove(this.player) : this.server.getPlayerList().fission$remove(this.player, quitMessage); // Paper - pass in quitMessage to fix kick message not being used
        if ((quitMessage != null) && !quitMessage.equals(net.kyori.adventure.text.Component.empty())) {
            this.server.getPlayerList().broadcastSystemMessage(io.papermc.paper.adventure.PaperAdventure.asVanilla(quitMessage), false);
            // Paper end - Adventure
        }
        // CraftBukkit end
        this.player.getTextFilter().leave();
    }
}
