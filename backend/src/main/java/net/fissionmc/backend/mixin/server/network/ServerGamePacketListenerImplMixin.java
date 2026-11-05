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
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import java.util.List;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.server.MinecraftServer;
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
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.Action;
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

    private boolean justTeleported = false; // CraftBukkit
    private io.papermc.paper.event.packet.ClientTickEndEvent tickEndEvent; // Paper - add client tick end event
    // CraftBukkit start - add fields
    // FissionMC: MinecraftServer.currentTick is maintained by the server area; use the same clock (System.currentTimeMillis() / 50) locally.
    private int lastTick = (int) (System.currentTimeMillis() / 50);
    private int allowedPlayerTicks = 1;
    private int lastDropTick = (int) (System.currentTimeMillis() / 50);
    private int lastBookTick = (int) (System.currentTimeMillis() / 50);
    private int dropCount = 0;
    private boolean hasMoved = false;
    private double lastPosX = Double.MAX_VALUE;
    private double lastPosY = Double.MAX_VALUE;
    private double lastPosZ = Double.MAX_VALUE;
    private float lastYRot = Float.MAX_VALUE;
    private float lastXRot = Float.MAX_VALUE;
    // CraftBukkit end
    private boolean fission$internalTeleporting;
    private double fission$startX, fission$startY, fission$startZ;
    private int limitedPackets; // Paper - limit place/interactions
    private long lastLimitedPacket = -1;
    @Shadow private net.minecraft.world.phys.Vec3 awaitingPositionFromClient;
    @Shadow private int clientLoadedTimeoutTimer;
    @Shadow private void detectChatRateSpam() { throw new AssertionError(); }
    @Shadow private void broadcastChatMessage(final PlayerChatMessage message) { throw new AssertionError(); }
    @Shadow public abstract void ackBlockChangesUpTo(int sequence);
    @Shadow public abstract boolean hasClientLoaded();
    @Shadow public abstract void handleContainerClick(ServerboundContainerClickPacket packet);
    // Surface: public final. Mixin requires private fields; the loader widens it.
    private io.papermc.paper.connection.PaperPlayerGameConnection playerGameConnection; // Paper

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$init(final MinecraftServer server, final Connection connection, final ServerPlayer player, final CommonListenerCookie cookie, final CallbackInfo ci) {
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
        // FissionMC: Paper moves the vanilla teleport(PositionMoveRotation, Set) body here; we call the vanilla body with the delegation guard set.
        this.fission$internalTeleporting = true;
        try {
            this.teleport(destination, relatives);
        } finally {
            this.fission$internalTeleporting = false;
        }
        // CraftBukkit start - update last location
        this.lastPosX = this.awaitingPositionFromClient.x;
        this.lastPosY = this.awaitingPositionFromClient.y;
        this.lastPosZ = this.awaitingPositionFromClient.z;
        this.lastYRot = this.player.getYRot();
        this.lastXRot = this.player.getXRot();
        // CraftBukkit end
    }

    // Paper/CraftBukkit: vanilla teleport(PositionMoveRotation, Set) delegates to the cause overload (TeleportCause.UNKNOWN)
    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("HEAD"), cancellable = true)
    private void paper$teleportDelegate(final PositionMoveRotation destination, final Set<Relative> relatives, final CallbackInfo ci) {
        if (this.fission$internalTeleporting) {
            return;
        }
        ci.cancel();
        this.teleport(destination, relatives, PlayerTeleportEvent.TeleportCause.UNKNOWN);
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

    // ===================================================================================================================
    // Wave 2: behaviour hunks (sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch)
    // ===================================================================================================================
    private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

    @Shadow private int ackBlockChangesUpTo;
    @Shadow private static double clampHorizontal(final double v) { throw new AssertionError(); }
    @Shadow private static double clampVertical(final double v) { throw new AssertionError(); }

    // Paper: BundleContents#getMaxAmountToAdd (added to the vanilla record by the blocks area)
    private static int fission$bundleMaxToAdd(final net.minecraft.world.item.component.BundleContents contents, final ItemStack stack) {
        // FissionMC: re-implemented (the vanilla helper is private); TODO(behaviour): bee-holding items count as a full bundle in vanilla
        final java.util.Optional<org.apache.commons.lang3.math.Fraction> weight = contents.weight().result();
        if (weight.isEmpty()) {
            return 0;
        }
        final org.apache.commons.lang3.math.Fraction itemWeight = stack.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)
            ? org.apache.commons.lang3.math.Fraction.getFraction(1, 16).add(stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS).weight().result().orElse(org.apache.commons.lang3.math.Fraction.ZERO))
            : org.apache.commons.lang3.math.Fraction.getFraction(1, stack.getMaxStackSize());
        return Math.max(org.apache.commons.lang3.math.Fraction.ONE.subtract(weight.get()).divideBy(itemWeight).intValue(), 0);
    }

    private static int fission$currentTick() {
        return (int) (System.currentTimeMillis() / 50);
    }

    // Paper - FutureChain uses the chat executor (async chat)
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/FutureChain;<init>(Ljava/util/concurrent/Executor;)V"))
    private java.util.concurrent.Executor paper$chatExecutor(final java.util.concurrent.Executor executor) {
        return executor instanceof MinecraftServer ms && ms.chatExecutor != null ? ms.chatExecutor : executor; // CraftBukkit - async chat
    }

    // ---- handlePlayerInput: PlayerInputEvent / PlayerToggleSneakEvent ----
    @Inject(method = "handlePlayerInput", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void paper$playerInput(final net.minecraft.network.protocol.game.ServerboundPlayerInputPacket packet, final CallbackInfo ci) {
        // CraftBukkit start
        if (!packet.input().equals(this.player.getLastClientInput())) {
            org.bukkit.event.player.PlayerInputEvent event = new org.bukkit.event.player.PlayerInputEvent(this.player.getBukkitEntity(), new org.bukkit.craftbukkit.CraftInput(packet.input()));
            this.cserver.getPluginManager().callEvent(event);
        }
        // CraftBukkit end
        // Paper start - PlayerToggleSneakEvent
        net.minecraft.world.entity.player.Input lastInput = this.player.getLastClientInput();
        if (lastInput.shift() != packet.input().shift()) {
            // Has sneak changed
            org.bukkit.event.player.PlayerToggleSneakEvent event = new org.bukkit.event.player.PlayerToggleSneakEvent(this.getCraftPlayer(), packet.input().shift());
            this.cserver.getPluginManager().callEvent(event);

            // Technically the player input and the flag is desynced, but this is previous behavior.. so should be fine?
            if (!event.isCancelled() && this.hasClientLoaded()) {
                // Only set the shift key status if the shift input has changed and the event has not been cancelled
                this.player.setShiftKeyDown(packet.input().shift());
            }
        }
        // Paper end - PlayerToggleSneakEvent
    }

    // Paper - move up and only set if event is not cancelled.
    @WrapWithCondition(method = "handlePlayerInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setShiftKeyDown(Z)V"))
    private boolean paper$noVanillaShift(final ServerPlayer player, final boolean shift) {
        return false;
    }

    // ---- handleMoveVehicle ----
    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getRootVehicle()Lnet/minecraft/world/entity/Entity;", shift = At.Shift.AFTER), cancellable = true)
    private void paper$moveVehicleStart(final net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet, final CallbackInfo ci) {
        // Paper start - Don't allow vehicle movement from players while teleporting
        if (this.awaitingPositionFromClient != null || this.player.isImmobile() || this.player.getRootVehicle().isRemoved()) {
            ci.cancel();
            return;
        }
        // Paper end - Don't allow vehicle movement from players while teleporting
        // CraftBukkit - store current player position
        this.fission$startYRot = this.player.getYRot();
        this.fission$startXRot = this.player.getXRot();
        this.fission$startX = this.player.getX();
        this.fission$startY = this.player.getY();
        this.fission$startZ = this.player.getZ();
    }
    private float fission$startYRot, fission$startXRot;

    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;absSnapTo(DDDFF)V", ordinal = 1), cancellable = true)
    private void paper$moveVehicleEvent(final net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet, final CallbackInfo ci,
                                        @Local(ordinal = 3) final double targetX, @Local(ordinal = 4) final double targetY, @Local(ordinal = 5) final double targetZ,
                                        @Local(ordinal = 0) final float targetYRot, @Local(ordinal = 1) final float targetXRot) {
        // CraftBukkit start - fire PlayerMoveEvent TODO: this should be removed.
        this.player.absSnapTo(targetX, targetY, targetZ, this.player.getYRot(), this.player.getXRot()); // Paper - TODO: This breaks alot of stuff
        if (this.fission$callPlayerMove(targetX, targetY, targetZ, targetYRot, targetXRot, this.fission$startX, this.fission$startY, this.fission$startZ, this.fission$startYRot, this.fission$startXRot)) {
            ci.cancel();
        }
        // CraftBukkit end
    }

    @ModifyConstant(method = {"handleMoveVehicle", "handlePlayerPositionChange"}, constant = @Constant(doubleValue = 0.0625))
    private double paper$movedWronglyThreshold(final double vanilla) {
        return org.spigotmc.SpigotConfig.movedWronglyThreshold; // Spigot
    }

    /** CraftBukkit PlayerMoveEvent logic shared by player and vehicle movement. Returns true if the caller must return. */
    private boolean fission$callPlayerMove(final double targetX, final double targetY, final double targetZ, final float targetYRot, final float targetXRot,
                                           final double startX, final double startY, final double startZ, final float startYRot, final float startXRot) {
        org.bukkit.entity.Player player = this.getCraftPlayer();
        if (!this.hasMoved) {
            this.lastPosX = startX;
            this.lastPosY = startY;
            this.lastPosZ = startZ;
            this.lastYRot = startYRot;
            this.lastXRot = startXRot;
            this.hasMoved = true;
        }
        Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot); // Get the Players previous Event location.
        Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);

        // Prevent 40 event-calls for less than a single pixel of movement >.>
        double delta = Mth.square(this.lastPosX - to.getX()) + Mth.square(this.lastPosY - to.getY()) + Mth.square(this.lastPosZ - to.getZ());
        float deltaAngle = Math.abs(Mth.wrapDegrees(this.lastYRot - to.getYaw())) + Math.abs(this.lastXRot - to.getPitch());

        if ((delta > 1.0F / 256 || deltaAngle > 10.0F) && !this.player.isImmobile()) {
            this.lastPosX = to.getX();
            this.lastPosY = to.getY();
            this.lastPosZ = to.getZ();
            this.lastYRot = to.getYaw();
            this.lastXRot = to.getPitch();

            Location oldTo = to.clone();
            org.bukkit.event.player.PlayerMoveEvent event = new org.bukkit.event.player.PlayerMoveEvent(player, from, to);
            this.cserver.getPluginManager().callEvent(event);

            // If the event is cancelled we move the player back to their old location.
            if (event.isCancelled()) {
                this.internalTeleport(from);
                return true;
            }

            // If a Plugin has changed the To destination then we teleport the Player
            // there to avoid any 'Moved wrongly' or 'Moved too quickly' errors.
            // We only do this if the Event was not cancelled.
            if (!oldTo.equals(event.getTo()) && !event.isCancelled()) {
                this.player.getBukkitEntity().teleport(event.getTo(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                return true;
            }

            // Check to see if the Players Location has some how changed during the call of the event.
            // This can happen due to a plugin teleporting the player instead of using .setTo()
            if (!from.equals(this.getCraftPlayer().getLocation()) && this.justTeleported) {
                this.justTeleported = false;
                return true;
            }
        }
        return false;
    }

    // ---- handleMovePlayer / handlePlayerPositionChange ----
    // CraftBukkit: immobile players do not move
    @ModifyExpressionValue(method = "handleMovePlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayer;wonGame:Z"))
    private boolean paper$immobileMove(final boolean wonGame) {
        return wonGame || this.player.isImmobile();
    }

    @Inject(method = "handlePlayerPositionChange", at = @At("HEAD"))
    private void paper$positionChangeHead(final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround, final boolean horizontalCollision, final CallbackInfo ci) {
        this.fission$startYRot = this.player.getYRot();
        this.fission$startXRot = this.player.getXRot();
        this.fission$startX = this.player.getX();
        this.fission$startY = this.player.getY();
        this.fission$startZ = this.player.getZ();
    }

    @Inject(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;move(Lnet/minecraft/server/level/ServerPlayer;)V", ordinal = 0))
    private void paper$passengerMove(final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround, final boolean horizontalCollision, final CallbackInfo ci) {
        this.allowedPlayerTicks = 20; // CraftBukkit
    }

    // Paper - Add PlayerJumpEvent
    @Inject(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;jumpFromGround()V"), cancellable = true)
    private void paper$jumpEvent(final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround, final boolean horizontalCollision, final CallbackInfo ci) {
        org.bukkit.entity.Player player = this.getCraftPlayer();
        Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot); // Get the Players previous Event location.
        Location to = new Location(player.getWorld(), clampHorizontal(x), clampVertical(y), clampHorizontal(z), Mth.wrapDegrees(yRot), Mth.wrapDegrees(xRot));

        com.destroystokyo.paper.event.player.PlayerJumpEvent event = new com.destroystokyo.paper.event.player.PlayerJumpEvent(player, from, to);

        if (!event.callEvent()) {
            from = event.getFrom();
            this.internalTeleport(new PositionMoveRotation(CraftLocation.toVec3(from), Vec3.ZERO, from.getYaw(), from.getPitch()), Collections.emptySet());
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", shift = At.Shift.AFTER), cancellable = true)
    private void paper$afterMove(final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround, final boolean horizontalCollision, final CallbackInfo ci) {
        this.player.onGround = onGround; // CraftBukkit - SPIGOT-5810, SPIGOT-5835, SPIGOT-6828: reset by this.player.move
        // Paper start - prevent position desync
        if (this.awaitingPositionFromClient != null) {
            ci.cancel(); // ... thanks Mojang for letting move calls teleport across dimensions.
        }
        // Paper end - prevent position desync
    }

    @Inject(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;absSnapTo(DDDFF)V", ordinal = 1), cancellable = true)
    private void paper$moveEvent(final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround, final boolean horizontalCollision, final CallbackInfo ci) {
        // CraftBukkit start - fire PlayerMoveEvent
        // Reset to old location first
        this.player.absSnapTo(this.fission$startX, this.fission$startY, this.fission$startZ, this.fission$startYRot, this.fission$startXRot);
        if (this.fission$callPlayerMove(clampHorizontal(x), clampVertical(y), clampHorizontal(z), Mth.wrapDegrees(yRot), Mth.wrapDegrees(xRot),
            this.fission$startX, this.fission$startY, this.fission$startZ, this.fission$startYRot, this.fission$startXRot)) {
            ci.cancel();
        }
        // CraftBukkit end
    }

    // CraftBukkit - SPIGOT-1807: Don't call teleport event, when the client thinks the player is falling, because the chunks are not loaded on the client yet.
    @WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V", ordinal = 2))
    private void paper$internalTeleportOnFail(final ServerGamePacketListenerImpl self, final double x, final double y, final double z, final float yRot, final float xRot, final Operation<Void> original) {
        this.internalTeleport(x, y, z, yRot, xRot);
    }

    @Inject(method = "handleSetCarriedItem", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$heldItem(final net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) { ci.cancel(); return; } // CraftBukkit
        if (packet.getSlot() >= 0 && packet.getSlot() < net.minecraft.world.entity.player.Inventory.getSelectionSize()) {
            if (packet.getSlot() == this.player.getInventory().getSelectedSlot()) { ci.cancel(); return; } // Paper - don't fire itemheldevent when there wasn't a slot change
            org.bukkit.event.player.PlayerItemHeldEvent event = new org.bukkit.event.player.PlayerItemHeldEvent(this.getCraftPlayer(), this.player.getInventory().getSelectedSlot(), packet.getSlot());
            this.cserver.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                this.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(this.player.getInventory().getSelectedSlot()));
                this.player.resetLastActionTime();
                ci.cancel();
            }
        }
    }

    // ---- client loaded (Paper - Add PlayerLoadedWorldEvent) ----
    @Inject(method = "markClientLoaded", at = @At("HEAD"))
    private void paper$clientLoaded(final CallbackInfo ci) {
        if (!this.hasClientLoaded()) {
            new io.papermc.paper.event.player.PlayerClientLoadedWorldEvent(this.player.getBukkitEntity(), false).callEvent();
        }
    }

    @Inject(method = "tickClientLoadTimeout", at = @At("HEAD"))
    private void paper$clientLoadTimeout(final CallbackInfo ci) {
        if (this.clientLoadedTimeoutTimer == 1) {
            new io.papermc.paper.event.player.PlayerClientLoadedWorldEvent(this.player.getBukkitEntity(), true).callEvent();
        }
    }

    @Inject(method = "handleClientTickEnd", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void paper$clientTickEnd(final net.minecraft.network.protocol.game.ServerboundClientTickEndPacket packet, final CallbackInfo ci) {
        this.tickEndEvent.callEvent(); // Paper - add client tick end event
    }

    // ---- small packet events ----
    @Inject(method = "handleBundleItemSelectedPacket", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void paper$bundleSelect(final net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket packet, final CallbackInfo ci) {
        CraftEventFactory.callPlayerBundleItemSelectEvent(this.player, packet.slotId(), packet.selectedItemIndex()); // Paper - PlayerBundleItemSelectEvent
    }

    @Inject(method = "handleRecipeBookChangeSettingsPacket", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void paper$recipeBookSettings(final net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket packet, final CallbackInfo ci) {
        CraftEventFactory.callRecipeBookSettingsEvent(this.player, packet.getBookType(), packet.isOpen(), packet.isFiltering()); // CraftBukkit
    }

    @Inject(method = "handleSelectTrade", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$selectTrade(final net.minecraft.network.protocol.game.ServerboundSelectTradePacket packet, final CallbackInfo ci) {
        if (this.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu menu) {
            // CraftBukkit start
            final org.bukkit.event.inventory.TradeSelectEvent tradeSelectEvent = CraftEventFactory.callTradeSelectEvent(packet.getItem(), menu);
            if (tradeSelectEvent.isCancelled()) {
                this.player.containerMenu.sendAllDataToRemote();
                ci.cancel();
            }
            // CraftBukkit end
        }
    }

    @Inject(method = "handlePlayerAbilities", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$toggleFlight(final net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket packet, final CallbackInfo ci) {
        // CraftBukkit start
        if (this.player.getAbilities().mayfly && this.player.getAbilities().flying != packet.isFlying()) {
            org.bukkit.event.player.PlayerToggleFlightEvent event = new org.bukkit.event.player.PlayerToggleFlightEvent(this.player.getBukkitEntity(), packet.isFlying());
            this.cserver.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                this.player.getAbilities().flying = packet.isFlying(); // Actually set the player's flying status
            } else {
                this.player.onUpdateAbilities(); // Tell the player their ability was reverted
            }
        }
        ci.cancel(); // replaces `flying = packet.isFlying() && mayfly`
        // CraftBukkit end
    }

    @Inject(method = "handlePlayerCommand", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V", ordinal = 0), cancellable = true)
    private void paper$playerCommand(final net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet, final CallbackInfo ci) {
        // CraftBukkit start
        if (this.player.isRemoved()) { ci.cancel(); return; }
        switch (packet.getAction()) {
            case START_SPRINTING:
            case STOP_SPRINTING: {
                org.bukkit.event.player.PlayerToggleSprintEvent event = new org.bukkit.event.player.PlayerToggleSprintEvent(this.getCraftPlayer(), packet.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_SPRINTING);
                this.cserver.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    ci.cancel();
                }
                break;
            }
            default:
                break;
        }
        // CraftBukkit end
    }

    @Inject(method = "handleClientInformation", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;updateOptions(Lnet/minecraft/server/level/ClientInformation;)V"), cancellable = true)
    private void paper$clientInfoValidate(final net.minecraft.network.protocol.common.ServerboundClientInformationPacket packet, final CallbackInfo ci) {
        // Paper start - do not accept invalid information
        if (packet.information().viewDistance() < 0) {
            LOGGER.warn("Disconnecting {} for invalid view distance: {}", this.player.getScoreboardName(), packet.information().viewDistance());
            this.disconnect(Component.literal("Invalid client settings"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION);
            ci.cancel();
        }
        // Paper end - do not accept invalid information
    }

    @Inject(method = "handleClientInformation", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;updateOptions(Lnet/minecraft/server/level/ClientInformation;)V", shift = At.Shift.AFTER))
    private void paper$clientInfoLocale(final net.minecraft.network.protocol.common.ServerboundClientInformationPacket packet, final CallbackInfo ci) {
        this.connection.channel.attr(io.papermc.paper.adventure.PaperAdventure.LOCALE_ATTRIBUTE).set(net.kyori.adventure.translation.Translator.parseLocale(packet.information().language())); // Paper
    }

    // ---- respawn reasons (CraftBukkit) ----
    @WrapOperation(method = "handleClientCommand", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;respawn(Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/server/level/ServerPlayer;", ordinal = 0))
    private ServerPlayer paper$respawnEndPortal(final net.minecraft.server.players.PlayerList list, final ServerPlayer player, final boolean keepAll, final Entity.RemovalReason reason, final Operation<ServerPlayer> original) {
        return list.respawn(player, keepAll, reason, org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL); // CraftBukkit
    }

    @WrapOperation(method = "handleClientCommand", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;respawn(Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/server/level/ServerPlayer;", ordinal = 1))
    private ServerPlayer paper$respawnDeath(final net.minecraft.server.players.PlayerList list, final ServerPlayer player, final boolean keepAll, final Entity.RemovalReason reason, final Operation<ServerPlayer> original) {
        return list.respawn(player, keepAll, reason, org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH); // CraftBukkit
    }

    @WrapOperation(method = "handleClientCommand", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z"))
    private boolean paper$hardcoreGameMode(final ServerPlayer player, final net.minecraft.world.level.GameType mode, final Operation<Boolean> original) {
        player.setGameMode(mode, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH, null); // Paper - Expand PlayerGameModeChangeEvent
        return true;
    }

    // ---- container close / click ----
    @Inject(method = "handleContainerClose(Lnet/minecraft/network/protocol/game/ServerboundContainerClosePacket;)V", at = @At("HEAD"), cancellable = true)
    private void paper$containerCloseReason(final ServerboundContainerClosePacket packet, final CallbackInfo ci) {
        ci.cancel();
        this.handleContainerClose(packet, org.bukkit.event.inventory.InventoryCloseEvent.Reason.PLAYER); // Paper - Inventory close reason
    }

    @Inject(method = "handleContainerButtonClick", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$containerButtonImmobile(final net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) ci.cancel(); // CraftBukkit
    }

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$containerClickImmobile(final ServerboundContainerClickPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) ci.cancel(); // CraftBukkit
    }

    // CraftBukkit - pre cancelled when in spectator (the event is fired; vanilla's spectator resync is skipped)
    @ModifyExpressionValue(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean paper$containerClickSpectator(final boolean spectator) {
        return false;
    }

    @WrapOperation(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V"))
    private void paper$inventoryClickEvent(final net.minecraft.world.inventory.AbstractContainerMenu menu, final int slotIndex, final int buttonNum, final net.minecraft.world.inventory.ContainerInput containerInput, final net.minecraft.world.entity.player.Player clicker,
                                           final Operation<Void> original, @Local(argsOnly = true) final ServerboundContainerClickPacket packet) {
        boolean cancelled = this.player.isSpectator(); // CraftBukkit - pre cancelled when in spectator
        // CraftBukkit start - Call InventoryClickEvent
        if (slotIndex < -1 && slotIndex != net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
            return;
        }

        org.bukkit.inventory.InventoryView inventory = this.player.containerMenu.getBukkitView();
        org.bukkit.event.inventory.InventoryType.SlotType type = inventory.getSlotType(slotIndex);

        org.bukkit.event.inventory.InventoryClickEvent event;
        org.bukkit.event.inventory.ClickType click = org.bukkit.event.inventory.ClickType.UNKNOWN;
        org.bukkit.event.inventory.InventoryAction action = org.bukkit.event.inventory.InventoryAction.UNKNOWN;

        switch (packet.containerInput()) {
            case PICKUP:
                if (packet.buttonNum() == 0) {
                    click = org.bukkit.event.inventory.ClickType.LEFT;
                } else if (packet.buttonNum() == 1) {
                    click = org.bukkit.event.inventory.ClickType.RIGHT;
                }
                if (packet.buttonNum() == 0 || packet.buttonNum() == 1) {
                    action = org.bukkit.event.inventory.InventoryAction.NOTHING; // Don't want to repeat ourselves
                    if (slotIndex == net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
                        if (!this.player.containerMenu.getCarried().isEmpty()) {
                            action = packet.buttonNum() == 0 ? org.bukkit.event.inventory.InventoryAction.DROP_ALL_CURSOR : org.bukkit.event.inventory.InventoryAction.DROP_ONE_CURSOR;
                        }
                    } else if (slotIndex < 0) {
                        action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                    } else {
                        net.minecraft.world.inventory.Slot slot = this.player.containerMenu.getSlot(slotIndex);
                        if (slot != null) {
                            ItemStack clickedItem = slot.getItem();
                            ItemStack cursor = this.player.containerMenu.getCarried();
                            if (clickedItem.isEmpty()) {
                                if (!cursor.isEmpty()) {
                                    if (cursor.getItem() instanceof net.minecraft.world.item.BundleItem && cursor.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() != 0) {
                                        action = cursor.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS).isEmpty() ? org.bukkit.event.inventory.InventoryAction.NOTHING : org.bukkit.event.inventory.InventoryAction.PLACE_FROM_BUNDLE;
                                    } else {
                                        action = packet.buttonNum() == 0 ? org.bukkit.event.inventory.InventoryAction.PLACE_ALL : org.bukkit.event.inventory.InventoryAction.PLACE_ONE;
                                    }
                                }
                            } else if (slot.mayPickup(this.player)) {
                                if (cursor.isEmpty()) {
                                    if (slot.getItem().getItem() instanceof net.minecraft.world.item.BundleItem && slot.getItem().has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() != 0) {
                                        action = slot.getItem().get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS).isEmpty() ? org.bukkit.event.inventory.InventoryAction.NOTHING : org.bukkit.event.inventory.InventoryAction.PICKUP_FROM_BUNDLE;
                                    } else {
                                        action = packet.buttonNum() == 0 ? org.bukkit.event.inventory.InventoryAction.PICKUP_ALL : org.bukkit.event.inventory.InventoryAction.PICKUP_HALF;
                                    }
                                } else if (slot.mayPlace(cursor)) {
                                    if (ItemStack.isSameItemSameComponents(clickedItem, cursor)) {
                                        int toPlace = packet.buttonNum() == 0 ? cursor.getCount() : 1;
                                        toPlace = Math.min(toPlace, clickedItem.getMaxStackSize() - clickedItem.getCount());
                                        toPlace = Math.min(toPlace, slot.container.getMaxStackSize() - clickedItem.getCount());
                                        if (toPlace == 1) {
                                            action = org.bukkit.event.inventory.InventoryAction.PLACE_ONE;
                                        } else if (toPlace == cursor.getCount()) {
                                            action = org.bukkit.event.inventory.InventoryAction.PLACE_ALL;
                                        } else if (toPlace < 0) {
                                            action = toPlace != -1 ? org.bukkit.event.inventory.InventoryAction.PICKUP_SOME : org.bukkit.event.inventory.InventoryAction.PICKUP_ONE; // this happens with oversized stacks
                                        } else if (toPlace != 0) {
                                            action = org.bukkit.event.inventory.InventoryAction.PLACE_SOME;
                                        }
                                    } else if (cursor.getCount() <= slot.getMaxStackSize()) {
                                        if (cursor.getItem() instanceof net.minecraft.world.item.BundleItem && cursor.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() == 0) {
                                            int toPickup = fission$bundleMaxToAdd(cursor.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS), slot.getItem());
                                            if (toPickup >= slot.getItem().getCount()) {
                                                action = org.bukkit.event.inventory.InventoryAction.PICKUP_ALL_INTO_BUNDLE;
                                            } else if (toPickup == 0) {
                                                action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                                            } else {
                                                action = org.bukkit.event.inventory.InventoryAction.PICKUP_SOME_INTO_BUNDLE;
                                            }
                                        } else if (slot.getItem().getItem() instanceof net.minecraft.world.item.BundleItem && slot.getItem().has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() == 0) {
                                            int toPickup = fission$bundleMaxToAdd(slot.getItem().get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS), cursor);
                                            if (toPickup >= cursor.getCount()) {
                                                action = org.bukkit.event.inventory.InventoryAction.PLACE_ALL_INTO_BUNDLE;
                                            } else if (toPickup == 0) {
                                                action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                                            } else {
                                                action = org.bukkit.event.inventory.InventoryAction.PLACE_SOME_INTO_BUNDLE;
                                            }
                                        } else {
                                            action = org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR;
                                        }
                                    }
                                } else if (ItemStack.isSameItemSameComponents(cursor, clickedItem)) {
                                    if (clickedItem.getCount() >= 0) {
                                        if (clickedItem.getCount() + cursor.getCount() <= cursor.getMaxStackSize()) {
                                            // As of 1.5, this is result slots only
                                            action = org.bukkit.event.inventory.InventoryAction.PICKUP_ALL;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                break;
            // TODO check on updates
            case QUICK_MOVE:
                if (packet.buttonNum() == 0) {
                    click = org.bukkit.event.inventory.ClickType.SHIFT_LEFT;
                } else if (packet.buttonNum() == 1) {
                    click = org.bukkit.event.inventory.ClickType.SHIFT_RIGHT;
                }
                if (packet.buttonNum() == 0 || packet.buttonNum() == 1) {
                    if (slotIndex < 0) {
                        action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                    } else {
                        net.minecraft.world.inventory.Slot slot = this.player.containerMenu.getSlot(slotIndex);
                        if (slot != null && slot.mayPickup(this.player) && slot.hasItem()) {
                            action = org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY;
                        } else {
                            action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                        }
                    }
                }
                break;
            case SWAP:
                if ((packet.buttonNum() >= 0 && packet.buttonNum() < 9) || packet.buttonNum() == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND) {
                    // Paper start - Add slot sanity checks to container clicks
                    if (slotIndex < 0) {
                        action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                        break;
                    }
                    // Paper end - Add slot sanity checks to container clicks
                    click = (packet.buttonNum() == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND) ? org.bukkit.event.inventory.ClickType.SWAP_OFFHAND : org.bukkit.event.inventory.ClickType.NUMBER_KEY;
                    net.minecraft.world.inventory.Slot clickedSlot = this.player.containerMenu.getSlot(slotIndex);
                    if (clickedSlot.mayPickup(this.player)) {
                        ItemStack hotbar = this.player.getInventory().getItem(packet.buttonNum());
                        if ((!hotbar.isEmpty() && clickedSlot.mayPlace(hotbar)) || (hotbar.isEmpty() && clickedSlot.hasItem())) { // Paper - modernify this logic (no such thing as a "hotbar move and readd"
                            action = org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP;
                        } else {
                            action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                        }
                    } else {
                        action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                    }
                }
                break;
            case CLONE:
                if (packet.buttonNum() == 0 || packet.buttonNum() == 2) { // Paper - See #11465, workaround for rebound middle clicks in survival, regular middle clicks still don't send a packet
                    click = org.bukkit.event.inventory.ClickType.MIDDLE;
                    if (slotIndex < 0) {
                        action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                    } else {
                        net.minecraft.world.inventory.Slot slot = this.player.containerMenu.getSlot(slotIndex);
                        if (slot != null && slot.hasItem() && this.player.getAbilities().instabuild && this.player.containerMenu.getCarried().isEmpty()) {
                            action = org.bukkit.event.inventory.InventoryAction.CLONE_STACK;
                        } else {
                            action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                        }
                    }
                } else {
                    click = org.bukkit.event.inventory.ClickType.UNKNOWN;
                    action = org.bukkit.event.inventory.InventoryAction.UNKNOWN;
                }
                break;
            case THROW:
                if (slotIndex >= 0) {
                    if (packet.buttonNum() == 0) {
                        click = org.bukkit.event.inventory.ClickType.DROP;
                        net.minecraft.world.inventory.Slot slot = this.player.containerMenu.getSlot(slotIndex);
                        if (slot != null && slot.hasItem() && slot.mayPickup(this.player) && !slot.getItem().isEmpty() && slot.getItem().getItem() != net.minecraft.world.item.Items.AIR) {
                            action = org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT;
                        } else {
                            action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                        }
                    } else if (packet.buttonNum() == 1) {
                        click = org.bukkit.event.inventory.ClickType.CONTROL_DROP;
                        net.minecraft.world.inventory.Slot slot = this.player.containerMenu.getSlot(slotIndex);
                        if (slot != null && slot.hasItem() && slot.mayPickup(this.player) && !slot.getItem().isEmpty() && slot.getItem().getItem() != net.minecraft.world.item.Items.AIR) {
                            action = org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT;
                        } else {
                            action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                        }
                    }
                } else {
                    // Sane default (because this happens when they are holding nothing. Don't ask why.)
                    click = org.bukkit.event.inventory.ClickType.LEFT;
                    if (packet.buttonNum() == 1) {
                        click = org.bukkit.event.inventory.ClickType.RIGHT;
                    }
                    action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                }
                break;
            case QUICK_CRAFT:
                // Paper start - Fix CraftBukkit drag system
                net.minecraft.world.inventory.AbstractContainerMenu containerMenu = this.player.containerMenu;
                int currentStatus = this.player.containerMenu.quickcraftStatus;
                int newStatus = net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftHeader(packet.buttonNum());
                if ((currentStatus != net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE || newStatus != net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_END && currentStatus != newStatus)) {
                } else if (containerMenu.getCarried().isEmpty()) {
                } else if (newStatus == net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_START) {
                } else if (newStatus == net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE) {
                } else if (newStatus == net.minecraft.world.inventory.AbstractContainerMenu.QUICKCRAFT_HEADER_END) {
                    if (!this.player.containerMenu.quickcraftSlots.isEmpty()) {
                        if (this.player.containerMenu.quickcraftSlots.size() == 1) {
                            int index = containerMenu.quickcraftSlots.iterator().next().index;
                            containerMenu.resetQuickCraft();
                            this.handleContainerClick(new ServerboundContainerClickPacket(packet.containerId(), packet.stateId(), (short) index, (byte) containerMenu.quickcraftType, net.minecraft.world.inventory.ContainerInput.PICKUP, packet.changedSlots(), packet.carriedItem()));
                            return;
                        }
                    }
                }
                // Paper end - Fix CraftBukkit drag system
                original.call(menu, slotIndex, buttonNum, containerInput, clicker);
                break;
            case PICKUP_ALL:
                click = org.bukkit.event.inventory.ClickType.DOUBLE_CLICK;
                action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                if (slotIndex >= 0 && !this.player.containerMenu.getCarried().isEmpty()) {
                    ItemStack cursor = this.player.containerMenu.getCarried();
                    action = org.bukkit.event.inventory.InventoryAction.NOTHING;
                    // Quick check for if we have any of the item
                    if (inventory.getTopInventory().contains(org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(cursor.getItem())) || inventory.getBottomInventory().contains(org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(cursor.getItem()))) {
                        action = org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR;
                    }
                }
                break;
            default:
                break;
        }

        if (packet.containerInput() != net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT) {
            // If a non quick craft packet is emitted by the client while in quick crafting, the server will reset quick crafting and
            // discard any further packet action.
            if (this.player.containerMenu.quickcraftStatus != 0) {
                action = org.bukkit.event.inventory.InventoryAction.NOTHING;
            }

            if (click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                event = new org.bukkit.event.inventory.InventoryClickEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
            } else {
                event = new org.bukkit.event.inventory.InventoryClickEvent(inventory, type, slotIndex, click, action);
            }

            org.bukkit.inventory.Inventory top = inventory.getTopInventory();
            if (slotIndex == 0 && top instanceof final org.bukkit.inventory.CraftingInventory craftingInv) {
                org.bukkit.inventory.Recipe recipe = craftingInv.getRecipe();
                if (recipe != null) {
                    if (click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                        event = new org.bukkit.event.inventory.CraftItemEvent(recipe, inventory, type, slotIndex, click, action, packet.buttonNum());
                    } else {
                        event = new org.bukkit.event.inventory.CraftItemEvent(recipe, inventory, type, slotIndex, click, action);
                    }
                }
            }

            if (slotIndex == 3 && top instanceof final org.bukkit.inventory.SmithingInventory smithingInv) {
                org.bukkit.inventory.ItemStack result = smithingInv.getResult();
                if (result != null) {
                    if (click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                        event = new org.bukkit.event.inventory.SmithItemEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                    } else {
                        event = new org.bukkit.event.inventory.SmithItemEvent(inventory, type, slotIndex, click, action);
                    }
                }
            }

            // Paper start - cartography item event
            if (slotIndex == net.minecraft.world.inventory.CartographyTableMenu.RESULT_SLOT && top instanceof org.bukkit.inventory.CartographyInventory cartographyInventory) {
                org.bukkit.inventory.ItemStack result = cartographyInventory.getResult();
                if (result != null && !result.isEmpty()) {
                    if (click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                        event = new io.papermc.paper.event.player.CartographyItemEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                    } else {
                        event = new io.papermc.paper.event.player.CartographyItemEvent(inventory, type, slotIndex, click, action);
                    }
                }
            }
            // Paper end - cartography item event

            event.setCancelled(cancelled);
            net.minecraft.world.inventory.AbstractContainerMenu oldContainer = this.player.containerMenu; // SPIGOT-1224
            this.cserver.getPluginManager().callEvent(event);
            if (this.player.containerMenu != oldContainer) {
                this.player.containerMenu.resumeRemoteUpdates();
                this.player.containerMenu.broadcastFullState();
                return;
            }

            if (event.getResult() != org.bukkit.event.Event.Result.DENY) { // if denied, synchronizing is fully handled by broadcastChanges at the end
                original.call(menu, slotIndex, buttonNum, containerInput, clicker);
            }

            if (event instanceof org.bukkit.event.inventory.CraftItemEvent || event instanceof org.bukkit.event.inventory.SmithItemEvent) {
                // Need to update the inventory on crafting to
                // correctly support custom recipes
                this.player.containerMenu.sendAllDataToRemote();
            }
        }
        // CraftBukkit end
    }

    // Paper - update offhand data when the player is clicking in an inventory not their own
    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;broadcastChanges()V", shift = At.Shift.AFTER))
    private void paper$offhandResync(final ServerboundContainerClickPacket packet, final CallbackInfo ci) {
        if (packet.buttonNum() == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND && this.player.containerMenu != this.player.inventoryMenu) this.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(this.player.containerMenu.containerId, this.player.containerMenu.incrementStateId(), net.minecraft.world.inventory.InventoryMenu.SHIELD_SLOT, this.player.inventoryMenu.getSlot(net.minecraft.world.inventory.InventoryMenu.SHIELD_SLOT).getItem().copy())); // Paper - Sync offhand slot in menus (containerSynchronizer.sendOffHandSlotChange)
    }

    // ---- creative slot: InventoryCreativeEvent ----
    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "JUMP", opcode = org.objectweb.asm.Opcodes.IFEQ, ordinal = 1), cancellable = true)
    private void paper$creativeEvent(final net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket packet, final CallbackInfo ci,
                                     @Local(ordinal = 0) final boolean drop, @Local final LocalRef<ItemStack> itemStack, @Local(ordinal = 2) final LocalBooleanRef validData) {
        boolean validSlot = packet.slotNum() >= 1 && packet.slotNum() <= 45;
        if (drop || (validSlot && !ItemStack.matches(this.player.inventoryMenu.getSlot(packet.slotNum()).getItem(), packet.itemStack()))) { // Insist on valid slot
            // CraftBukkit start - Call click event
            org.bukkit.inventory.InventoryView inventory = this.player.inventoryMenu.getBukkitView();
            org.bukkit.inventory.ItemStack item = CraftItemStack.asBukkitCopy(packet.itemStack());

            org.bukkit.event.inventory.InventoryType.SlotType type = org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR;
            if (drop) {
                type = org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE;
            } else if (packet.slotNum() < 36) {
                if (packet.slotNum() >= 5 && packet.slotNum() < 9) {
                    type = org.bukkit.event.inventory.InventoryType.SlotType.ARMOR;
                } else {
                    type = org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER;
                }
            }
            org.bukkit.event.inventory.InventoryCreativeEvent event = new org.bukkit.event.inventory.InventoryCreativeEvent(inventory, type, drop ? net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE : packet.slotNum(), item);
            this.cserver.getPluginManager().callEvent(event);

            itemStack.set(CraftItemStack.asNMSCopy(event.getCursor()));

            switch (event.getResult()) {
                case ALLOW:
                    // Plugin cleared the id / stacksize checks
                    validData.set(true);
                    break;
                case DEFAULT:
                    break;
                case DENY:
                    // Reset the slot
                    if (packet.slotNum() >= 0) {
                        this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(this.player.inventoryMenu.containerId, this.player.inventoryMenu.incrementStateId(), packet.slotNum(), this.player.inventoryMenu.getSlot(packet.slotNum()).getItem()));
                        this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket(ItemStack.EMPTY.copy())); // Paper - correctly set cursor
                    }
                    ci.cancel();
                    return;
            }
        }
        // CraftBukkit end
    }

    // ---- sign update ----
    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void paper$signImmobile(final net.minecraft.network.protocol.game.ServerboundSignUpdatePacket packet, final List<net.minecraft.server.network.FilteredText> lines, final CallbackInfo ci) {
        if (this.player.isImmobile()) ci.cancel(); // CraftBukkit
    }

    @Inject(method = "updateSignText", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"), cancellable = true)
    private void paper$uncheckedSign(final net.minecraft.network.protocol.game.ServerboundSignUpdatePacket packet, final List<net.minecraft.server.network.FilteredText> lines, final CallbackInfo ci) {
        // Paper start - Add API for client-side signs
        if (!new io.papermc.paper.event.packet.UncheckedSignChangeEvent(
            this.player.getBukkitEntity(),
            io.papermc.paper.util.MCUtil.toPosition(packet.pos()),
            org.bukkit.craftbukkit.block.sign.CraftSignSide.fromVanilla(packet.slot()),
            lines.stream().<net.kyori.adventure.text.Component>map(line -> net.kyori.adventure.text.Component.text(line.raw())).toList())
            .callEvent()) {
            ci.cancel();
        }
        // Paper end - Add API for client-side signs
    }

    // ---- handlePlayerAction ----
    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$actionImmobile(final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) ci.cancel(); // CraftBukkit
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setItemInHand(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)V", ordinal = 0), cancellable = true)
    private void paper$swapHands(final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet, final CallbackInfo ci) {
        ItemStack swap = this.player.getItemInHand(InteractionHand.OFF_HAND);
        // CraftBukkit start - inspiration taken from DispenserRegistry (See SpigotCraft#394)
        org.bukkit.inventory.ItemStack mainHand = CraftItemStack.asBukkitMirror(swap);
        org.bukkit.inventory.ItemStack offHand = CraftItemStack.asBukkitMirror(this.player.getItemInHand(InteractionHand.MAIN_HAND));
        org.bukkit.event.player.PlayerSwapHandItemsEvent swapItemsEvent = new org.bukkit.event.player.PlayerSwapHandItemsEvent(this.getCraftPlayer(), mainHand.clone(), offHand.clone());
        this.cserver.getPluginManager().callEvent(swapItemsEvent);
        ci.cancel(); // we run the whole swap here; vanilla's swap + `return` are skipped
        if (swapItemsEvent.isCancelled()) {
            return;
        }
        if (swapItemsEvent.getOffHandItem().equals(offHand)) {
            this.player.setItemInHand(InteractionHand.OFF_HAND, this.player.getItemInHand(InteractionHand.MAIN_HAND));
        } else {
            this.player.setItemInHand(InteractionHand.OFF_HAND, CraftItemStack.asNMSCopy(swapItemsEvent.getOffHandItem()));
        }
        if (swapItemsEvent.getMainHandItem().equals(mainHand)) {
            this.player.setItemInHand(InteractionHand.MAIN_HAND, swap);
        } else {
            this.player.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(swapItemsEvent.getMainHandItem()));
        }
        // CraftBukkit end
        this.player.stopUsingItem();
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;drop(Z)V", ordinal = 0), cancellable = true)
    private void paper$dropLimit(final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet, final CallbackInfo ci) {
        // limit how quickly items can be dropped
        // If the ticks aren't the same then the count starts from 0 and we update the lastDropTick.
        if (this.lastDropTick != fission$currentTick()) {
            this.dropCount = 0;
            this.lastDropTick = fission$currentTick();
        } else {
            // Else we increment the drop count and check the amount.
            this.dropCount++;
            if (this.dropCount >= 20) {
                LOGGER.warn(this.player.getScoreboardName() + " dropped their items too quickly!");
                this.disconnect(Component.literal("You dropped your items too quickly (Hacking?)"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - kick event cause
                ci.cancel();
            }
        }
        // CraftBukkit end
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V"), cancellable = true)
    private void paper$digStart(final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet, final CallbackInfo ci) {
        net.minecraft.core.BlockPos pos = packet.getPos();
        // Paper start - Don't allow digging into unloaded chunks
        if (this.player.level().getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) == null || !this.player.isWithinBlockInteractionRange(pos, 1.0)) {
            this.player.connection.ackBlockChangesUpTo(packet.getSequence());
            ci.cancel();
            return;
        }
        // Paper end - Don't allow digging into unloaded chunks
        // Paper start - Send block entities after destroy prediction
        this.player.gameMode.capturedBlockEntity = false;
        this.player.gameMode.captureSentBlockEntities = true;
        // Paper end - Send block entities after destroy prediction
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;ackBlockChangesUpTo(I)V", shift = At.Shift.AFTER))
    private void paper$digEnd(final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet, final CallbackInfo ci) {
        // Paper start - Send block entities after destroy prediction
        this.player.gameMode.captureSentBlockEntities = false;
        // If a block entity was modified speedup the block change ack to avoid the block entity
        // being overridden.
        if (this.player.gameMode.capturedBlockEntity) {
            // manually tick
            this.send(new net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket(this.ackBlockChangesUpTo));
            this.ackBlockChangesUpTo = -1;

            this.player.gameMode.capturedBlockEntity = false;
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = this.player.level().getBlockEntity(packet.getPos());
            if (blockEntity != null) {
                this.player.connection.send(blockEntity.getUpdatePacket());
            }
        }
        // Paper end - Send block entities after destroy prediction
    }

    // ---- use item on / use item ----
    // Paper start - limit place/interactions
    private boolean checkLimit(final long timestamp) {
        if (!io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.incomingPacketThreshold.enabled()) {
            return true;
        }

        final int threshold = io.papermc.paper.configuration.GlobalConfiguration.get().spamLimiter.incomingPacketThreshold.intValue();

        if (this.lastLimitedPacket != -1 && timestamp - this.lastLimitedPacket < threshold && this.limitedPackets++ >= 8) {
            return false;
        }

        if (this.lastLimitedPacket == -1 || timestamp - this.lastLimitedPacket >= threshold) {
            this.lastLimitedPacket = timestamp;
            this.limitedPackets = 0;
            return true;
        }

        return true;
    }
    // Paper end - limit place/interactions

    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$useItemOnGuard(final net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) { ci.cancel(); return; } // CraftBukkit
        if (!this.checkLimit(System.currentTimeMillis())) ci.cancel(); // Spigot - check limit
    }

    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private void paper$stopUsingBeforeUseOn(final net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet, final CallbackInfo ci) {
        this.player.stopUsingItem(); // CraftBukkit - SPIGOT-4706
    }

    @ModifyExpressionValue(method = "handleUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/InteractionResult$Success;shouldSwing()Z"))
    private boolean paper$noSwingOnInteractResult(final boolean shouldSwing) {
        return shouldSwing && !this.player.gameMode.interactResult; // Paper - Call interact event
    }

    @Inject(method = "handleUseItem", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$useItemGuard(final net.minecraft.network.protocol.game.ServerboundUseItemPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) { ci.cancel(); return; } // CraftBukkit
        if (!this.checkLimit(System.currentTimeMillis())) ci.cancel(); // Spigot - check limit
    }

    @WrapOperation(method = "handleUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult paper$useItemInteractEvent(final net.minecraft.server.level.ServerPlayerGameMode gameMode, final ServerPlayer player, final net.minecraft.world.level.Level level, ItemStack itemStack, final InteractionHand hand, final Operation<InteractionResult> original) {
        // CraftBukkit start
        // Raytrace to look for 'rogue armswings'
        Vec3 from = this.player.getEyePosition();
        Vec3 delta = this.player.getViewVector(1.0F).scale(this.player.blockInteractionRange());
        Vec3 to = from.add(delta);
        net.minecraft.world.phys.BlockHitResult hitResult = this.player.level().clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, this.player));

        boolean cancelled;
        if (hitResult == null || hitResult.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            org.bukkit.event.player.PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(this.player, Action.RIGHT_CLICK_AIR, itemStack, hand);
            cancelled = event.useItemInHand() == org.bukkit.event.Event.Result.DENY;
        } else {
            if (this.player.gameMode.firedInteract && this.player.gameMode.interactPosition.equals(hitResult.getBlockPos()) && this.player.gameMode.interactHand == hand && ItemStack.isSameItemSameComponents(this.player.gameMode.interactItemStack, itemStack)) {
                cancelled = this.player.gameMode.interactResult;
            } else {
                org.bukkit.event.player.PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(this.player, Action.RIGHT_CLICK_BLOCK, hitResult.getBlockPos(), hitResult.getDirection(), itemStack, true, hand, hitResult.getLocation());
                cancelled = event.useItemInHand() == org.bukkit.event.Event.Result.DENY;
            }
            this.player.gameMode.firedInteract = false;
        }

        if (cancelled) {
            this.player.resyncUsingItem(this.player); // Paper - Properly cancel usable items
            // Paper start - Fix inventory desync; SPIGOT-2524
            if (io.papermc.paper.util.MCUtil.clientPredictsInteraction(this.player, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), itemStack)) {
                this.player.containerMenu.sendAllDataToRemote();
            } else {
                this.player.containerMenu.forceHeldSlotAndArmor(hand);
            }
            // Paper end - Fix inventory desync
            return InteractionResult.PASS;
        }
        itemStack = this.player.getItemInHand(hand); // Update in case it was changed in the event
        if (itemStack.isEmpty()) {
            return InteractionResult.PASS;
        }
        // CraftBukkit end
        return original.call(gameMode, player, level, itemStack, hand);
    }

    // ---- punch (left click air / arm swing) ----
    @Inject(method = "handlePunch", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$punch(final net.minecraft.network.protocol.game.ServerboundPunchPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) { ci.cancel(); return; } // CraftBukkit
        this.player.resetLastActionTime();
        // CraftBukkit start - Raytrace to look for 'rogue armswings'
        Location origin = CraftLocation.toBukkit(this.player.getEyePosition(), this.player.level(), this.player.getYRot(), this.player.getXRot());
        double maxRange = Math.max(this.player.blockInteractionRange(), this.player.entityInteractionRange()); // todo flawed
        // SPIGOT-5607: Only call interact event if no block or entity is being clicked. Use bukkit ray trace method, because it handles blocks and entities at the same time
        // SPIGOT-7429: Make sure to call PlayerInteractEvent for spectators and non-pickable entities
        org.bukkit.util.RayTraceResult result = this.player.level().getWorld().rayTrace(origin, origin.getDirection(), maxRange, org.bukkit.FluidCollisionMode.NEVER, false, 0.0, entity -> { // Paper - Call interact event; change raySize from 0.1 to 0.0
            Entity handle = ((org.bukkit.craftbukkit.entity.CraftEntity) entity).getHandle();
            return entity != this.player.getBukkitEntity() && this.player.getBukkitEntity().canSee(entity) && !handle.isSpectator() && handle.isPickable() && !handle.isPassengerOfSameVehicle(this.player);
        });
        if (result == null) {
            CraftEventFactory.callPlayerInteractEvent(this.player, Action.LEFT_CLICK_AIR, this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
        } else { // Paper start - Call interact event
            net.minecraft.world.level.GameType gameType = this.player.gameMode.getGameModeForPlayer();
            if (gameType == net.minecraft.world.level.GameType.ADVENTURE && result.getHitBlock() != null) {
                CraftEventFactory.callPlayerInteractEvent(this.player, Action.LEFT_CLICK_BLOCK, ((org.bukkit.craftbukkit.block.CraftBlock) result.getHitBlock()).getPosition(), org.bukkit.craftbukkit.block.CraftBlock.blockFaceToNotch(result.getHitBlockFace()), this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
            } else if (gameType != net.minecraft.world.level.GameType.CREATIVE && result.getHitEntity() != null && origin.toVector().distanceSquared(result.getHitPosition()) > this.player.entityInteractionRange() * this.player.entityInteractionRange()) {
                CraftEventFactory.callPlayerInteractEvent(this.player, Action.LEFT_CLICK_AIR, this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
            }
        } // Paper end - Call interact event

        // Arm swing animation
        io.papermc.paper.event.player.PlayerArmSwingEvent event = new io.papermc.paper.event.player.PlayerArmSwingEvent(this.getCraftPlayer(), org.bukkit.inventory.EquipmentSlot.HAND); // Paper - Add PlayerArmSwingEvent
        if (!event.callEvent()) ci.cancel();
        // CraftBukkit end
    }

    // ---- attack / interact with entity ----
    @Inject(method = "handleAttack", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$attackImmobile(final net.minecraft.network.protocol.game.ServerboundAttackPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) ci.cancel(); // Paper
    }

    @ModifyExpressionValue(method = "handleAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getEntityOrPart(I)Lnet/minecraft/world/entity/Entity;"))
    private Entity paper$attackSelf(final Entity target) {
        // Spigot start
        if (target == this.player) {
            this.disconnect(Component.literal("Cannot interact with self!"), org.bukkit.event.player.PlayerKickEvent.Cause.SELF_INTERACTION); // Paper - kick event cause
            return null;
        }
        // Spigot end
        return target;
    }

    @Inject(method = "handleInteract", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void paper$interactImmobile(final net.minecraft.network.protocol.game.ServerboundInteractPacket packet, final CallbackInfo ci) {
        if (this.player.isImmobile()) ci.cancel(); // CraftBukkit
    }

    @ModifyExpressionValue(method = "handleInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getEntityOrPart(I)Lnet/minecraft/world/entity/Entity;"))
    private Entity paper$interactSelf(final Entity target) {
        // Spigot start
        if (target == this.player && !this.player.isSpectator()) {
            this.disconnect(Component.literal("Cannot interact with self!"), org.bukkit.event.player.PlayerKickEvent.Cause.SELF_INTERACTION); // Paper - kick event cause
            return null;
        }
        // Spigot end
        return target;
    }

    @WrapOperation(method = "handleInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;interactOn(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult paper$interactAtEntity(final ServerPlayer self, final Entity target, final InteractionHand hand, final Vec3 location, final Operation<InteractionResult> original) {
        ItemStack tool = this.player.getItemInHand(hand);
        // CraftBukkit start
        final net.minecraft.world.item.Item itemType = tool.getItem();
        boolean triggerLeashUpdate = tool.is(net.minecraft.world.item.Items.LEAD) && target instanceof net.minecraft.world.entity.Leashable;

        final org.bukkit.event.player.PlayerInteractAtEntityEvent event = new org.bukkit.event.player.PlayerInteractAtEntityEvent(
            this.getCraftPlayer(), target.getBukkitEntity(),
            org.bukkit.craftbukkit.util.CraftVector.toBukkit(location), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand)
        );
        this.cserver.getPluginManager().callEvent(event);
        final boolean resendData = event.isCancelled() || !this.player.getItemInHand(hand).is(itemType);

        // Entity in bucket - SPIGOT-4048 and SPIGOT-6859
        if (itemType == net.minecraft.world.item.Items.WATER_BUCKET && target instanceof net.minecraft.world.entity.Bucketable && target instanceof net.minecraft.world.entity.LivingEntity && resendData) {
            target.resendPossiblyDesyncedEntityData(this.player); // Paper - The entire mob gets deleted, so resend it
        }

        // Paper start - Fix inventory desync
        if (resendData) {
            if (io.papermc.paper.util.MCUtil.clientPredictsInteraction(this.player, target, tool)) {
                this.player.containerMenu.sendAllDataToRemote();
            } else {
                this.player.containerMenu.forceHeldSlot(hand);
            }
        }
        // Paper end - Fix inventory desync

        if (triggerLeashUpdate && resendData) {
            // Refresh the current leash state
            this.send(new net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket(target, ((net.minecraft.world.entity.Leashable) target).getLeashHolder()));
        }

        if (resendData) {
            // Refresh the current entity metadata
            target.refreshEntityData(this.player);
            // SPIGOT-7136 - Allays
            if (target instanceof net.minecraft.world.entity.animal.allay.Allay || target instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) { // Paper - Fix horse armor desync
                this.send(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                    target.getId(), java.util.Arrays.stream(net.minecraft.world.entity.EquipmentSlot.values())
                    .map((slot) -> com.mojang.datafixers.util.Pair.of(slot, ((net.minecraft.world.entity.LivingEntity) target).getItemBySlot(slot).copy()))
                    .collect(java.util.stream.Collectors.toList()))); // Paper - sanitize
                this.player.containerMenu.sendAllDataToRemote();
            } else {
                this.player.containerMenu.forceHeldSlot(hand); // Paper - fix slot desync (is this needed?)
            }
        }

        if (event.isCancelled()) {
            return InteractionResult.PASS;
        }
        // CraftBukkit end
        return original.call(self, target, hand, location);
    }

    // ---- chat ----
    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void paper$chatStopped(final net.minecraft.network.protocol.game.ServerboundChatPacket packet, final CallbackInfo ci) {
        // CraftBukkit start - async chat
        // SPIGOT-3638
        if (this.server.isStopped()) {
            ci.cancel();
        }
        // CraftBukkit end
    }

    @Inject(method = "lambda$handleChat$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/FutureChain;append(Ljava/util/concurrent/CompletableFuture;Ljava/util/function/Consumer;)V"), cancellable = true)
    private void paper$asyncChat(final net.minecraft.network.protocol.game.ServerboundChatPacket packet, final java.util.Optional<net.minecraft.network.chat.LastSeenMessages> lastSeen, final CallbackInfo ci,
                                 @Local final PlayerChatMessage signedMessage, @Local(ordinal = 0) final java.util.concurrent.CompletableFuture<net.minecraft.server.network.FilteredText> vanillaFiltered) {
        java.util.concurrent.CompletableFuture<net.minecraft.server.network.FilteredText> filteredFuture = vanillaFiltered.thenApplyAsync(java.util.function.Function.identity(), this.server.chatExecutor); // CraftBukkit - async chat
        java.util.concurrent.CompletableFuture<Component> decoratedFuture = this.server.getChatDecorator().decorate(this.player, null, signedMessage.decoratedContent()); // Paper - Adventure

        this.chatMessageChain.append(java.util.concurrent.CompletableFuture.allOf(filteredFuture, decoratedFuture), ($) -> { // Paper - Adventure
            PlayerChatMessage filteredMessage = signedMessage.withUnsignedContent(decoratedFuture.join()).filter(filteredFuture.join().mask()); // Paper - Adventure
            this.broadcastChatMessage(filteredMessage);
        });
        ci.cancel();
    }

    // CraftBukkit - SPIGOT-7346: Prevent disconnected players from executing commands
    @Inject(method = "lambda$handleChatCommand$0", at = @At("HEAD"), cancellable = true)
    private void paper$chatCommandDisconnected(final net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet, final CallbackInfo ci) {
        if (this.player.hasDisconnected()) ci.cancel();
    }

    @Inject(method = "lambda$handleSignedChatCommand$0", at = @At("HEAD"), cancellable = true)
    private void paper$signedChatCommandDisconnected(final net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet, final java.util.Optional<net.minecraft.network.chat.LastSeenMessages> lastSeen, final CallbackInfo ci) {
        if (this.player.hasDisconnected()) ci.cancel();
    }

    // CraftBukkit - async chat / sync commands: chat is handled on the calling thread, commands on the main thread
    @WrapOperation(method = "tryHandleChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;execute(Ljava/lang/Runnable;)V"))
    private void paper$syncCommandsOnly(final MinecraftServer server, final Runnable chatHandler, final Operation<Void> original, @Local(argsOnly = true) final boolean isCommand) {
        if (isCommand) {
            original.call(server, chatHandler);
        } else {
            chatHandler.run();
        }
    }

    // CraftBukkit: broadcastChatMessage fires AsyncChatEvent through chat(...)
    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void paper$broadcastChatMessage(final PlayerChatMessage message, final CallbackInfo ci) {
        ci.cancel();
        // CraftBukkit start
        String rawMessage = message.signedContent();
        if (rawMessage.isEmpty()) {
            LOGGER.warn("{} tried to send an empty message", this.player.getScoreboardName());
        } else if (this.getCraftPlayer().isConversing()) {
            final String conversationInput = rawMessage;
            this.server.processQueue.add(() -> this.getCraftPlayer().acceptConversationInput(conversationInput));
        } else if (this.player.getChatVisibility() == ChatVisiblity.SYSTEM) { // Re-add "Command Only" flag check
            this.send(new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(Component.translatable("chat.cannotSend").withStyle(net.minecraft.ChatFormatting.RED), false));
        } else {
            this.chat(rawMessage, message, true);
        }
        // CraftBukkit end
        this.detectChatRateSpam(); // Spigot - spam exclusions: TODO(behaviour) SpigotConfig.spamExclusions are not consulted
    }

    // CraftBukkit: PlayerCommandPreprocessEvent for unsigned commands
    @WrapMethod(method = "performUnsignedChatCommand")
    private void paper$unsignedCommandEvent(String command, final Operation<Void> original) {
        String prefixedCommand = "/" + command;
        if (org.spigotmc.SpigotConfig.logCommands) { // Paper - Add missing SpigotConfig logCommands check
            LOGGER.info("{} issued server command: {}", this.player.getScoreboardName(), prefixedCommand);
        }

        org.bukkit.event.player.PlayerCommandPreprocessEvent event = new org.bukkit.event.player.PlayerCommandPreprocessEvent(this.getCraftPlayer(), prefixedCommand, new org.bukkit.craftbukkit.util.LazyPlayerSet(this.server));
        this.cserver.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return;
        }
        command = event.getMessage().substring(1);
        original.call(command);
    }

    // CraftBukkit: PlayerCommandPreprocessEvent for signed commands (fired first; parse/collect uses the original command)
    private org.bukkit.event.player.PlayerCommandPreprocessEvent fission$signedCommandEvent;
    private String fission$signedCommandString;

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"))
    private void paper$signedCommandEvent(final net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet, final net.minecraft.network.chat.LastSeenMessages lastSeenMessages, final CallbackInfo ci) {
        // CraftBukkit start
        String commandString = "/" + packet.command();
        if (org.spigotmc.SpigotConfig.logCommands) { // Paper - Add missing SpigotConfig logCommands check
            LOGGER.info("{} issued server command: {}", this.player.getScoreboardName(), commandString);
        } // Paper - Add missing SpigotConfig logCommands check

        org.bukkit.event.player.PlayerCommandPreprocessEvent event = new org.bukkit.event.player.PlayerCommandPreprocessEvent(this.getCraftPlayer(), commandString, new org.bukkit.craftbukkit.util.LazyPlayerSet(this.server));
        this.cserver.getPluginManager().callEvent(event);
        this.fission$signedCommandEvent = event;
        this.fission$signedCommandString = event.getMessage().substring(1);
    }

    @Inject(method = "performSignedChatCommand", at = @At(value = "NEW", target = "net/minecraft/commands/CommandSigningContext$SignedArguments"), cancellable = true)
    private void paper$signedCommandResult(final net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet, final net.minecraft.network.chat.LastSeenMessages lastSeenMessages, final CallbackInfo ci,
                                           @Local final LocalRef<com.mojang.brigadier.ParseResults<net.minecraft.commands.CommandSourceStack>> command, @Local final LocalRef<java.util.Map<String, PlayerChatMessage>> signedArguments) {
        // Paper start - Fix cancellation and message changing
        if (this.fission$signedCommandEvent.isCancelled()) {
            // Only now are we actually good to return
            ci.cancel();
            return;
        }

        // Remove signed parts if the command was changed
        if (!this.fission$signedCommandString.equals(packet.command())) {
            command.set(this.parseCommand(this.fission$signedCommandString));
            signedArguments.set(Collections.emptyMap());
        }
        // Paper end - Fix cancellation and message changing
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "performSignedChatCommand", at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/Commands;performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V"), index = 1)
    private String paper$signedCommandString(final String vanilla) {
        return this.fission$signedCommandString != null ? this.fission$signedCommandString : vanilla; // CraftBukkit
    }

    @Shadow private com.mojang.brigadier.ParseResults<net.minecraft.commands.CommandSourceStack> parseCommand(final String command) { throw new AssertionError(); }

    // CraftBukkit - SPIGOT-7262: if hidden we have to send as disguised message.
    @Inject(method = "sendPlayerChatMessage", at = @At("HEAD"), cancellable = true)
    private void paper$sendChatHidden(final PlayerChatMessage message, final net.minecraft.network.chat.ChatType.Bound chatType, final CallbackInfo ci) {
        if (!this.getCraftPlayer().canSeePlayer(message.link().sender())) {
            this.sendDisguisedChatMessage(message.decoratedContent(), chatType);
            ci.cancel();
        }
    }
    @Shadow public abstract void sendDisguisedChatMessage(Component content, net.minecraft.network.chat.ChatType.Bound chatType);

    // ---- books ----
    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void paper$editBookLimits(final net.minecraft.network.protocol.game.ServerboundEditBookPacket packet, final CallbackInfo ci) {
        // Paper start - Book size limits
        final io.papermc.paper.configuration.type.number.IntOr.Disabled pageMax = io.papermc.paper.configuration.GlobalConfiguration.get().itemValidation.bookSize.pageMax;
        if (!this.cserver.isPrimaryThread() && pageMax.enabled()) {
            final List<String> pageList = packet.pages();
            long byteTotal = 0;
            final int maxBookPageSize = pageMax.intValue();
            final double multiplier = Math.clamp(io.papermc.paper.configuration.GlobalConfiguration.get().itemValidation.bookSize.totalMultiplier, 0.3D, 1D);
            long byteAllowed = maxBookPageSize;
            for (final String page : pageList) {
                final int byteLength = page.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                byteTotal += byteLength;
                final int length = page.length();
                int multiByteCharacters = 0;
                if (byteLength != length) {
                    // Count the number of multi byte characters
                    for (final char c : page.toCharArray()) {
                        if (c > 127) {
                            multiByteCharacters++;
                        }
                    }
                }

                // Allow pages with fewer characters to consume less of the allowed byte quota
                byteAllowed += maxBookPageSize * Math.clamp((double) length / 255D, 0.1D, 1) * multiplier;

                if (multiByteCharacters > 1) {
                    // Penalize multibyte characters
                    byteAllowed -= multiByteCharacters;
                }
            }

            if (byteTotal > byteAllowed) {
                LOGGER.warn("{} tried to send a book too large. Book size: {} - Allowed: {} - Pages: {}", this.player.getScoreboardName(), byteTotal, byteAllowed, pageList.size());
                this.disconnectAsync(Component.literal("Book too large!"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - kick event cause // Paper - add proper async disconnect
                ci.cancel();
                return;
            }
        }
        // Paper end - Book size limits
        // CraftBukkit start
        if (this.lastBookTick + 20 > fission$currentTick()) {
            this.disconnectAsync(Component.literal("Book edited too quickly!"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION); // Paper - kick event cause // Paper - add proper async disconnect
            ci.cancel();
            return;
        }
        this.lastBookTick = fission$currentTick();
        // CraftBukkit end
    }

    @Inject(method = "updateBookContents", at = @At("HEAD"), cancellable = true)
    private void paper$updateBookContents(final List<net.minecraft.server.network.FilteredText> contents, final int slot, final CallbackInfo ci) {
        ci.cancel();
        // CraftBukkit start
        ItemStack handItem = this.player.getInventory().getItem(slot);
        ItemStack carried = handItem.copy();
        // CraftBukkit end
        if (carried.has(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT)) {
            List<net.minecraft.server.network.Filterable<String>> pages = contents.stream().map(this::filterableFromOutgoing).toList();
            carried.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT, new net.minecraft.world.item.component.WritableBookContent(pages));
            this.player.getInventory().setItem(slot, CraftEventFactory.handleEditBookEvent(this.player, slot, handItem, carried)); // CraftBukkit // Paper - Don't ignore result (see other callsite for handleEditBookEvent)
        }
    }
    @Shadow private net.minecraft.server.network.Filterable<String> filterableFromOutgoing(final net.minecraft.server.network.FilteredText text) { throw new AssertionError(); }

    @WrapOperation(method = "signBook", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private void paper$signBook(final net.minecraft.world.entity.player.Inventory inventory, final int slot, final ItemStack writtenBook, final Operation<Void> original) {
        ItemStack carried = inventory.getItem(slot);
        CraftEventFactory.handleEditBookEvent(this.player, slot, carried, writtenBook); // CraftBukkit
        original.call(inventory, slot, carried); // CraftBukkit - event factory updates the hand book
    }

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
