package net.fissionmc.backend.mixin.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fissionmc.backend.Hooks;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.Services;
import net.minecraft.server.TickTask;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plugin lifecycle: STARTUP plugins before worlds load, POSTWORLD after.
 * Paper: sources/net/minecraft/server/MinecraftServer.java.patch (members; see surface/members/server.txt).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin extends ReentrantBlockableEventLoop<TickTask> {
    @Shadow @Final @Mutable private Map<ResourceKey<Level>, ServerLevel> levels;
    @Shadow @Final @Mutable protected WorldData worldData;
    @Shadow @Final @Mutable protected LevelStorageSource.LevelStorageAccess storageSource;
    @Shadow @Final private Executor executor;
    @Shadow @Final private Services services;
    @Shadow private int emptyTicks;
    @Shadow private @Nullable String motd;
    @Shadow private void updateEffectiveRespawnData() {} // widened to public by the surface

    @Shadow public abstract ServerTickRateManager tickRateManager();
    @Shadow public abstract PlayerList getPlayerList();
    @Shadow public abstract int getAbsoluteMaxWorldSize();
    @Shadow public abstract com.mojang.datafixers.DataFixer getFixerUpper();
    @Shadow public abstract net.minecraft.core.RegistryAccess.Frozen registryAccess();
    @Shadow public abstract CompletableFuture<Void> reloadResources(Collection<String> packsToEnable);
    @Shadow public abstract ServerLevel findRespawnDimension();
    @Shadow public abstract void halt(boolean wait);
    @Shadow protected abstract int pauseWhenEmptySeconds();
    @Shadow protected abstract GlobalPos selectLevelLoadFocusPos();
    @Shadow protected abstract void sendDifficultyUpdate(ServerPlayer player);
    @Shadow private static void setInitialSpawn(final ServerLevel level, final ServerLevelData levelData, final boolean spawnBonusChest, final boolean isDebug, final LevelLoadListener levelLoadListener) {}
    @Shadow public abstract net.minecraft.world.clock.ServerClockManager clockManager();

    private MinecraftServerMixin(String name, boolean propagatesCrashes) {
        super(name, propagatesCrashes);
    }

    // ---- FissionMC lifecycle hooks (pre-existing) ----

    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void fission$beforeLevel(CallbackInfo ci) {
        if ((Object) this instanceof DedicatedServer server) {
            Hooks.beforeLevel(server);
        }
    }

    @Inject(method = "loadLevel", at = @At("RETURN"))
    private void fission$afterLevel(CallbackInfo ci) {
        if ((Object) this instanceof DedicatedServer) {
            Hooks.afterLevel();
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void fission$beforeStop(CallbackInfo ci) {
        if ((Object) this instanceof DedicatedServer) {
            Hooks.beforeStop();
        }
    }

    // ---- Paper members ----

    private static MinecraftServer SERVER; // Paper
    private static final net.kyori.adventure.text.logger.slf4j.ComponentLogger COMPONENT_LOGGER = net.kyori.adventure.text.logger.slf4j.ComponentLogger.logger(MinecraftServer.LOGGER.getName()); // Paper
    private volatile boolean hasStarted = false; // Paper
    private volatile boolean isRestarting = false; // Paper - flag to signify we're attempting to restart

    // CraftBukkit start
    private net.minecraft.server.WorldLoader.DataLoadContext worldLoaderContext; // TODO(behaviour): Paper passes this through the constructor
    private org.bukkit.craftbukkit.CraftServer server;
    private joptsimple.OptionSet options; // TODO(behaviour): Paper passes this through the constructor
    private org.bukkit.command.ConsoleCommandSender console;
    private static int currentTick; // Paper - improve tick loop
    private java.util.Queue<Runnable> processQueue = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
    private int autosavePeriod;
    // Paper - don't store the vanilla dispatcher
    private boolean forceTicks;
    // CraftBukkit end
    // Spigot start
    private static final int TPS = 20;
    private static final int TICK_TIME = 1000000000 / TPS;
    // Spigot end
    private volatile boolean hasFullyShutdown; // Paper - Improved watchdog support
    private volatile boolean abnormalExit; // Paper - Improved watchdog support
    private volatile Thread shutdownThread; // Paper - Improved watchdog support
    private io.papermc.paper.configuration.PaperConfigurations paperConfigurations; // Paper - add paper configuration files
    private boolean isIteratingOverLevels = false; // Paper - Throw exception on world create while being ticked
    private final Set<String> pluginsBlockingSleep = new java.util.HashSet<>(); // Paper - API to allow/disallow tick sleeping
    private static final long SERVER_INIT = System.nanoTime(); // Paper - Lag compensation
    // Paper start - improve tick loop
    private final ca.spottedleaf.common.time.TickData tickTimes1s  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(1L));
    private final ca.spottedleaf.common.time.TickData tickTimes5s  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(5L));
    private final ca.spottedleaf.common.time.TickData tickTimes10s = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(10L));
    private final ca.spottedleaf.common.time.TickData tickTimes15s = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.SECONDS.toNanos(15L));
    private final ca.spottedleaf.common.time.TickData tickTimes1m  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.MINUTES.toNanos(1L));
    private final ca.spottedleaf.common.time.TickData tickTimes5m  = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.MINUTES.toNanos(5L));
    private final ca.spottedleaf.common.time.TickData tickTimes15m = new ca.spottedleaf.common.time.TickData(java.util.concurrent.TimeUnit.MINUTES.toNanos(15L));
    // TODO(behaviour): Paper's improved tick loop (tickSchedule, recordEndOfTick -> addTickTime, runAllTasksAtTickStart, ...) feeds these
    private final Object statsLock = new Object();
    private double @Nullable [] tps;
    private ca.spottedleaf.common.time.TickData.@Nullable MSPTData msptData5s;

    private void addTickTime(final ca.spottedleaf.common.time.TickTime time) {
        synchronized (this.statsLock) {
            this.tickTimes1s.addDataFrom(time);
            this.tickTimes5s.addDataFrom(time);
            this.tickTimes10s.addDataFrom(time);
            this.tickTimes15s.addDataFrom(time);
            this.tickTimes1m.addDataFrom(time);
            this.tickTimes5m.addDataFrom(time);
            this.tickTimes15m.addDataFrom(time);
            this.clearTickTimeStatistics();
        }
    }

    private void clearTickTimeStatistics() {
        this.msptData5s = null;
        this.tps = null;
    }

    private static double getTPS(final ca.spottedleaf.common.time.TickData tickData, final long tickInterval) {
        final Double avg = tickData.getTPSAverage(null, tickInterval);
        if (avg == null) {
            return 1.0E9 / (double)tickInterval;
        }

        return avg;
    }

    public double[] getTPS() {
        synchronized (this.statsLock) {
            double @Nullable [] tps = this.tps;
            if (tps == null) {
                tps = this.computeTPS();
                this.tps = tps;
            }
            return tps.clone();
        }
    }

    public ca.spottedleaf.common.time.TickData.@Nullable MSPTData getMSPTData5s() {
        synchronized (this.statsLock) {
            if (this.msptData5s == null) {
                this.msptData5s = this.tickTimes5s.getMSPTData(null, this.tickRateManager().nanosecondsPerTick());
            }
            return this.msptData5s;
        }
    }

    public double[] computeTPS() {
        final long interval = this.tickRateManager().nanosecondsPerTick();
        return new double[] {
            getTPS(this.tickTimes1m, interval),
            getTPS(this.tickTimes5m, interval),
            getTPS(this.tickTimes15m, interval)
        };
    }
    // Paper end - improve tick loop

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$init(CallbackInfo ci) {
        SERVER = (MinecraftServer) (Object) this; // Paper - better singleton
        // TODO(behaviour): constructor hunks - options/worldLoaderContext params, ServerShutdownThread hook, LogManagerShutdownThread.unhook(), per-level gamerules/weather/scheduledEvents
        this.paperConfigurations = this.services.paper().configurations(); // Paper - add paper configuration files
    }

    // Paper start - rework world loading process
    public void createLevel(
        LevelStem levelStem,
        io.papermc.paper.world.PaperWorldLoader.WorldLoadingInfoAndData loading,
        net.minecraft.world.level.storage.LevelDataAndDimensions.WorldDataAndGenSettings worldDataAndGenSettings
    ) {
        final WorldOptions worldOptions = worldDataAndGenSettings.genSettings().options();
        final ResourceKey<Level> dimensionKey = loading.info().dimensionKey();
        final SavedDataStorage savedDataStorage = new SavedDataStorage(this.storageSource.getDimensionPath(dimensionKey).resolve(LevelResource.DATA.id()), this.getFixerUpper(), this.registryAccess());
        savedDataStorage.set(WorldGenSettings.TYPE, new WorldGenSettings(worldDataAndGenSettings.genSettings().options(), worldDataAndGenSettings.genSettings().dimensions()));
        long seed = worldOptions.seed();
        long biomeZoomSeed = BiomeManager.obfuscateSeed(seed);
        List<CustomSpawner> overworldCustomSpawners = ImmutableList.of(
            new net.minecraft.world.level.levelgen.PhantomSpawner(), new net.minecraft.world.level.levelgen.PatrolSpawner(), new net.minecraft.world.entity.npc.CatSpawner(), new net.minecraft.world.entity.ai.village.VillageSiege(), new net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner(savedDataStorage) // Paper - save to world data
        );
        final org.bukkit.generator.ChunkGenerator chunkGenerator = this.server.getGenerator(loading.data().bukkitName());
        org.bukkit.generator.BiomeProvider biomeProvider = this.server.getBiomeProvider(loading.data().bukkitName());
        final org.bukkit.generator.WorldInfo worldInfo = new org.bukkit.craftbukkit.generator.CraftWorldInfo(
            loading.data().bukkitName(),
            org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(loading.info().dimensionKey().identifier()),
            worldDataAndGenSettings.genSettings().options().seed(),
            worldDataAndGenSettings.data().enabledFeatures(),
            loading.info().environment(),
            levelStem.type().value(),
            levelStem.generator(),
            this.registryAccess(),
            loading.data().uuid()
        );
        if (biomeProvider == null && chunkGenerator != null) {
            biomeProvider = chunkGenerator.getDefaultBiomeProvider(worldInfo);
        }
        ServerLevel serverLevel;
        if (loading.info().stemKey() == LevelStem.OVERWORLD) {
            serverLevel = ServerLevel.create( // FissionMC: Paper-added constructor -> static factory
                (MinecraftServer) (Object) this,
                this.executor,
                this.storageSource,
                worldDataAndGenSettings.genSettings(),
                dimensionKey,
                levelStem,
                worldDataAndGenSettings.data().isDebugWorld(),
                biomeZoomSeed,
                overworldCustomSpawners,
                true,
                loading.info().stemKey(),
                loading.info().environment(),
                chunkGenerator,
                biomeProvider,
                savedDataStorage,
                loading.data()
            );
            this.worldData = worldDataAndGenSettings.data();
            this.worldData.setGameType(((DedicatedServer) (Object) this).getProperties().gameMode.get()); // From DedicatedServer.init
            // TODO(behaviour): Paper also loads the scoreboard, command storage and stopwatches here (moved from createLevels)
            this.server.scoreboardManager = new org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager((MinecraftServer) (Object) this, serverLevel.getScoreboard());
        } else {
            final List<CustomSpawner> spawners;
            if (io.papermc.paper.configuration.GlobalConfiguration.get().misc.useDimensionTypeForCustomSpawners && levelStem.type().is(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD)) {
                spawners = overworldCustomSpawners;
            } else {
                spawners = Collections.emptyList();
            }
            serverLevel = ServerLevel.create( // FissionMC: Paper-added constructor -> static factory
                (MinecraftServer) (Object) this,
                this.executor,
                this.storageSource,
                worldDataAndGenSettings.genSettings(),
                dimensionKey,
                levelStem,
                this.worldData.isDebugWorld(),
                biomeZoomSeed,
                spawners,
                true,
                loading.info().stemKey(),
                loading.info().environment(),
                chunkGenerator,
                biomeProvider,
                savedDataStorage,
                loading.data()
            );
        }
        this.addLevel(serverLevel);
        this.initWorld(serverLevel, null);
    }

    public void initWorld(ServerLevel overworld, org.bukkit.@Nullable WorldCreator worldCreator) {
        final ServerLevelData levelData = overworld.serverLevelData;
        final WorldOptions worldOptions = overworld.worldGenSettings.options();
        final boolean isDebug = this.worldData.isDebugWorld();
        if (overworld.generator != null) {
            overworld.getWorld().getPopulators().addAll(overworld.generator.getDefaultPopulators(overworld.getWorld()));
        }
        overworld.getWorldBorder().world = overworld;
        overworld.getWorldBorder().setAbsoluteMaxSize(this.getAbsoluteMaxWorldSize());
        this.getPlayerList().addWorldborderListener(overworld);
        this.server.getPluginManager().callEvent(new org.bukkit.event.world.WorldInitEvent(overworld.getWorld()));
        // Paper end - rework world loading process
        if (!levelData.isInitialized()) {
            try {
                // Paper start - Allow direct setting spawn location
                if (worldCreator != null && worldCreator.forcedSpawnPosition() != null) {
                    levelData.setSpawn(LevelData.RespawnData.of(overworld.dimension(),
                        io.papermc.paper.util.MCUtil.toBlockPos(worldCreator.forcedSpawnPosition()),
                        Objects.requireNonNullElse(worldCreator.forcedSpawnYaw(), LevelData.RespawnData.DEFAULT.yaw()),
                        Objects.requireNonNullElse(worldCreator.forcedSpawnPitch(), LevelData.RespawnData.DEFAULT.pitch())
                    ));
                } else {
                    setInitialSpawn(overworld, levelData, worldOptions.generateBonusChest(), isDebug, overworld.levelLoadListener); // Paper - per world level load listener
                }
                // Paper end - Allow direct setting spawn location
                levelData.setInitialized(true);
                if (isDebug) {
                    this.setupDebugLevel(this.worldData, overworld); // Paper - per-level GameRules
                }
            } catch (Throwable t) {
                net.minecraft.CrashReport report = net.minecraft.CrashReport.forThrowable(t, "Exception initializing level");

                try {
                    overworld.fillReportDetails(report);
                } catch (Throwable ignored) {
                }

                throw new net.minecraft.ReportedException(report);
            }

            levelData.setInitialized(true);
        }

        GlobalPos focusPos = this.selectLevelLoadFocusPos();
        overworld.levelLoadListener.updateFocus(focusPos.dimension(), ChunkPos.containing(focusPos.pos())); // Paper - per world load listener
    }

    private void setupDebugLevel(final WorldData worldData, final ServerLevel level) { // Paper - pass level
        worldData.setDifficulty(Difficulty.PEACEFUL);
        worldData.setDifficultyLocked(true);
        ServerLevelData levelData = worldData.overworldData();
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, level); // Paper - per-level GameRules
        this.clockManager().moveToTimeMarker(this.registryAccess().getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD), net.minecraft.world.clock.ClockTimeMarkers.NOON);
        levelData.setGameType(GameType.SPECTATOR);
    }

    // CraftBukkit start
    public void prepareLevel(ServerLevel level) {
        this.forceTicks = true;
        // CraftBukkit end
        net.minecraft.server.level.ChunkLoadCounter chunkLoadCounter = new net.minecraft.server.level.ChunkLoadCounter();

        if (true) { // CraftBukkit
            chunkLoadCounter.track(level, () -> {
                TicketStorage savedTickets = level.getDataStorage().get(TicketStorage.TYPE);
                if (savedTickets != null) {
                    savedTickets.activateAllDeactivatedTickets();
                }
            });
        }

        level.levelLoadListener.start(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, chunkLoadCounter.totalChunks()); // Paper - per world load listener

        do {
            level.levelLoadListener.update(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, chunkLoadCounter.readyChunks(), chunkLoadCounter.totalChunks()); // Paper - per world load listener
            this.executeModerately(); // CraftBukkit
        } while (chunkLoadCounter.pendingChunks() > 0);

        level.levelLoadListener.finish(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS); // Paper - per world load listener
        level.setSpawnSettings(level.isSpawningMonsters()); // Paper - per level difficulty (from setDifficulty(ServerLevel, Difficulty, boolean))
        this.updateEffectiveRespawnData();
        this.forceTicks = false; // CraftBukkit
        level.entityManager.tick(); // SPIGOT-6526: Load pending entities so they are available to the API
        new org.bukkit.event.world.WorldLoadEvent(level.getWorld()).callEvent(); // Paper - call WorldLoadEvent
    }

    private void executeModerately() {
        this.runAllTasks();
        java.util.concurrent.locks.LockSupport.parkNanos("executing tasks", 1000L);
        // CraftBukkit end
    }

    // CraftBukkit start
    private boolean hasStopped = false;
    private boolean hasLoggedStop = false; // Paper - Debugging
    private final Object stopLock = new Object();
    public final boolean hasStopped() {
        synchronized (this.stopLock) {
            return this.hasStopped;
        }
    }
    // CraftBukkit end
    // TODO(behaviour): stopServer hunks (set hasStopped under stopLock, shutdownThread, WatchdogThread.doStop, disable plugins, removeAll(isRestarting), hasFullyShutdown ...)

    public void halt(final boolean wait, final boolean isRestarting) {
        this.isRestarting = isRestarting;
        this.hasLoggedStop = true; // Paper - Debugging
        if (this.isDebugging()) io.papermc.paper.util.TraceUtil.dumpTraceForThread("Server stopped"); // Paper - Debugging
        // Paper end
        this.halt(wait); // FissionMC: Paper inlines the remaining vanilla halt(boolean) body here
    }

    // TODO(behaviour): wrapRunnable hunk (run directly on the watchdog shutdown thread; tickTaskTickCount)

    // Paper - per world respawn data: updateEffectiveRespawnData() is only widened to public (surface)
    // TODO(behaviour): its body uses respawnLevel.serverLevelData's respawn data and syncs it back to the overworld level data

    // CraftBukkit start
    public void addLevel(ServerLevel level) {
        Map<ResourceKey<Level>, ServerLevel> oldLevels = this.levels;
        Map<ResourceKey<Level>, ServerLevel> newLevels = Maps.newLinkedHashMap(oldLevels);
        newLevels.put(level.dimension(), level);
        this.levels = Collections.unmodifiableMap(newLevels);
    }

    public void removeLevel(ServerLevel level) {
        Map<ResourceKey<Level>, ServerLevel> oldLevels = this.levels;
        Map<ResourceKey<Level>, ServerLevel> newLevels = Maps.newLinkedHashMap(oldLevels);
        newLevels.remove(level.dimension());
        this.levels = Collections.unmodifiableMap(newLevels);
    }
    // CraftBukkit end

    // Paper start - per level difficulty, WorldDifficultyChangeEvent
    public void setDifficulty(final ServerLevel level, final Difficulty difficulty, final net.minecraft.commands.@Nullable CommandSourceStack source, final boolean ignoreLock) {
        io.papermc.paper.world.saveddata.PaperLevelOverrides worldData = (io.papermc.paper.world.saveddata.PaperLevelOverrides) level.serverLevelData; // FissionMC: the field keeps its vanilla type
        if (ignoreLock || !worldData.isDifficultyLocked()) {
            new io.papermc.paper.event.world.WorldDifficultyChangeEvent(
                level.getWorld(), source, org.bukkit.craftbukkit.util.CraftDifficulty.toBukkit(difficulty)
            ).callEvent();
            worldData.setDifficulty(worldData.isHardcore() ? Difficulty.HARD : difficulty);
            level.setSpawnSettings(level.isSpawningMonsters());
            level.players().forEach(this::sendDifficultyUpdate);
            // Paper end - per level difficulty
        }
    }

    // Paper start - Adventure
    // FissionMC: Paper retypes the motd field to an adventure Component; the vanilla String field is kept and converted
    // TODO(behaviour): getMotd/setMotd hunks
    public net.kyori.adventure.text.Component motd() {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserializeOr(this.motd, net.kyori.adventure.text.Component.empty());
    }

    public void motd(final net.kyori.adventure.text.Component motd) {
        // Paper end - Adventure
        this.motd = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(motd);
    }

    private io.papermc.paper.event.server.ServerResourcesReloadedEvent.@Nullable Cause paper$reloadCause;

    public CompletableFuture<Void> reloadResources(
        final Collection<String> packsToEnable, final io.papermc.paper.event.server.ServerResourcesReloadedEvent.Cause cause
    ) {
        // TODO(behaviour): reloadResources hunks (moveBukkitCommands, commands lifecycle event, help map, fire ServerResourcesReloadedEvent(paper$reloadCause))
        this.paper$reloadCause = cause;
        return this.reloadResources(packsToEnable);
    }

    // CraftBukkit start
    public boolean isDebugging() {
        return false;
    }

    private static MinecraftServer getServer() {
        return SERVER;
    }
    // CraftBukkit end

    private final java.util.concurrent.ExecutorService chatExecutor = java.util.concurrent.Executors.newCachedThreadPool(
        new com.google.common.util.concurrent.ThreadFactoryBuilder().setDaemon(true).setNameFormat("Async Chat Thread - #%d").setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(MinecraftServer.LOGGER)).build()); // Paper

    // Paper start - API to check if the server is sleeping
    public boolean isTickPaused() {
        return this.emptyTicks > 0 && this.emptyTicks >= this.pauseWhenEmptySeconds() * 20;
    }

    public void addPluginAllowingSleep(final String pluginName, final boolean value) {
        if (!value) {
            this.pluginsBlockingSleep.add(pluginName);
        } else {
            this.pluginsBlockingSleep.remove(pluginName);
        }
    }

    private void removeDisabledPluginsBlockingSleep() {
        if (this.pluginsBlockingSleep.isEmpty()) {
            return;
        }
        this.pluginsBlockingSleep.removeIf(plugin -> (
            !io.papermc.paper.plugin.manager.PaperPluginManagerImpl.getInstance().isPluginEnabled(plugin)
        ));
    }
    // Paper end - API to check if the server is sleeping
    // TODO(behaviour): tickServer hunks (removeDisabledPluginsBlockingSleep, pluginsBlockingSleep check, processQueue, isIteratingOverLevels, currentTick, WatchdogThread.tick ...)
}
