package net.fissionmc.backend.mixin.server.level;

import com.mojang.authlib.GameProfile;
import java.util.Collection;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/level/ServerPlayer.java.patch */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends Player {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private static Component SPAWN_SET_MESSAGE;
    @Shadow public ServerGamePacketListenerImpl connection;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public ServerPlayerGameMode gameMode;
    @Shadow private ServerPlayer.@Nullable RespawnConfig respawnConfig;
    @Shadow private boolean postEffectsDirty;
    @Shadow private int containerCounter;

    @Shadow protected abstract void setShoulderEntityLeft(CompoundTag tag);
    @Shadow protected abstract void setShoulderEntityRight(CompoundTag tag);
    @Shadow private boolean acceptsChatMessages() { throw new AssertionError(); }

    protected ServerPlayerMixin(final Level level, final GameProfile gameProfile) {
        super(level, gameProfile);
    }

    private ServerPlayer fission$self() {
        return (ServerPlayer) (Object) this;
    }

    // Surface: public fields (see surface/members/player.txt). Mixin requires private fields; the loader widens them.
    // TODO(behaviour): Paper changes `language` to default to null
    private java.util.Locale adventure$locale = java.util.Locale.US; // Paper
    private int containerUpdateDelay; // Paper - Configurable container update tick rate // TODO(behaviour): used in tick()
    private long loginTime; // Paper - Replace OfflinePlayer#getLastPlayed
    private int patrolSpawnDelay; // Paper - Pillager patrol spawn settings and per player options
    // Paper start - cancellable death event
    private boolean queueHealthUpdatePacket;
    private net.minecraft.network.protocol.game.@Nullable ClientboundSetHealthPacket queuedHealthUpdatePacket;
    // Paper end - cancellable death event
    // CraftBukkit start
    private @Nullable String lastKnownName; // Better rename detection
    private String displayName;
    private net.kyori.adventure.text.Component adventure$displayName; // Paper
    private @Nullable Component listName; // TODO(behaviour): getTabListDisplayName() returns listName
    private int listOrder = 0; // TODO(behaviour): getTabListOrder() returns listOrder
    private org.bukkit.Location compassTarget;
    private int newExp = 0;
    private int newLevel = 0;
    private int newTotalExp = 0;
    private boolean keepLevel = false;
    private double maxHealthCache;
    private boolean joining = true; // TODO(behaviour): reset in tick()
    private boolean sentListPacket = false;
    private boolean suppressTrackerForLogin = false; // Paper - Fire PlayerJoinEvent when Player is actually ready
    // CraftBukkit end
    private boolean isRealPlayer; // Paper
    private com.destroystokyo.paper.event.entity.@Nullable PlayerNaturallySpawnCreaturesEvent playerNaturallySpawnedEvent; // Paper - PlayerNaturallySpawnCreaturesEvent
    private org.bukkit.event.player.PlayerQuitEvent.@Nullable QuitReason quitReason = null; // Paper - Add API for quit reason

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$init(final MinecraftServer server, final ServerLevel level, final GameProfile gameProfile, final ClientInformation clientInformation, final CallbackInfo ci) {
        // TODO(behaviour): Paper calls updateOptionsNoEvents instead of updateOptions here (don't call options events on login)
        // CraftBukkit start
        this.displayName = this.getScoreboardName();
        this.adventure$displayName = net.kyori.adventure.text.Component.text(this.getScoreboardName()); // Paper
        // TODO(behaviour): this.bukkitPickUpLoot = true; (Mob/LivingEntity field, entity area)
        this.maxHealthCache = this.getMaxHealth();
        // CraftBukkit end
    }

    // Paper start - release entity api
    public Entity releaseLeftShoulderEntity() {
        Entity entity = this.respawnEntityOnShoulder0(this.fission$self().getShoulderEntityLeft());
        if (entity != null) {
            this.setShoulderEntityLeft(new CompoundTag());
        }
        return entity;
    }

    public Entity releaseRightShoulderEntity() {
        Entity entity = this.respawnEntityOnShoulder0(this.fission$self().getShoulderEntityRight());
        if (entity != null) {
            this.setShoulderEntityRight(new CompoundTag());
        }
        return entity;
    }
    // Paper end - release entity api

    // TODO(behaviour): Paper makes removeEntitiesOnShoulder/respawnEntityOnShoulder use this (void -> boolean)
    private @Nullable Entity respawnEntityOnShoulder0(final CompoundTag tag) { // CraftBukkit void->boolean
        ServerLevel serverLevel = this.fission$self().level();
        if (!tag.isEmpty()) {
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(this.problemPath(), LOGGER)) {
                return EntityType.create( // Paper - release entity api
                        TagValueInput.create(reporter.forChild(() -> ".shoulder"), serverLevel.registryAccess(), tag),
                        serverLevel,
                        new EntitySpawnRequest(EntitySpawnReason.LOAD, false)
                    )
                    .map(entity -> { // Paper - release entity api
                        if (entity instanceof TamableAnimal tamed) {
                            tamed.setOwner(this);
                        }

                        entity.setPos(this.getX(), this.getY() + 0.7F, this.getZ());
                        // TODO(behaviour): Paper passes CreatureSpawnEvent.SpawnReason.SHOULDER_ENTITY to addWithUUID
                        return serverLevel.addWithUUID(entity) ? entity : null; // Paper - spawn reason
                    }).orElse(null); // Paper - release entity api - return entity
            }
        }
        return null; // Paper - return null
    }

    // Paper start - Post effects API
    public boolean setPostEffects(final Collection<Identifier> postEffects) {
        if (com.google.common.collect.Iterables.elementsEqual(this.postEffects, postEffects)) {
            return false;
        }

        this.postEffects.clear();
        this.postEffects.addAll(postEffects);
        this.postEffectsDirty = true;
        return true;
    }
    // Paper end - Post effects API

    // Paper start - Inventory close reason
    // TODO(behaviour): vanilla closeContainer() should delegate here with Reason.UNKNOWN
    public void closeContainer(org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        org.bukkit.craftbukkit.event.CraftEventFactory.handleInventoryCloseEvent(this, reason); // CraftBukkit
        // Paper end - Inventory close reason
        this.connection.send(new ClientboundContainerClosePacket(this.containerMenu.containerId));
        this.fission$self().doCloseContainer();
    }

    // Paper start - special close for unloaded inventory
    public void closeUnloadedInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        // copied from above
        org.bukkit.craftbukkit.event.CraftEventFactory.handleInventoryCloseEvent(this, reason); // CraftBukkit
        // Paper end
        // copied from below
        this.connection.send(new ClientboundContainerClosePacket(this.containerMenu.containerId));
        this.containerMenu = this.inventoryMenu;
        // do not run close logic
    }
    // Paper end - special close for unloaded inventory

    // TODO(behaviour): vanilla teleportTo(double, double, double) should delegate here with TeleportCause.UNKNOWN; dismountTo uses DISMOUNT
    public void teleportTo(final double x, final double y, final double z, final org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        this.connection.teleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0.0F, 0.0F), Relative.union(Relative.DELTA, Relative.ROTATION), cause);
        // Paper end - pass cause
    }

    // TODO(behaviour): vanilla setGameMode(GameType) should delegate here with Cause.UNKNOWN
    public org.bukkit.event.player.@Nullable PlayerGameModeChangeEvent setGameMode(final GameType mode, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause cause, net.kyori.adventure.text.@Nullable Component message) {
        boolean wasSpectator = this.isSpectator();
        org.bukkit.event.player.PlayerGameModeChangeEvent event = this.gameMode.changeGameModeForPlayer(mode, cause, message);
        if (event == null) return null;
        if (event.isCancelled()) return event; // need to return the event for the cancel message
        // Paper end - Expand PlayerGameModeChangeEvent
        this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, mode.getId()));
        if (mode == GameType.SPECTATOR) {
            this.removeEntitiesOnShoulder();
            this.stopRiding();
            this.stopUsingItem();
            EnchantmentHelper.stopLocationBasedEffects(this);
        } else {
            this.fission$self().setCamera(this);
            if (wasSpectator) {
                EnchantmentHelper.runLocationChangedEffects(this.fission$self().level(), this);
            }
        }

        this.onUpdateAbilities();
        this.updateEffectVisibility();
        return event; // Paper - Expand PlayerGameModeChangeEvent
    }

    // Paper start
    public void sendChatMessage(final OutgoingChatMessage message, final boolean filtered, final ChatType.Bound chatType, @Nullable Component unsigned) {
        // Paper end
        if (this.acceptsChatMessages()) {
            message.sendToPlayer(this.fission$self(), filtered, chatType, unsigned); // Paper
        }
    }
    // TODO(behaviour): vanilla sendChatMessage(3) should delegate here with unsigned = null

    // Paper start - don't call options events on login
    // TODO(behaviour): Paper's updateOptions fires PlayerClientOptionsChangeEvent/PlayerChangedMainHandEvent/PlayerLocaleChangeEvent,
    // then calls this. Here the (event-free) vanilla body is reused; once updateOptions fires events this must not route through it.
    public void updateOptionsNoEvents(final ClientInformation information) {
        // Paper end
        this.fission$self().updateOptions(information);
        this.adventure$locale = java.util.Objects.requireNonNullElse(net.kyori.adventure.translation.Translator.parseLocale(this.fission$self().language), java.util.Locale.US); // Paper
    }

    // Paper start - Add PlayerSetSpawnEvent
    // TODO(behaviour): vanilla setRespawnPosition(2) should delegate here with Cause.UNKNOWN
    public boolean setRespawnPosition(ServerPlayer.@Nullable RespawnConfig respawnConfig, final boolean showMessage, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause cause) {
        org.bukkit.Location spawnLoc = null;
        boolean actuallyDisplayInChat = false;
        if (respawnConfig != null) {
            actuallyDisplayInChat = showMessage && !respawnConfig.isSamePosition(this.respawnConfig);
            spawnLoc = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(respawnConfig.respawnData().pos(), this.server.getLevel(respawnConfig.respawnData().dimension()));
            spawnLoc.setYaw(respawnConfig.respawnData().yaw());
            spawnLoc.setPitch(respawnConfig.respawnData().pitch());
        }
        org.bukkit.event.player.PlayerSpawnChangeEvent dumbEvent = new org.bukkit.event.player.PlayerSpawnChangeEvent(
            this.getBukkitEntity(),
            spawnLoc,
            respawnConfig != null && respawnConfig.forced(),
            cause == com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN
                ? org.bukkit.event.player.PlayerSpawnChangeEvent.Cause.RESET
                : org.bukkit.event.player.PlayerSpawnChangeEvent.Cause.valueOf(cause.name())
        );
        dumbEvent.callEvent();

        com.destroystokyo.paper.event.player.PlayerSetSpawnEvent event = new com.destroystokyo.paper.event.player.PlayerSetSpawnEvent(
            this.getBukkitEntity(),
            cause,
            dumbEvent.getNewSpawn(),
            dumbEvent.isForced(),
            actuallyDisplayInChat,
            actuallyDisplayInChat ? io.papermc.paper.adventure.PaperAdventure.asAdventure(SPAWN_SET_MESSAGE) : null
        );
        event.setCancelled(dumbEvent.isCancelled());
        if (!event.callEvent()) {
            return false;
        }

        if (event.getLocation() != null) {
            respawnConfig = new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(
                    ((org.bukkit.craftbukkit.CraftWorld) event.getLocation().getWorld()).getHandle().dimension(),
                    org.bukkit.craftbukkit.util.CraftLocation.toBlockPos(event.getLocation()),
                    event.getLocation().getYaw(),
                    event.getLocation().getPitch()
                ),
                event.isForced()
            );
            if (event.willNotifyPlayer() && event.getNotification() != null) {
                this.sendSystemMessage(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.getNotification()));
            }
        }

        this.respawnConfig = respawnConfig;
        return true;
        // Paper end - Add PlayerSetSpawnEvent
    }

    @Override
    public net.minecraft.world.entity.item.@Nullable ItemEntity drop(final net.minecraft.world.item.ItemStack itemStack, final boolean thrownFromHand, final net.minecraft.util.Prediction prediction, final boolean callEvent, final java.util.function.@Nullable Consumer<org.bukkit.entity.Item> entityOperation) { // Paper - Extend dropItem API
        net.minecraft.world.entity.item.ItemEntity entity = super.drop(itemStack, thrownFromHand, prediction, callEvent, entityOperation); // Paper - Extend dropItem API
        net.minecraft.world.item.ItemStack droppedItemStack = entity != null ? entity.getItem() : net.minecraft.world.item.ItemStack.EMPTY; // Paper - move up
        if (thrownFromHand) {
            if (!droppedItemStack.isEmpty()) {
                this.awardStat(net.minecraft.stats.Stats.ITEM_DROPPED.get(droppedItemStack.getItem()), droppedItemStack.getCount()); // Paper - use size from dropped item
                this.awardStat(net.minecraft.stats.Stats.DROP);
            }
        }
        // Paper start - remove player from map on drop
        if (droppedItemStack.getItem() instanceof net.minecraft.world.item.MapItem) {
            final net.minecraft.world.level.saveddata.maps.MapItemSavedData mapData = net.minecraft.world.item.MapItem.getSavedData(droppedItemStack, this.level());
            if (mapData != null) {
                mapData.tickCarriedBy(this, droppedItemStack, null);
            }
        }
        // Paper end - remove player from map on drop
        return entity;
    }
    // TODO(behaviour): vanilla drop(ItemStack, boolean, Prediction) override's stat logic moves into the override above (LivingEntity's 3-arg becomes final)

    // FissionMC: Paper changes nextContainerCounter() void -> int; a vanilla return type cannot change, so ported call sites
    // that use the result call this instead.
    public int fission$nextContainerCounter() { // CraftBukkit - void -> int
        this.fission$self().nextContainerCounter();
        return this.containerCounter; // CraftBukkit
    }

    // FissionMC: Paper changes drop(boolean) void -> boolean ("add back success return"); a vanilla return type cannot change,
    // so ported call sites that use the result call this instead (copy of the vanilla body with Paper's result).
    public boolean fission$drop(final boolean all) { // Paper - add back success return
        net.minecraft.world.entity.player.Inventory inventory = this.getInventory();
        net.minecraft.world.item.ItemStack removed = inventory.removeFromSelected(all);
        this.containerMenu
            .findSlot(inventory, inventory.getSelectedSlot())
            .ifPresent(slot -> this.containerMenu.setRemoteSlot(slot, inventory.getSelectedItem()));
        if (this.useItem.isEmpty()) {
            this.stopUsingItem();
        }

        boolean result = this.drop(removed, true, net.minecraft.util.Prediction.PREDICTED) != null; // Paper - add back success return
        this.resetAttackStrengthTicker();
        return result; // Paper - return, keep order
    }

    @Override
    public void removeVehicle(final boolean suppressCancellation) { // Paper - Force entity dismount during teleportation
        Entity oldVehicle = this.getVehicle();
        super.removeVehicle(suppressCancellation); // Paper - Force entity dismount during teleportation
        if (oldVehicle instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
            for (net.minecraft.world.effect.MobEffectInstance effect : livingEntity.getActiveEffects()) {
                this.connection.send(new net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket(oldVehicle.getId(), effect.getEffect()));
            }
        }

        if (oldVehicle != null) {
            this.connection.send(new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(oldVehicle));
        }
    }

    // CraftBukkit start - Add per-player time and weather.
    private long timeOffset = 0;
    private boolean relativeTime = true;

    public long getPlayerTime() {
        if (this.relativeTime) {
            // Adds timeOffset to the current server time.
            return this.level().getDefaultClockTime() + this.timeOffset;
        } else {
            // Adds timeOffset to the beginning of this day.
            return this.level().getDefaultClockTime() - (this.level().getDefaultClockTime() % net.minecraft.SharedConstants.TICKS_PER_GAME_DAY) + this.timeOffset;
        }
    }

    private org.bukkit.@Nullable WeatherType weatherType = null;

    public void setPlayerWeather(org.bukkit.WeatherType type, boolean plugin) {
        if (!plugin && this.weatherType != null) {
            return;
        }

        if (plugin) {
            this.weatherType = type;
        }

        if (type == org.bukkit.WeatherType.DOWNFALL) {
            this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0));
        } else {
            this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
        }
    }

    private float pluginRainPosition;
    private float pluginRainPositionPrevious;

    // TODO(behaviour): ServerLevel calls updateWeather/tickWeather per player (server area)
    public void updateWeather(float oldRain, float newRain, float oldThunder, float newThunder) {
        if (this.weatherType == null) {
            // Vanilla
            if (oldRain != newRain) {
                this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, newRain));
            }
        } else {
            // Plugin
            if (this.pluginRainPositionPrevious != this.pluginRainPosition) {
                this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.pluginRainPosition));
            }
        }

        if (oldThunder != newThunder) {
            if (this.weatherType == org.bukkit.WeatherType.DOWNFALL || this.weatherType == null) {
                this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, newThunder));
            } else {
                this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0));
            }
        }
    }

    public void tickWeather() {
        if (this.weatherType == null) return;

        this.pluginRainPositionPrevious = this.pluginRainPosition;
        if (this.weatherType == org.bukkit.WeatherType.DOWNFALL) {
            this.pluginRainPosition += 0.01F;
        } else {
            this.pluginRainPosition -= 0.01F;
        }

        this.pluginRainPosition = Mth.clamp(this.pluginRainPosition, 0.0F, 1.0F);
    }

    public void resetPlayerWeather() {
        this.weatherType = null;
        this.setPlayerWeather(this.level().isRaining() ? org.bukkit.WeatherType.DOWNFALL : org.bukkit.WeatherType.CLEAR, false);
    }

    // FissionMC: Paper also overrides toString() here, but vanilla 26.3 Player#toString is final; not ported.

    @Override
    public boolean isImmobile() {
        return super.isImmobile() || (this.connection != null && this.connection.isDisconnected()); // Paper - Fix duplication bugs
    }

    @Override
    public org.bukkit.craftbukkit.entity.CraftPlayer getBukkitEntity() {
        return (org.bukkit.craftbukkit.entity.CraftPlayer) super.getBukkitEntity();
    }
    // CraftBukkit end
}
