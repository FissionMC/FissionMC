package net.fissionmc.backend.mixin.server.players;

import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fissionmc.craft.compat.LoginResult;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.net.SocketAddress;
import java.text.SimpleDateFormat;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.IpBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.UserNameToIdResolver;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/server/players/PlayerList.java.patch
 * Paper's nested PlayerList.LoginResult lives in net.fissionmc.craft.compat.LoginResult.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final public static Component CHAT_FILTERED_FULL;
    @Shadow @Final @Mutable private List<ServerPlayer> players;
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private static SimpleDateFormat BAN_DATE_FORMAT;
    @Shadow @Final public static Component DUPLICATE_LOGIN_DISCONNECT_MESSAGE;
    @Shadow @Final private Map<UUID, ServerPlayer> playersByUUID;
    @Shadow @Final private UserBanList bans;
    @Shadow @Final private IpBanList ipBans;
    @Shadow @Final public PlayerDataStorage playerIo;
    @Shadow private int sendAllPlayerInfoIn;

    @Shadow public abstract MinecraftServer getServer();
    @Shadow protected abstract void updateEntireScoreboard(ServerScoreboard scoreboard, ServerPlayer player);
    @Shadow public abstract void sendPlayerPermissionLevel(ServerPlayer player);
    @Shadow public abstract void sendLevelInfo(ServerPlayer player, ServerLevel level);
    @Shadow public abstract void sendActivePlayerEffects(ServerPlayer player);
    @Shadow public abstract void sendAllPlayerInfo(ServerPlayer player);
    @Shadow public abstract void broadcastSystemMessage(Component message, boolean overlay);
    @Shadow protected abstract void save(ServerPlayer player);
    @Shadow public abstract ServerPlayer getPlayerByName(String name);
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private ServerOpList ops;
    @Shadow @Final private UserWhiteList whitelist;
    @Shadow @Final private LayeredRegistryAccess<RegistryLayer> registries;

    @Shadow public abstract ServerPlayer respawn(ServerPlayer serverPlayer, boolean keepAllPlayerData, Entity.RemovalReason removalReason);
    @Shadow public abstract void broadcastAll(Packet<?> packet);
    @Shadow public abstract boolean isUsingWhitelist();
    @Shadow public abstract int getMaxPlayers();
    @Shadow public abstract boolean canBypassPlayerLimit(NameAndId nameAndId);
    @Shadow private boolean verifyChatTrusted(final PlayerChatMessage message) { throw new AssertionError(); }
    @Shadow private Path locateStatsFile(final GameProfile gameProfile) { throw new AssertionError(); }

    // CraftBukkit start
    private org.bukkit.craftbukkit.CraftServer cserver;
    private final Map<String,ServerPlayer> playersByName = new java.util.HashMap<>();

    @Redirect(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/server/players/PlayerList;players:Ljava/util/List;", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void craftbukkit$copyOnWritePlayers(final PlayerList self, final List<ServerPlayer> value) {
        this.players = new java.util.concurrent.CopyOnWriteArrayList<>(); // CraftBukkit - ArrayList -> CopyOnWriteArrayList: Iterator safety
    }

    // FissionMC: Paper runs this at the top of the constructor body; at RETURN every field CraftServer's constructor
    // reads (players) is set as well.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$createCraftServer(CallbackInfo ci) {
        this.cserver = this.server.server = new org.bukkit.craftbukkit.CraftServer((net.minecraft.server.dedicated.DedicatedServer) this.server, (PlayerList) (Object) this);
        this.server.console = new com.destroystokyo.paper.console.TerminalConsoleCommandSender(); // Paper
    }
    // CraftBukkit end

    // Surface: public. Mixin requires private fields; the loader widens it.
    private @Nullable String collideRuleTeamName; // Paper - Configurable player collision // TODO(behaviour): team creation happens in MinecraftServer (server area)

    // Paper start - respawn event
    public ServerPlayer respawn(final ServerPlayer serverPlayer, final boolean keepAllPlayerData, final Entity.RemovalReason removalReason, final org.bukkit.event.player.PlayerRespawnEvent.RespawnReason respawnReason) {
        net.fissionmc.craft.compat.RespawnResult result = serverPlayer.findRespawnPositionAndUseSpawnBlock0(!keepAllPlayerData, TeleportTransition.DO_NOTHING, respawnReason);
        if (result == null) { // disconnected player during the respawn event
            return serverPlayer;
        }
        TeleportTransition respawnInfo = result.transition();
        Level fromLevel = serverPlayer.level();
        // Paper end - respawn event
        this.players.remove(serverPlayer);
        this.playersByName.remove(serverPlayer.getScoreboardName().toLowerCase(java.util.Locale.ROOT)); // Paper
        serverPlayer.level().removePlayerImmediately(serverPlayer, removalReason);
        ServerLevel level = respawnInfo.newLevel();
        ServerPlayer player = serverPlayer; // Paper - TODO - recreate instance
        player.connection = serverPlayer.connection;
        player.restoreFrom(serverPlayer, keepAllPlayerData);
        player.setId(serverPlayer.getId());
        player.setMainArm(serverPlayer.getMainArm());
        if (false && !respawnInfo.missingRespawnBlock()) { // Paper - Once we not reuse the player entity, this can be flipped again but without the events being fired
            player.copyRespawnPosition(serverPlayer);
        }

        for (String tag : serverPlayer.entityTags()) {
            player.addTag(tag);
        }

        for (Identifier postEffect : serverPlayer.getPostEffects()) {
            player.addPostEffect(postEffect);
        }

        // Paper start - Once we not reuse the player entity we can remove this.
        if (!keepAllPlayerData) serverPlayer.reset();
        player.setServerLevel(level);
        player.unsetRemoved();
        player.setShiftKeyDown(false);
        player.connection.restartClientLoadTimerAfterRespawn();
        // Paper end
        Vec3 pos = respawnInfo.position();
        player.snapTo(pos.x, pos.y, pos.z, respawnInfo.yRot(), respawnInfo.xRot());
        player.connection.resetPosition(); // Paper - Fix SPIGOT-1903, MC-98153
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.POST_TELEPORT, new net.minecraft.world.level.ChunkPos(net.minecraft.util.Mth.floor(pos.x()) >> 4, net.minecraft.util.Mth.floor(pos.z()) >> 4), 1); // Paper - post teleport ticket type
        if (respawnInfo.missingRespawnBlock()) {
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F));
            player.setRespawnPosition(null, false, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN); // CraftBukkit - SPIGOT-5988: Clear respawn location when obstructed
        }

        byte dataToKeep = (byte)(keepAllPlayerData ? 1 : 0); // ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS
        ServerLevel playerLevel = player.level();
        LevelData levelData = playerLevel.getLevelData();
        player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(playerLevel), dataToKeep));
        player.connection.internalTeleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()); // Paper
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        player.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        this.sendActivePlayerEffects(player);
        player.sendPostEffects();
        this.sendLevelInfo(player, level);
        this.sendPlayerPermissionLevel(player);
        level.addRespawnedPlayer(player);
        this.players.add(player);
        this.playersByName.put(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT), player); // Paper
        this.playersByUUID.put(player.getUUID(), player);
        player.initInventoryMenu();
        player.setHealth(player.getHealth());
        // Paper start - Once we not reuse the player entity we can remove this.
        // But we have to resend the player info as it's not marked as dirty
        this.sendAllPlayerInfo(serverPlayer); // Update health
        serverPlayer.onUpdateAbilities(); // Update inventory, etc
        // Paper end
        ServerPlayer.RespawnConfig respawnConfig = player.getRespawnConfig();
        if (!keepAllPlayerData && respawnConfig != null) {
            RespawnData respawnData = respawnConfig.respawnData();
            ServerLevel respawnLevel = this.server.getLevel(respawnData.dimension());
            if (respawnLevel != null) {
                BlockPos respawnPosition = respawnData.pos();
                BlockState blockState = respawnLevel.getBlockState(respawnPosition);
                if (blockState.is(Blocks.RESPAWN_ANCHOR)) {
                    player.connection
                        .send(
                            new ClientboundSoundPacket(
                                SoundEvents.RESPAWN_ANCHOR_DEPLETE,
                                SoundSource.BLOCKS,
                                respawnPosition.getX(),
                                respawnPosition.getY(),
                                respawnPosition.getZ(),
                                1.0F,
                                1.0F,
                                level.getRandom().nextLong()
                            )
                        );
                }
            }
        }

        // Paper start
        // Save player file again if they were disconnected
        if (player.connection.isDisconnected()) {
            this.save(player);
        }

        // It's possible for respawn to be in a diff dimension
        if (fromLevel != level) {
            new org.bukkit.event.player.PlayerChangedWorldEvent(player.getBukkitEntity(), fromLevel.getWorld()).callEvent();
            player.triggerDimensionChangeTriggers(level);
        }

        // Call post respawn event
        new com.destroystokyo.paper.event.player.PlayerPostRespawnEvent(
            player.getBukkitEntity(),
            org.bukkit.craftbukkit.util.CraftLocation.toBukkit(respawnInfo.position(), level, respawnInfo.yRot(), respawnInfo.xRot()),
            result.isBedSpawn(),
            result.isAnchorSpawn(),
            respawnInfo.missingRespawnBlock(),
            respawnReason
        ).callEvent();
        // Paper end

        return player;
    }
    // Paper end - respawn event

    // Paper start - collect packets
    @Inject(method = "sendActiveEffects", at = @At("HEAD"), cancellable = true)
    private void paper$sendActiveEffects(final LivingEntity livingEntity, final ServerGamePacketListenerImpl connection, final CallbackInfo ci) {
        ci.cancel();
        this.sendActiveEffects(livingEntity, connection::send);
    }

    public void sendActiveEffects(final LivingEntity livingEntity, final java.util.function.Consumer<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> packetConsumer) {
        // Paper end - collect packets
        for (MobEffectInstance effect : livingEntity.getActiveEffects()) {
            packetConsumer.accept(new ClientboundUpdateMobEffectPacket(livingEntity.getId(), effect, false)); // Paper - collect packets
        }
    }

    // Paper start - avoid recalculating permissions if possible
    @Inject(method = "sendPlayerPermissionLevel(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void paper$sendPlayerPermissionLevel(final ServerPlayer player, final CallbackInfo ci) {
        ci.cancel();
        this.sendPlayerPermissionLevel(player, true);
    }

    @Inject(method = "sendPlayerPermissionLevel(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/permissions/LevelBasedPermissionSet;)V", at = @At("HEAD"), cancellable = true)
    private void paper$sendPlayerPermissionLevelSet(final ServerPlayer player, final LevelBasedPermissionSet permissions, final CallbackInfo ci) {
        ci.cancel();
        this.sendPlayerPermissionLevel(player, permissions, true); // Paper - Add sendOpLevel API
    }
    // Paper end - avoid recalculating permissions if possible

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$tick(final CallbackInfo ci) {
        ci.cancel();
        if (++this.sendAllPlayerInfoIn > 600) {
            // CraftBukkit start
            for (int i = 0; i < this.players.size(); ++i) {
                final ServerPlayer target = this.players.get(i);

                target.connection.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), com.google.common.collect.Collections2.filter(this.players, t -> target.getBukkitEntity().canSee(t.getBukkitEntity()))));
            }
            // CraftBukkit end
            this.sendAllPlayerInfoIn = 0;
        }
    }

    @Inject(method = "getPlayerByName", at = @At("HEAD"), cancellable = true)
    private void spigot$getPlayerByName(final String name, final CallbackInfoReturnable<ServerPlayer> cir) {
        cir.setReturnValue(this.playersByName.get(name.toLowerCase(java.util.Locale.ROOT))); // Spigot
    }

    // CraftBukkit start - Test if player receiving packet can see the source of the packet
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$broadcast(final @Nullable Player except, final double x, final double y, final double z, final double range, final ResourceKey<Level> dimension, final Packet<?> packet, final CallbackInfo ci) {
        ci.cancel();
        for (int i = 0; i < this.players.size(); i++) {
            ServerPlayer player = this.players.get(i);
            // CraftBukkit start - Test if player receiving packet can see the source of the packet
            if (except != null && !player.getBukkitEntity().canSee(except.getBukkitEntity())) {
               continue;
            }
            // CraftBukkit end
            if (player != except && player.level().dimension() == dimension) {
                double xd = x - player.getX();
                double yd = y - player.getY();
                double zd = z - player.getZ();
                if (xd * xd + yd * yd + zd * zd < range * range) {
                    player.connection.send(packet);
                }
            }
        }
    }

    private boolean fission$savingAll;

    @Inject(method = "saveAll", at = @At("HEAD"), cancellable = true)
    private void paper$saveAllEnsureMain(final CallbackInfo ci) {
        if (this.fission$savingAll) return;
        ci.cancel();
        io.papermc.paper.util.MCUtil.ensureMain("Save Players" , () -> { // Paper - Ensure main
            this.fission$savingAll = true;
            try {
                ((PlayerList) (Object) this).saveAll();
            } finally {
                this.fission$savingAll = false;
            }
            return null;
        }); // Paper - ensure main
    }

    // Paper: replaced body (per-world/per-player time, view distances, player weather)
    @Inject(method = "sendLevelInfo", at = @At("HEAD"), cancellable = true)
    private void paper$sendLevelInfo(final ServerPlayer player, final ServerLevel level, final CallbackInfo ci) {
        ci.cancel();
        WorldBorder worldBorder = level.getWorldBorder();
        player.connection.send(new ClientboundInitializeBorderPacket(worldBorder));
        player.connection.send(this.server.clockManager().createFullSyncPacket(player)); // Paper - per-world time; per-player time // FissionMC: Paper uses level.clockManager() (per-world clocks, not ported)
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        // Paper start - view distances
        player.connection.send(new ClientboundSetChunkCacheRadiusPacket(io.papermc.paper.FeatureHooks.getViewDistance(level)));
        player.connection.send(new ClientboundSetSimulationDistancePacket(io.papermc.paper.FeatureHooks.getSimulationDistance(level)));
        // Paper end - view distances
        if (level.isRaining()) {
            // CraftBukkit start - handle player weather
            player.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL, false);
            player.updateWeather(-level.rainLevel, level.rainLevel, -level.thunderLevel, level.thunderLevel);
        } else {
            player.setPlayerWeather(org.bukkit.WeatherType.CLEAR, false);
            player.updateWeather(-1.0F, level.rainLevel, -1.0F, level.thunderLevel);
            // CraftBukkit end
        }

        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
        this.server.tickRateManager().updateJoiningPlayer(player);
    }

    // Paper: replaced body (instance is reused on respawn: resend attributes, entity data, debug info and immediate respawn)
    @Inject(method = "sendAllPlayerInfo", at = @At("HEAD"), cancellable = true)
    private void paper$sendAllPlayerInfo(final ServerPlayer player, final CallbackInfo ci) {
        ci.cancel();
        player.inventoryMenu.sendAllDataToRemote();
        // entityplayer.resetSentInfo();
        // Paper start - send all attributes
        // needs to be done because the ServerPlayer instance is being reused on respawn instead of getting replaced like on vanilla
        java.util.Collection<net.minecraft.world.entity.ai.attributes.AttributeInstance> syncableAttributes = player.getAttributes().getSyncableAttributes();
        player.getBukkitEntity().injectScaledMaxHealth(syncableAttributes, true);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket(player.getId(), syncableAttributes));
        // Paper end - send all attributes
        player.refreshEntityData(player); // CraftBukkit - SPIGOT-7218: sync metadata
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        // CraftBukkit start - from GameRules
        int i = player.level().getGameRules().get(GameRules.REDUCED_DEBUG_INFO) ? 22 : 23;
        player.connection.send(new ClientboundEntityEventPacket(player, (byte) i));
        float immediateRespawn = player.level().getGameRules().get(GameRules.IMMEDIATE_RESPAWN) ? 1.0F: 0.0F;
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.IMMEDIATE_RESPAWN, immediateRespawn));
        // CraftBukkit end
    }

    @Inject(method = "removeAll()V", at = @At("HEAD"), cancellable = true)
    private void paper$removeAll(final CallbackInfo ci) {
        ci.cancel();
        // Paper start - Extract method to allow for restarting flag
        this.removeAll(false);
    }

    public void removeAll(boolean isRestarting) {
        // Paper end
        // CraftBukkit start - disconnect safely
        for (ServerPlayer player : this.players) {
            if (isRestarting) player.connection.disconnect(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(org.spigotmc.SpigotConfig.restartMessage), org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN); else // Paper - kick event cause (cause is never used here)
            player.connection.disconnect(java.util.Objects.requireNonNullElseGet(this.server.server.shutdownMessage(), net.kyori.adventure.text.Component::empty)); // CraftBukkit - add custom shutdown message // Paper - Adventure
        }
        // CraftBukkit end

        // Paper start - Configurable player collision; Remove collideRule team if it exists
        if (this.collideRuleTeamName != null) {
            final net.minecraft.world.scores.Scoreboard scoreboard = this.getServer().getLevel(Level.OVERWORLD).getScoreboard();
            final PlayerTeam team = scoreboard.getPlayersTeam(this.collideRuleTeamName);
            if (team != null) scoreboard.removePlayerTeam(team);
        }
        // Paper end - Configurable player collision
    }

    // Paper start - vanilla broadcastChatMessage overloads delegate with unsignedFunction = null
    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V", at = @At("HEAD"), cancellable = true)
    private void paper$broadcastChatMessage(final PlayerChatMessage message, final ServerPlayer sender, final ChatType.Bound chatType, final CallbackInfo ci) {
        ci.cancel();
        this.broadcastChatMessage(message, sender, chatType, (Function<net.kyori.adventure.audience.Audience, Component>) null);
    }

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V", at = @At("HEAD"), cancellable = true)
    private void paper$broadcastChatMessageFiltered(final PlayerChatMessage message, final Predicate<ServerPlayer> isFiltered, final @Nullable ServerPlayer senderPlayer, final ChatType.Bound chatType, final CallbackInfo ci) {
        ci.cancel();
        this.broadcastChatMessage(message, isFiltered, senderPlayer, chatType, (Function<net.kyori.adventure.audience.Audience, Component>) null);
    }
    // Paper end

    // Paper start - API for updating recipes on clients
    @Inject(method = "reloadResources", at = @At("HEAD"), cancellable = true)
    private void paper$reloadResources(final CallbackInfo ci) {
        ci.cancel();
        this.reloadAdvancementData();
        this.reloadTagData();
        this.reloadRecipes();
    }
    // Paper end - API for updating recipes on clients

    // Paper start - avoid recalculating permissions if possible
    public void sendPlayerPermissionLevel(final ServerPlayer player, final boolean recalculatePermissions) {
        // Paper end - avoid recalculating permissions if possible
        LevelBasedPermissionSet permissions = this.server.getProfilePermissions(player.nameAndId());
        this.sendPlayerPermissionLevel(player, permissions, recalculatePermissions); // Paper - avoid recalculating permissions if possible
    }

    // CraftBukkit start - add a world/entity limited version
    public void broadcastAll(Packet packet, net.minecraft.world.entity.player.Player entityhuman) {
        for (ServerPlayer entityplayer : this.players) { // Paper - replace for i with for each for thread safety
            if (entityhuman != null && !entityplayer.getBukkitEntity().canSee(entityhuman.getBukkitEntity())) {
                continue;
            }
            ((ServerPlayer) entityplayer).connection.send(packet); // Paper - replace for i with for each for thread safety
        }
    }

    public void broadcastAll(Packet packet, Level world) {
        for (int i = 0; i < world.players().size(); ++i) {
            ((ServerPlayer) world.players().get(i)).connection.send(packet);
        }

    }
    // CraftBukkit end

    // Paper start - Add sendOpLevel API
    public void sendPlayerPermissionLevel(final ServerPlayer player, final LevelBasedPermissionSet permissions, final boolean recalculatePermissions) {
        // Paper end - Add sendOpLevel API
        if (player.connection != null) {
            byte eventId = switch (permissions.level()) {
                case ALL -> EntityEvent.PERMISSION_LEVEL_ALL;
                case MODERATORS -> EntityEvent.PERMISSION_LEVEL_MODERATORS;
                case GAMEMASTERS -> EntityEvent.PERMISSION_LEVEL_GAMEMASTERS;
                case ADMINS -> EntityEvent.PERMISSION_LEVEL_ADMINS;
                case OWNERS -> EntityEvent.PERMISSION_LEVEL_OWNERS;
            };
            player.connection.send(new ClientboundEntityEventPacket(player, eventId));
        }

        if (recalculatePermissions) { // Paper - Add sendOpLevel API
        player.getBukkitEntity().recalculatePermissions(); // CraftBukkit
        this.server.getCommands().sendCommands(player);
        } // Paper - Add sendOpLevel API
    }

    // Paper start - whitelist verify event / login event
    public LoginResult canBypassFullServerLogin(final NameAndId nameAndId, final LoginResult currentResult) {
        final boolean shouldKick = this.players.size() >= this.getMaxPlayers() && !this.canBypassPlayerLimit(nameAndId);
        final io.papermc.paper.event.player.PlayerServerFullCheckEvent fullCheckEvent = new io.papermc.paper.event.player.PlayerServerFullCheckEvent(
            new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId),
            io.papermc.paper.adventure.PaperAdventure.asAdventure(currentResult.message()),
            shouldKick
        );

        fullCheckEvent.callEvent();
        if (fullCheckEvent.isAllowed()) {
            return LoginResult.ALLOW;
        } else {
            return new LoginResult(
                io.papermc.paper.adventure.PaperAdventure.asVanilla(fullCheckEvent.kickMessage()), currentResult.result()
            );
        }
    }

    public LoginResult isWhiteListedLogin(NameAndId nameAndId) {
        boolean isOp = ((StoredUserListMixin) (Object) this.ops).fission$contains(nameAndId); // FissionMC: protected, same-package access in Paper
        boolean isWhitelisted = !this.isUsingWhitelist() || isOp || ((StoredUserListMixin) (Object) this.whitelist).fission$contains(nameAndId); // FissionMC: protected, same-package access in Paper

        final net.kyori.adventure.text.Component configuredMessage = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(org.spigotmc.SpigotConfig.whitelistMessage);
        final com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent event
            = new com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent(new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId), this.isUsingWhitelist(), isWhitelisted, isOp, configuredMessage);
        event.callEvent();
        if (!event.isWhitelisted()) {
            return new LoginResult(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.kickMessage() == null ? configuredMessage : event.kickMessage()), org.bukkit.event.player.PlayerLoginEvent.Result.KICK_WHITELIST);
        }

        return LoginResult.ALLOW;
    }
    // Paper end

    // Paper start
    public void broadcastChatMessage(final PlayerChatMessage message, final ServerPlayer sender, final ChatType.Bound chatType, final @Nullable Function<net.kyori.adventure.audience.Audience, Component> unsignedFunction) {
        // Paper end
        this.broadcastChatMessage(message, sender::shouldFilterMessageTo, sender, chatType, unsignedFunction); // Paper
    }

    public void broadcastChatMessage(final PlayerChatMessage message, final Predicate<ServerPlayer> isFiltered, final @Nullable ServerPlayer senderPlayer, final ChatType.Bound chatType, final @Nullable Function<net.kyori.adventure.audience.Audience, Component> unsignedFunction) {
        // Paper end
        boolean trusted = this.verifyChatTrusted(message);
        this.server.logChatMessage((unsignedFunction == null ? message.decoratedContent() : unsignedFunction.apply(this.server.console)), chatType, trusted ? null : "Not Secure"); // Paper
        OutgoingChatMessage tracked = OutgoingChatMessage.create(message);
        boolean wasFullyFiltered = false;

        Packet<?> disguised = senderPlayer != null && unsignedFunction == null ? new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(tracked.content(), chatType) : null; // Paper - don't send player chat packets from vanished players
        for (ServerPlayer player : this.players) {
            boolean filtered = isFiltered.test(player);
            // Paper start - don't send player chat packets from vanished players
            if (senderPlayer != null && !player.getBukkitEntity().canSee(senderPlayer.getBukkitEntity())) {
                player.connection.send(unsignedFunction != null
                    ? new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(unsignedFunction.apply(player.getBukkitEntity()), chatType)
                    : disguised);
                continue;
            }
            player.sendChatMessage(tracked, filtered, chatType, unsignedFunction == null ? null : unsignedFunction.apply(player.getBukkitEntity()));
            // Paper end
            wasFullyFiltered |= filtered && message.isFullyFiltered();
        }

        if (wasFullyFiltered && senderPlayer != null) {
            senderPlayer.sendSystemMessage(CHAT_FILTERED_FULL);
        }
    }

    // CraftBukkit start
    // TODO(behaviour): Paper replaces getPlayerStats(Player) (per-UUID map) with these; ServerPlayer's constructor calls the vanilla one
    public ServerStatsCounter getPlayerStats(final ServerPlayer player) {
        GameProfile gameProfile = player.getGameProfile();
        ServerStatsCounter playerStatsCounter = player.getStats();
        if (playerStatsCounter == null) {
            return this.getPlayerStats(gameProfile);
        } else {
            return playerStatsCounter;
        }
    }

    public ServerStatsCounter getPlayerStats(final GameProfile gameProfile) {
            Path targetFile = this.locateStatsFile(gameProfile);
            return new ServerStatsCounter(this.server, targetFile);
    }
    // CraftBukkit end

    // Paper start - API for updating recipes on clients
    public void reloadAdvancementData() {
        // Paper end - API for updating recipes on clients
        // CraftBukkit start
        for (ServerPlayer player : this.players) {
            player.getAdvancements().reload(this.server.getAdvancements());
            player.getAdvancements().flushDirty(player, false); // CraftBukkit - trigger immediate flush of advancements
        }
        // CraftBukkit end
        // Paper start - API for updating recipes on clients
    }

    public void reloadTagData() {
        this.broadcastAll(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(this.registries)));
        // Paper end - API for updating recipes on clients
    }

    public void reloadRecipes() {
        // CraftBukkit end
        RecipeManager recipeManager = this.server.getRecipeManager();
        ClientboundUpdateRecipesPacket recipes = new ClientboundUpdateRecipesPacket(
            recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes()
        );

        for (ServerPlayer player : this.players) {
            player.connection.send(recipes);
            player.getRecipeBook().sendInitialRecipeBook(player);
        }
    }

    // Paper: replaced body (PlayerJoinEvent, per-viewer player info, tracker after list packets, collide rule team)
    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void paper$placeNewPlayer(final Connection connection, final ServerPlayer player, final CommonListenerCookie cookie, final CallbackInfo ci) {
        ci.cancel();
        player.isRealPlayer = true; // Paper
        player.loginTime = System.currentTimeMillis(); // Paper - Replace OfflinePlayer#getLastPlayed
        NameAndId gameProfile = player.nameAndId();
        UserNameToIdResolver profileCache = this.server.services().nameToIdCache();
        Optional<NameAndId> oldProfile = profileCache.get(gameProfile.id());
        String oldName = oldProfile.map(NameAndId::name).orElse(gameProfile.name());
        if (player.lastKnownName != null) { oldName = player.lastKnownName; player.lastKnownName = null; } // CraftBukkit - Better rename detection
        profileCache.add(gameProfile);
        ServerLevel level = player.level();
        String address = connection.getLoggableAddress(this.server.logIPs());
        LevelData levelData = level.getLevelData();
        ServerGamePacketListenerImpl playerConnection = new ServerGamePacketListenerImpl(this.server, connection, player, cookie);
        connection.setupInboundProtocol(
            GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess()), playerConnection), playerConnection
        );
        playerConnection.suspendFlushing();
        GameRules gameRules = level.getGameRules();
        boolean immediateRespawn = gameRules.get(GameRules.IMMEDIATE_RESPAWN);
        boolean reducedDebugInfo = gameRules.get(GameRules.REDUCED_DEBUG_INFO);
        boolean doLimitedCrafting = gameRules.get(GameRules.LIMITED_CRAFTING);
        playerConnection.send(
            new ClientboundLoginPacket(
                player.getId(),
                levelData.isHardcore(),
                this.server.levelKeys(),
                this.getMaxPlayers(),
                io.papermc.paper.FeatureHooks.getViewDistance(level), // Paper - view distance
                io.papermc.paper.FeatureHooks.getSimulationDistance(level), // Paper - simulation distance
                reducedDebugInfo,
                !immediateRespawn,
                doLimitedCrafting,
                player.createCommonSpawnInfo(level),
                this.server.usesAuthentication(),
                this.server.enforceSecureProfile()
            )
        );
        player.getBukkitEntity().sendSupportedChannels(); // CraftBukkit
        playerConnection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        playerConnection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        playerConnection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        RecipeManager recipeManager = this.server.getRecipeManager();
        playerConnection.send(
            new ClientboundUpdateRecipesPacket(recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes())
        );
        this.sendPlayerPermissionLevel(player);
        player.getStats().markAllDirty();
        player.getRecipeBook().sendInitialRecipeBook(player);
        this.updateEntireScoreboard(level.getScoreboard(), player);
        this.server.invalidateStatus();
        MutableComponent component;
        if (player.getGameProfile().name().equalsIgnoreCase(oldName)) {
            component = Component.translatable("multiplayer.player.joined", player.getDisplayName());
        } else {
            component = Component.translatable("multiplayer.player.joined.renamed", player.getDisplayName(), oldName);
        }

        // CraftBukkit start
        component.withStyle(ChatFormatting.YELLOW);
        Component joinMessage = component; // Paper - Adventure
        playerConnection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        ServerStatus status = this.server.getStatus();
        if (status != null && !cookie.transferred()) {
            player.sendServerStatus(status);
        }

        // player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(this.players)); // CraftBukkit - replaced with loop below
        this.players.add(player);
        this.playersByName.put(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT), player); // Spigot
        this.playersByUUID.put(player.getUUID(), player);
        // this.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player))); // CraftBukkit - replaced with loop below
        // Paper start - Fire PlayerJoinEvent when Player is actually ready; correctly register player BEFORE PlayerJoinEvent, so the entity is valid and doesn't require tick delay hacks
        player.suppressTrackerForLogin = true;
        this.sendLevelInfo(player, level);
        level.addNewPlayer(player);
        this.server.getCustomBossEvents().onPlayerConnect(player); // see commented out section below serverLevel.addPlayerJoin(player);
        // Paper end - Fire PlayerJoinEvent when Player is actually ready
        player.initInventoryMenu();
        // CraftBukkit start
        org.bukkit.craftbukkit.entity.CraftPlayer bukkitPlayer = player.getBukkitEntity();

        // Ensure that player inventory is populated with its viewer
        player.containerMenu.transferTo(player.containerMenu, bukkitPlayer);

        org.bukkit.event.player.PlayerJoinEvent playerJoinEvent = new org.bukkit.event.player.PlayerJoinEvent(bukkitPlayer, io.papermc.paper.adventure.PaperAdventure.asAdventure(component)); // Paper - Adventure
        this.cserver.getPluginManager().callEvent(playerJoinEvent);

        if (!player.connection.isAcceptingMessages()) {
            return;
        }

        final net.kyori.adventure.text.Component jm = playerJoinEvent.joinMessage();

        if (jm != null && !jm.equals(net.kyori.adventure.text.Component.empty()) && !net.fissionmc.backend.relay.LocalAnnouncements.suppress(player.getUUID())) { // Paper - Adventure; FissionMC: relayed players are announced by the master
            joinMessage = io.papermc.paper.adventure.PaperAdventure.asVanilla(jm); // Paper - Adventure
            this.server.getPlayerList().broadcastSystemMessage(joinMessage, false); // Paper - Adventure
        }
        // CraftBukkit end

        // CraftBukkit start - sendAll above replaced with this loop
        ClientboundPlayerInfoUpdatePacket packet = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)); // Paper - Add Listing API for Player

        final List<ServerPlayer> onlinePlayers = Lists.newArrayListWithExpectedSize(this.players.size() - 1); // Paper - Use single player info update packet on join
        for (int i = 0; i < this.players.size(); ++i) {
            ServerPlayer entityplayer1 = (ServerPlayer) this.players.get(i);

            if (entityplayer1.getBukkitEntity().canSee(bukkitPlayer)) {
                // Paper start - Add Listing API for Player
                if (entityplayer1.getBukkitEntity().isListed(bukkitPlayer)) {
                    // Paper end - Add Listing API for Player
                    entityplayer1.connection.send(packet);
                    // Paper start - Add Listing API for Player
                } else {
                    entityplayer1.connection.send(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(player, false));
                }
                // Paper end - Add Listing API for Player
            }

            if (entityplayer1 == player || !bukkitPlayer.canSee(entityplayer1.getBukkitEntity())) { // Paper - Use single player info update packet on join; Don't include joining player
                continue;
            }

            onlinePlayers.add(entityplayer1); // Paper - Use single player info update packet on join
        }
        // Paper start - Use single player info update packet on join
        if (!onlinePlayers.isEmpty()) {
            player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(onlinePlayers, player)); // Paper - Add Listing API for Player
        }
        // Paper end - Use single player info update packet on join
        player.sentListPacket = true;
        player.suppressTrackerForLogin = false; // Paper - Fire PlayerJoinEvent when Player is actually ready
        player.level().getChunkSource().addEntity(player); // Paper - Fire PlayerJoinEvent when Player is actually ready; track entity now
        // CraftBukkit end

        //player.refreshEntityData(player); // CraftBukkit - BungeeCord#2321, send complete data to self on spawn // Paper - THIS IS NOT NEEDED ANYMORE

        this.sendLevelInfo(player, level);

        // CraftBukkit start - Only add if the player wasn't moved in the event
        if (player.level() == level && !level.players().contains(player)) {
            level.addNewPlayer(player);
            this.server.getCustomBossEvents().onPlayerConnect(player);
        }

        level = player.level(); // CraftBukkit - Update in case join event changed it
        // CraftBukkit end
        this.sendActivePlayerEffects(player);
        player.sendPostEffects();
        // Paper - move loading pearls / parent vehicle up
        player.initInventoryMenu();
        this.server.notificationManager().playerJoined(player);
        playerConnection.resumeFlushing();
        // Paper start - Configurable player collision; Add to collideRule team if needed
        final net.minecraft.world.scores.Scoreboard scoreboard = this.getServer().getLevel(Level.OVERWORLD).getScoreboard();
        final PlayerTeam collideRuleTeam = scoreboard.getPlayerTeam(this.collideRuleTeamName);
        if (this.collideRuleTeamName != null && collideRuleTeam != null && player.getTeam() == null) {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), collideRuleTeam);
        }
        // Paper end - Configurable player collision
        // CraftBukkit start - moved down
        LOGGER.info(
            "{}[{}] logged in with entity id {} at ([{}]{}, {}, {})", // Paper - add world identifier
            player.getPlainTextName(),
            address,
            player.getId(),
            level.dimension().identifier(), // Paper - add world identifier
            player.getX(),
            player.getY(),
            player.getZ()
        );
        // CraftBukkit end - moved down
        // Paper start - Send empty chunk, so players aren't stuck in the world loading screen with our chunk system not sending chunks when dead
        if (player.isDeadOrDying()) {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> plains = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                new net.minecraft.world.level.chunk.EmptyLevelChunk(level, player.chunkPosition(), plains),
                level.getLightEngine(), (java.util.BitSet)null, (java.util.BitSet) null)
            );
        }
        // Paper end - Send empty chunk
    }

    // Paper start - virtual world border API
    public void fission$broadcastWorldborder(Packet<?> packet, ResourceKey<Level> dimension) { // FissionMC: private broadcastWorldborder in Paper, called from PlayerList_1Mixin
        for (ServerPlayer serverPlayer : this.players) {
            if (serverPlayer.level().dimension() == dimension && serverPlayer.getBukkitEntity().getWorldBorder() == null) {
                serverPlayer.connection.send(packet);
            }
        }
    }
    // Paper end - virtual world border API

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$saveIfPersistent(final ServerPlayer player, final CallbackInfo ci) {
        if (!player.getBukkitEntity().isPersistent()) ci.cancel(); // CraftBukkit
    }

    // Paper: remove(ServerPlayer) returns the quit message; vanilla's void method delegates.
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void paper$remove(final ServerPlayer player, final CallbackInfo ci) {
        ci.cancel();
        this.fission$remove(player);
    }

    public net.kyori.adventure.text.@Nullable Component fission$remove(final ServerPlayer player) { // CraftBukkit - return string // Paper - return Component
        // Paper start - Fix kick event leave message not being sent
        return this.fission$remove(player, net.kyori.adventure.text.Component.translatable("multiplayer.player.left", net.kyori.adventure.text.format.NamedTextColor.YELLOW, io.papermc.paper.configuration.GlobalConfiguration.get().messages.useDisplayNameInQuitMessage ? player.getBukkitEntity().displayName() : io.papermc.paper.adventure.PaperAdventure.asAdventure(player.getDisplayName())));
    }

    public net.kyori.adventure.text.@Nullable Component fission$remove(final ServerPlayer player, final net.kyori.adventure.text.Component leaveMessage) {
        // Paper end - Fix kick event leave message not being sent
        ServerLevel level = player.level();
        player.awardStat(Stats.LEAVE_GAME);
        // CraftBukkit start - Quitting must be before we do final save of data, in case plugins need to modify it
        // See SPIGOT-5799, SPIGOT-6145
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer(org.bukkit.event.inventory.InventoryCloseEvent.Reason.DISCONNECT); // Paper - Inventory close reason
        }

        org.bukkit.event.player.PlayerQuitEvent playerQuitEvent = new org.bukkit.event.player.PlayerQuitEvent(player.getBukkitEntity(), leaveMessage, player.quitReason); // Paper - Adventure & Add API for quit reason
        this.cserver.getPluginManager().callEvent(playerQuitEvent);
        player.getBukkitEntity().disconnect();

        // CraftBukkit end

        // Paper start - Configurable player collision; Remove from collideRule team if needed
        if (this.collideRuleTeamName != null) {
            final net.minecraft.world.scores.Scoreboard scoreBoard = this.server.getLevel(Level.OVERWORLD).getScoreboard();
            final PlayerTeam team = scoreBoard.getPlayersTeam(this.collideRuleTeamName);
            if (player.getTeam() == team && team != null) {
                scoreBoard.removePlayerFromTeam(player.getScoreboardName(), team);
            }
        }
        // Paper end - Configurable player collision

        // Paper - Drop carried item when player has disconnected
        if (!player.containerMenu.getCarried().isEmpty()) {
            net.minecraft.world.item.ItemStack carried = player.containerMenu.getCarried();
            player.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
            player.drop(carried, false, net.minecraft.util.Prediction.PREDICTED);
        }
        // Paper end - Drop carried item when player has disconnected
        this.save(player);
        if (player.isPassenger()) {
            Entity vehicle = player.getRootVehicle();
            if (vehicle.hasExactlyOnePlayerPassenger()) {
                LOGGER.debug("Removing player mount");
                player.stopRiding();
                vehicle.getPassengersAndSelf().forEach(e -> {
                    // Paper start - Fix villager boat exploit
                    if (e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager villager) {
                        final net.minecraft.world.entity.player.Player human = villager.getTradingPlayer();
                        if (human != null) {
                            villager.setTradingPlayer(null);
                        }
                    }
                    // Paper end - Fix villager boat exploit
                    e.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, org.bukkit.event.entity.EntityRemoveEvent.Cause.PLAYER_QUIT); // CraftBukkit - add Bukkit remove cause
                });
            }
        }

        player.unRide();

        for (ThrownEnderpearl enderpearl : player.getEnderPearls()) {
            // Paper start - Allow using old ender pearl behavior
            if (!enderpearl.level().paperConfig().misc.legacyEnderPearlBehavior) {
                enderpearl.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, org.bukkit.event.entity.EntityRemoveEvent.Cause.PLAYER_QUIT); // CraftBukkit - add Bukkit remove cause
            }
            // Paper end - Allow using old ender pearl behavior
        }

        level.removePlayerImmediately(player, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        player.retireScheduler(); // Paper - Folia schedulers
        player.getAdvancements().clearTriggers();
        this.players.remove(player);
        this.playersByName.remove(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT)); // Spigot
        this.server.getCustomBossEvents().onPlayerDisconnect(player);
        UUID uuid = player.getUUID();
        ServerPlayer serverPlayer = this.playersByUUID.get(uuid);
        if (serverPlayer == player) {
            this.playersByUUID.remove(uuid);
            // FissionMC: the vanilla stats/advancements maps are kept (Paper removes them); drop this player's entries
            this.stats.remove(uuid);
            this.advancements.remove(uuid);
            this.server.notificationManager().playerLeft(player);
        }

        // CraftBukkit start
        // this.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));
        for (int i = 0; i < this.players.size(); i++) {
            ServerPlayer otherPlayer = this.players.get(i);

            if (otherPlayer.getBukkitEntity().canSee(player.getBukkitEntity())) {
                otherPlayer.connection.send(packet);
            } else {
                otherPlayer.getBukkitEntity().onEntityRemove(player);
            }
        }
        // This removes the scoreboard (and player reference) for the specific player in the manager
        this.cserver.getScoreboardManager().removePlayer(player.getBukkitEntity());
        // CraftBukkit end
        return net.fissionmc.backend.relay.LocalAnnouncements.suppress(player.getUUID()) ? null : playerQuitEvent.quitMessage(); // Paper - Adventure; FissionMC: relayed players are announced by the master
    }

    @Shadow @Final private Map<UUID, net.minecraft.stats.ServerStatsCounter> stats;
    @Shadow @Final private Map<UUID, PlayerAdvancements> advancements;

    // Paper: canPlayerLogin returns LoginResult (Paper changes the return type; vanilla's delegates)
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void paper$canPlayerLogin(final SocketAddress address, final NameAndId nameAndId, final CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(this.fission$canPlayerLogin(address, nameAndId).message());
    }

    public LoginResult fission$canPlayerLogin(final SocketAddress address, final NameAndId nameAndId) { // Paper - PlayerLoginEvent
        LoginResult whitelistEventResult; // Paper
        // Paper start - Fix MC-158900
        UserBanListEntry ban1;
        if (this.bans.isBanned(nameAndId) && (ban1 = this.bans.get(nameAndId)) != null) {
            UserBanListEntry ban = ban1;
            // Paper end - Fix MC-158900
            MutableComponent reason = Component.translatable("multiplayer.disconnect.banned.reason", ban.getReasonMessage());
            if (ban.getExpires() != null) {
                reason.append(Component.translatable("multiplayer.disconnect.banned.expiration", BAN_DATE_FORMAT.format(ban.getExpires())));
            }

            return new LoginResult(reason, org.bukkit.event.player.PlayerLoginEvent.Result.KICK_BANNED); // Paper - PlayerLoginEvent
        } else {
            if ((whitelistEventResult = this.isWhiteListedLogin(nameAndId)).result() == org.bukkit.event.player.PlayerLoginEvent.Result.KICK_WHITELIST) { // Paper - whitelist event
                return whitelistEventResult; // Paper - whitelist event
            }

            if (this.ipBans.isBanned(address)) {
                IpBanListEntry ban = this.ipBans.get(address);
                MutableComponent reason = Component.translatable("multiplayer.disconnect.banned_ip.reason", ban.getReasonMessage());
                if (ban.getExpires() != null) {
                    reason.append(Component.translatable("multiplayer.disconnect.banned_ip.expiration", BAN_DATE_FORMAT.format(ban.getExpires())));
                }

                return new LoginResult(reason, org.bukkit.event.player.PlayerLoginEvent.Result.KICK_BANNED); // Paper - PlayerLoginEvent
            } else {
                return this.canBypassFullServerLogin(nameAndId, new LoginResult(Component.translatable("multiplayer.disconnect.server_full"), org.bukkit.event.player.PlayerLoginEvent.Result.KICK_FULL)); // Paper - PlayerServerFullCheckEvent
            }
        }
    }

    public boolean disconnectAllPlayersWithProfile(final GameProfile profile) { // Paper - validate usernames
        UUID playerId = profile.id(); // Paper - validate usernames
        Set<ServerPlayer> dupes = Sets.newIdentityHashSet();

        for (ServerPlayer player : this.players) {
            if (player.getUUID().equals(playerId) || (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode() && player.getGameProfile().name().equalsIgnoreCase(profile.name()))) { // Paper - validate usernames
                dupes.add(player);
            }
        }

        ServerPlayer player = this.playersByUUID.get(playerId);
        if (player != null) {
            dupes.add(player);
        }

        for (ServerPlayer dupe : dupes) {
            dupe.connection.disconnect(DUPLICATE_LOGIN_DISCONNECT_MESSAGE, io.papermc.paper.connection.DisconnectionReason.DUPLICATE_LOGIN_MESSAGE); // Paper - disconnect API
        }

        return !dupes.isEmpty();
    }

    // Paper: the vanilla 3-argument respawn delegates to Paper's (keepAllPlayerData is only true when leaving the End)
    @Inject(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"), cancellable = true)
    private void paper$respawn(final ServerPlayer serverPlayer, final boolean keepAllPlayerData, final Entity.RemovalReason removalReason, final CallbackInfoReturnable<ServerPlayer> cir) {
        cir.setReturnValue(this.respawn(serverPlayer, keepAllPlayerData, removalReason, keepAllPlayerData ? org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL : org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH));
    }
}
