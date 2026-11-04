package net.fissionmc.backend.mixin.server.level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.fissionmc.backend.world.ServerLevelInit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.level.timers.TimerQueue;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/level/ServerLevel.java.patch */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin extends Level {
    @Shadow @Final private List<ServerPlayer> players;
    @Shadow @Final private ServerChunkCache chunkSource;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private PersistentEntitySectionManager<Entity> entityManager;
    @Shadow public abstract ServerChunkCache getChunkSource();
    @Shadow public abstract MinecraftServer getServer();
    @Shadow public abstract GameRules getGameRules();
    @Shadow public abstract List<ServerPlayer> players();
    @Shadow private void addPlayer(final ServerPlayer player) {}
    @Shadow private Optional<BlockPos> findLightningRod(final BlockPos center) { throw new AssertionError(); }
    @Shadow private Explosion.BlockInteraction getDestroyType(final GameRule<Boolean> gameRule) { throw new AssertionError(); }
    @Shadow private boolean sendParticles(final ServerPlayer player, final boolean overrideLimiter, final double x, final double y, final double z, final net.minecraft.network.protocol.Packet<?> packet) { throw new AssertionError(); }

    private ServerLevelMixin(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess, Holder<DimensionType> dimensionTypeRegistration, boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    // CraftBukkit start
    private ResourceKey<LevelStem> typeKey;
    private String bukkitName;
    private UUID uuid;
    private net.minecraft.server.level.progress.LevelLoadListener levelLoadListener;
    private GameRules gameRules; // TODO(behaviour): getGameRules() returns this (per-level GameRules)
    private WeatherData weatherData; // TODO(behaviour): getWeatherData()/prepareWeather use this (per-level WeatherData)
    private TimerQueue<MinecraftServer> scheduledEvents; // TODO(behaviour): tickTime uses this (per-level scheduledEvents)
    private WorldGenSettings worldGenSettings;
    private boolean hasPhysicsEvent = true; // Paper - BlockPhysicsEvent
    private boolean hasEntityMoveEvent; // Paper - Add EntityMoveEvent
    private static final org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry();
    private final org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer persistentDataContainer = new org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer(DATA_TYPE_REGISTRY);

    public @Nullable LevelChunk getChunkIfLoaded(int x, int z) {
        return this.chunkSource.getChunkAtIfLoadedImmediately(x, z); // Paper - Use getChunkAtIfLoadedImmediately
    }

    public ResourceKey<LevelStem> getTypeKey() {
        return this.typeKey;
    }

    // FissionMC: replaces Paper's added ServerLevel constructor (Mixin cannot add constructors). The extra arguments reach
    // LevelMixin/ServerLevelMixin's constructor injections through ServerLevelInit.
    private static ServerLevel create(
        final MinecraftServer server,
        final Executor executor,
        final LevelStorageSource.LevelStorageAccess levelStorage,
        final WorldGenSettings worldGenSettings, // CraftBukkit
        final ResourceKey<Level> dimension,
        final LevelStem levelStem,
        final boolean isDebug,
        final long biomeZoomSeed,
        final List<CustomSpawner> customSpawners,
        final boolean tickTime,
        // Paper start - add parameters
        ResourceKey<LevelStem> typeKey,
        org.bukkit.World.Environment env,
        org.bukkit.generator.ChunkGenerator gen,
        org.bukkit.generator.BiomeProvider biomeProvider,
        SavedDataStorage savedDataStorage,
        io.papermc.paper.world.PaperWorldLoader.LoadedWorldData loadedWorldData
        // Paper end - add parameters
    ) {
        // CraftBukkit start
        final io.papermc.paper.world.saveddata.PaperLevelOverrides levelData = loadedWorldData.levelOverrides();
        savedDataStorage.set(io.papermc.paper.world.saveddata.PaperLevelOverrides.TYPE, levelData);
        savedDataStorage.set(io.papermc.paper.world.saveddata.PaperWorldMetadata.TYPE, new io.papermc.paper.world.saveddata.PaperWorldMetadata(loadedWorldData.uuid()));
        savedDataStorage.set(io.papermc.paper.world.saveddata.PaperWorldPDC.TYPE, loadedWorldData.pdc() == null ? io.papermc.paper.world.saveddata.PaperWorldPDC.TYPE.constructor().get() : loadedWorldData.pdc());
        final GameRules gameRules = new GameRules(server.getWorldData().enabledFeatures(), savedDataStorage.computeIfAbsent(net.minecraft.world.level.gamerules.GameRuleMap.TYPE));
        ServerLevelInit.push(new ServerLevelInit(worldGenSettings, typeKey, env, gen, biomeProvider, savedDataStorage, loadedWorldData, gameRules));
        try {
            // TODO(behaviour): Paper passes savedDataStorage into ServerChunkCache, uses spigotConfig view/simulation distance,
            //  wraps the chunk generator (CustomWorldChunkManager/CustomChunkGenerator) and passes typeKey to StructureCheck
            return new ServerLevel(server, executor, levelStorage, levelData, dimension, levelStem, isDebug, biomeZoomSeed, customSpawners, tickTime);
        } finally {
            ServerLevelInit.clear();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$init(CallbackInfo ci) {
        final ServerLevelInit init = ServerLevelInit.peek();
        if (init == null) {
            return; // TODO(behaviour): ServerLevels constructed without ServerLevel.create (vanilla world loading) get no Bukkit state
        }
        final io.papermc.paper.world.PaperWorldLoader.LoadedWorldData loadedWorldData = init.loadedWorldData();
        final SavedDataStorage savedDataStorage = init.savedDataStorage();
        this.gameRules = init.gameRules();
        this.weatherData = savedDataStorage.computeIfAbsent(WeatherData.TYPE);
        this.weatherData.setLevel((ServerLevel) (Object) this);
        this.typeKey = init.typeKey();
        this.bukkitName = loadedWorldData.bukkitName();
        this.uuid = loadedWorldData.uuid();
        this.levelLoadListener = new net.minecraft.server.level.progress.LoggingLevelLoadListener(false); // TODO(behaviour): Paper passes the level (LoggingLevelLoadListener patch)
        this.worldGenSettings = init.worldGenSettings();
        this.scheduledEvents = savedDataStorage.computeIfAbsent(TimerQueue.TYPE);
        // CraftBukkit end
        if (loadedWorldData.pdc() != null) {
            this.persistentDataContainer.putAll(loadedWorldData.pdc().persistentData().toTagCompound());
        }
        this.getCraftServer().addWorld(this.getWorld()); // CraftBukkit
    }

    // Paper start
    public final boolean areChunksLoadedForMove(AABB box) {
        // copied code from collision methods, so that we can guarantee that they won't load chunks (we don't override
        // CollisionGetter methods for VoxelShapes)
        // be more strict too, add a block (dumb plugins in move events?)
        int minBlockX = Mth.floor(box.minX - 1.0E-7) - 3;
        int maxBlockX = Mth.floor(box.maxX + 1.0E-7) + 3;

        int minBlockZ = Mth.floor(box.minZ - 1.0E-7) - 3;
        int maxBlockZ = Mth.floor(box.maxZ + 1.0E-7) + 3;

        int minChunkX = minBlockX >> 4;
        int maxChunkX = maxBlockX >> 4;

        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = maxBlockZ >> 4;

        ServerChunkCache chunkProvider = this.getChunkSource();

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                if (chunkProvider.getChunkAtIfLoadedImmediately(cx, cz) == null) {
                    return false;
                }
            }
        }

        return true;
    }

    public final void loadChunksForMoveAsync(AABB box, ca.spottedleaf.concurrentutil.util.Priority priority,
                                             java.util.function.Consumer<List<ChunkAccess>> onLoad) {
        if (Thread.currentThread() != this.server.getRunningThread()) { // FissionMC: Paper compares against Level#thread (the server thread)
            this.getChunkSource().mainThreadProcessor.execute(() -> {
                this.loadChunksForMoveAsync(box, priority, onLoad);
            });
            return;
        }
        int minBlockX = Mth.floor(box.minX - 1.0E-7) - 3;
        int minBlockZ = Mth.floor(box.minZ - 1.0E-7) - 3;

        int maxBlockX = Mth.floor(box.maxX + 1.0E-7) + 3;
        int maxBlockZ = Mth.floor(box.maxZ + 1.0E-7) + 3;

        int minChunkX = minBlockX >> 4;
        int minChunkZ = minBlockZ >> 4;

        int maxChunkX = maxBlockX >> 4;
        int maxChunkZ = maxBlockZ >> 4;

        this.loadChunks(minChunkX, minChunkZ, maxChunkX, maxChunkZ, priority, onLoad);
    }

    public final void loadChunks(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                 ca.spottedleaf.concurrentutil.util.Priority priority,
                                 java.util.function.Consumer<List<ChunkAccess>> onLoad) {
        List<ChunkAccess> ret = new java.util.ArrayList<>();
        it.unimi.dsi.fastutil.ints.IntArrayList ticketLevels = new it.unimi.dsi.fastutil.ints.IntArrayList();
        ServerChunkCache chunkProvider = this.getChunkSource();

        int requiredChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        int[] loadedChunks = new int[1];

        java.util.function.Consumer<ChunkAccess> consumer = (ChunkAccess chunk) -> {
            if (chunk != null) {
                int ticketLevel = Math.max(33, chunkProvider.chunkMap.getUpdatingChunkIfPresent(chunk.getPos().pack()).getTicketLevel());
                ret.add(chunk);
                ticketLevels.add(ticketLevel);
                chunkProvider.addTicketAtLevel(TicketType.FUTURE_AWAIT, chunk.getPos(), ticketLevel);
            }
            if (++loadedChunks[0] == requiredChunks) {
                try {
                    onLoad.accept(java.util.Collections.unmodifiableList(ret));
                } finally {
                    for (int i = 0, len = ret.size(); i < len; ++i) {
                        ChunkPos chunkPos = ret.get(i).getPos();
                        int ticketLevel = ticketLevels.getInt(i);

                        chunkProvider.addTicketAtLevel(TicketType.UNKNOWN, chunkPos, ticketLevel);
                        chunkProvider.removeTicketAtLevel(TicketType.FUTURE_AWAIT, chunkPos, ticketLevel);
                    }
                }
            }
        };

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                ca.spottedleaf.moonrise.common.PlatformHooks.get().scheduleChunkLoad(
                    (ServerLevel) (Object) this, cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true, priority, consumer
                );
            }
        }
    }
    // Paper end

    // Paper start - Add methods to find targets for lightning strikes
    // TODO(behaviour): vanilla findLightningTargetAround(BlockPos) -> this(pos, false); MC-262422 spectator fix applies only here
    public @Nullable BlockPos findLightningTargetAround(final BlockPos pos, boolean returnNullWhenNoTarget) {
        // Paper end - Add methods to find targets for lightning strikes
        BlockPos center = this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        Optional<BlockPos> lightningRodTarget = this.findLightningRod(center);
        if (lightningRodTarget.isPresent()) {
            return lightningRodTarget.get();
        }

        AABB search = AABB.encapsulatingFullBlocks(center, center.atY(this.getMaxY() + 1)).inflate(3.0);
        List<LivingEntity> entities = this.getEntitiesOfClass(LivingEntity.class, search, input -> input.isAlive() && this.canSeeSky(input.blockPosition()) && !input.isSpectator()); // Paper - Fix lightning being able to hit spectators (MC-262422)
        if (!entities.isEmpty()) {
            return entities.get(this.random.nextInt(entities.size())).blockPosition();
        }

        if (returnNullWhenNoTarget) return null; // Paper - Add methods to find targets for lightning strikes
        if (center.getY() == this.getMinY() - 1) {
            center = center.above(2);
        }

        return center;
    }

    // CraftBukkit start
    // TODO(behaviour): vanilla addFreshEntity/addWithUUID/addDuringTeleport/tryAddFreshEntityWithPassengers delegate with SpawnReason.DEFAULT (null for teleport)
    public boolean addFreshEntity(final Entity entity, final org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason reason) {
        return this.addEntity(entity, reason);
        // CraftBukkit end
    }

    public boolean addWithUUID(final Entity entity, final org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason reason) {
        return this.addEntity(entity, reason);
        // CraftBukkit end
    }

    public void addDuringTeleport(final Entity entity, final org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason reason) {
        // CraftBukkit end
        if (entity instanceof ServerPlayer player) {
            this.addPlayer(player);
        } else {
            this.addEntity(entity, reason); // CraftBukkit
        }
    }

    // CraftBukkit start
    private boolean addEntity(final Entity entity, final org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason spawnReason) {
        org.spigotmc.AsyncCatcher.catchOp("entity add"); // Spigot
        entity.generation = false; // Paper - Don't fire sync event during generation; Reset flag if it was added during a ServerLevel generation process
        // Paper start - extra debug info
        if (entity.valid) {
            MinecraftServer.LOGGER.error("Attempted Double World add on {}", entity, new Throwable());
            return true;
        }
        // Paper end - extra debug info
        if (entity.spawnReason == null) entity.spawnReason = spawnReason; // Paper - Entity#getEntitySpawnReason
        if (entity.isRemoved()) {
            // LOGGER.warn("Tried to add entity {} but it was marked as removed already", entity.typeHolder().getRegisteredName()); // CraftBukkit - remove warning
            return false;
        } else {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().isEmpty()) return false; // Paper - Prevent empty items from being added
            // Paper start - capture all item additions to the world
            if (this.captureDrops != null && entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
                this.captureDrops.add(item);
                return true;
            }
            // Paper end - capture all item additions to the world
            // SPIGOT-6415: Don't call spawn event when reason is null. For example when an entity teleports to a new world.
            if (spawnReason != null && !org.bukkit.craftbukkit.event.CraftEventFactory.doEntityAddEventCalling((ServerLevel) (Object) this, entity, spawnReason)) {
                return false;
            }
            // CraftBukkit end

            return this.entityManager.addNewEntity(entity);
        }
    }

    public boolean tryAddFreshEntityWithPassengers(final Entity entity, final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        // CraftBukkit end
        if (entity.getSelfAndPassengers().map(Entity::getUUID).anyMatch(this.entityManager::isLoaded)) {
            return false;
        }

        ((ServerLevel) (Object) this).addFreshEntityWithPassengers(entity, reason); // CraftBukkit
        return true;
    }

    // CraftBukkit start
    public boolean strikeLightning(Entity entitylightning) {
        return this.strikeLightning(entitylightning, org.bukkit.event.weather.LightningStrikeEvent.Cause.UNKNOWN);
    }

    public boolean strikeLightning(Entity entitylightning, org.bukkit.event.weather.LightningStrikeEvent.Cause cause) {
        org.bukkit.event.weather.LightningStrikeEvent lightning = org.bukkit.craftbukkit.event.CraftEventFactory.callLightningStrikeEvent((org.bukkit.entity.LightningStrike) entitylightning.getBukkitEntity(), cause);

        if (lightning.isCancelled()) {
            return false;
        }

        return this.addFreshEntity(entitylightning);
    }
    // CraftBukkit end

    // TODO(behaviour): vanilla explode(...) delegates to explode0
    public ServerExplosion explode0(
        final @Nullable Entity source,
        final @Nullable DamageSource damageSource,
        final @Nullable ExplosionDamageCalculator damageCalculator,
        final double x,
        final double y,
        final double z,
        final float r,
        final boolean fire,
        final Level.ExplosionInteraction interactionType,
        final ParticleOptions smallExplosionParticles,
        final ParticleOptions largeExplosionParticles,
        final WeightedList<ExplosionParticleInfo> blockParticles,
        final Holder<SoundEvent> explosionSound
    ) {
        return this.explode0(source, damageSource, damageCalculator, x, y, z, r, fire, interactionType, smallExplosionParticles, largeExplosionParticles, blockParticles, explosionSound, null);
    }

    public ServerExplosion explode0(
        final @Nullable Entity source,
        final @Nullable DamageSource damageSource,
        final @Nullable ExplosionDamageCalculator damageCalculator,
        final double x,
        final double y,
        final double z,
        final float r,
        final boolean fire,
        final Level.ExplosionInteraction interactionType,
        final ParticleOptions smallExplosionParticles,
        final ParticleOptions largeExplosionParticles,
        final WeightedList<ExplosionParticleInfo> blockParticles,
        final Holder<SoundEvent> explosionSound,
        java.util.function.@Nullable Consumer<ServerExplosion> configurator
    ) {
        // CraftBukkit end
        // FissionMC: if-chain instead of Paper's switch so the added STANDARD constant needs no synthetic switch map entry
        final Explosion.BlockInteraction blockInteraction;
        if (interactionType == Level.ExplosionInteraction.NONE) {
            blockInteraction = Explosion.BlockInteraction.KEEP;
        } else if (interactionType == Level.ExplosionInteraction.BLOCK) {
            blockInteraction = this.getDestroyType(GameRules.BLOCK_EXPLOSION_DROP_DECAY);
        } else if (interactionType == Level.ExplosionInteraction.MOB) {
            blockInteraction = this.getGameRules().get(GameRules.MOB_GRIEFING) ? this.getDestroyType(GameRules.MOB_EXPLOSION_DROP_DECAY) : Explosion.BlockInteraction.KEEP;
        } else if (interactionType == Level.ExplosionInteraction.TNT) {
            blockInteraction = this.getDestroyType(GameRules.TNT_EXPLOSION_DROP_DECAY);
        } else if (interactionType == Level.ExplosionInteraction.TRIGGER) {
            blockInteraction = Explosion.BlockInteraction.TRIGGER_BLOCK;
        } else if (interactionType == Level.ExplosionInteraction.STANDARD) {
            blockInteraction = Explosion.BlockInteraction.DESTROY; // CraftBukkit - handle custom explosion type
        } else {
            throw new IncompatibleClassChangeError();
        }
        Vec3 center = new Vec3(x, y, z);
        ServerExplosion explosion = new ServerExplosion((ServerLevel) (Object) this, source, damageSource, damageCalculator, center, r, fire, blockInteraction);
        if (configurator != null) configurator.accept(explosion); // Paper - Allow explosions to damage source
        int blockCount = explosion.explode();
        // CraftBukkit start
        if (explosion.wasCanceled) {
            return explosion;
        }
        // CraftBukkit end
        ParticleOptions explosionParticle = explosion.isSmall() ? smallExplosionParticles : largeExplosionParticles;

        for (ServerPlayer player : this.players) {
            if (player.distanceToSqr(center) < 4096.0) {
                Optional<Vec3> playerKnockback = Optional.ofNullable(explosion.getHitPlayers().get(player));
                player.connection
                    .send(
                        new ClientboundExplodePacket(
                            center, r, blockCount, playerKnockback, explosionParticle, explosionSound, blockParticles, source == null || !source.isSilent()
                        )
                    );
            }
        }

        return explosion; // CraftBukkit
    }

    // Paper start - visibility API
    // TODO(behaviour): vanilla sendParticles overloads delegate to sendParticlesSource(null, ...)
    public <T extends ParticleOptions> int sendParticlesSource(
        final @Nullable Entity sender,
        final T particle,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double speed
    ) {
        return this.sendParticlesSource(sender, particle, false, false, x, y, z, count, xDist, yDist, zDist, speed, ClientboundLevelParticlesPacket.RandomizationType.DEFAULT);
    }

    public <T extends ParticleOptions> int sendParticlesSource(
        final @Nullable Entity sender,
        final T particle,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double xSpeed,
        final double ySpeed,
        final double zSpeed
    ) {
        return this.sendParticlesSource(
            sender, particle, false, false, x, y, z, count, xDist, yDist, zDist, xSpeed, ySpeed, zSpeed, ClientboundLevelParticlesPacket.RandomizationType.DEFAULT
        );
    }

    public <T extends ParticleOptions> int sendParticlesSource(
        final @Nullable Entity sender,
        final T particle,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double xSpeed,
        final double ySpeed,
        final double zSpeed,
        final ClientboundLevelParticlesPacket.RandomizationType randomizationType
    ) {
        return this.sendParticlesSource(sender, particle, false, false, x, y, z, count, xDist, yDist, zDist, xSpeed, ySpeed, zSpeed, randomizationType);
    }

    public <T extends ParticleOptions> int sendParticlesSource(
        final @Nullable Entity sender,
        final T particle,
        final boolean overrideLimiter,
        final boolean alwaysShow,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double speed
    ) {
        return this.sendParticlesSource(
            sender, particle, overrideLimiter, alwaysShow, x, y, z, count, xDist, yDist, zDist, speed, speed, speed, ClientboundLevelParticlesPacket.RandomizationType.DEFAULT
        );
    }

    public <T extends ParticleOptions> int sendParticlesSource(
        final @Nullable Entity sender,
        final T particle,
        final boolean overrideLimiter,
        final boolean alwaysShow,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double speed,
        final ClientboundLevelParticlesPacket.RandomizationType randomizationType
    ) {
        return this.sendParticlesSource(sender, particle, overrideLimiter, alwaysShow, x, y, z, count, xDist, yDist, zDist, speed, speed, speed, randomizationType);
    }

    public <T extends ParticleOptions> int sendParticlesSource(
        final @Nullable Entity sender,
        final T particle,
        final boolean overrideLimiter,
        final boolean alwaysShow,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double xSpeed,
        final double ySpeed,
        final double zSpeed,
        final ClientboundLevelParticlesPacket.RandomizationType randomizationType
    ) {
        return this.sendParticlesSource(this.players, sender, particle, overrideLimiter, alwaysShow, x, y, z, count, xDist, yDist, zDist, xSpeed, ySpeed, zSpeed, randomizationType);
    }

    public <T extends ParticleOptions> int sendParticlesSource(
        final List<ServerPlayer> receivers,
        final @Nullable Entity sender,
        final T particle,
        final boolean overrideLimiter,
        final boolean alwaysShow,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double speed,
        final ClientboundLevelParticlesPacket.RandomizationType randomizationType
    ) {
        return this.sendParticlesSource(receivers, sender, particle, overrideLimiter, alwaysShow, x, y, z, count, xDist, yDist, zDist, speed, speed, speed, randomizationType);
    }

    public <T extends ParticleOptions> int sendParticlesSource(
        final List<ServerPlayer> receivers,
        final @Nullable Entity sender,
        final T particle,
        final boolean overrideLimiter,
        final boolean alwaysShow,
        final double x,
        final double y,
        final double z,
        final int count,
        final double xDist,
        final double yDist,
        final double zDist,
        final double xSpeed,
        final double ySpeed,
        final double zSpeed,
        final ClientboundLevelParticlesPacket.RandomizationType randomizationType
    ) {
        // Paper end - visibility API
        ClientboundLevelParticlesPacket packet = new ClientboundLevelParticlesPacket(
            particle,
            overrideLimiter,
            alwaysShow,
            x,
            y,
            z,
            (float)xDist,
            (float)yDist,
            (float)zDist,
            (float)xSpeed,
            (float)ySpeed,
            (float)zSpeed,
            count,
            randomizationType
        );
        int result = 0;

        for (int i = 0; i < receivers.size(); i++) { // Paper - particle API
            ServerPlayer player = receivers.get(i); // Paper - particle API
            if (sender != null && !player.getBukkitEntity().canSee(sender.getBukkitEntity())) continue; // CraftBukkit
            if (this.sendParticles(player, overrideLimiter, x, y, z, packet)) {
                result++;
            }
        }

        return result;
    }

    // Paper start - respect global sound events gamerule
    public List<ServerPlayer> getPlayersForGlobalSoundGamerule() {
        return this.getGameRules().get(GameRules.GLOBAL_SOUND_EVENTS) ? this.getServer().getPlayerList().getPlayers() : this.players();
    }

    public double getGlobalSoundRangeSquared(java.util.function.Function<org.spigotmc.SpigotWorldConfig, Integer> rangeFunction) {
        final double range = rangeFunction.apply(this.spigotConfig);
        return range <= 0 ? 64.0 * 64.0 : range * range; // 64 is taken from default in ServerLevel#levelEvent
    }
    // Paper end - respect global sound events gamerule

    // Paper start - notify observers even if grow failed
    @Deprecated
    public void checkCapturedTreeStateForObserverNotify(final BlockPos pos, final org.bukkit.craftbukkit.block.CraftBlockState craftBlockState) {
        // notify observers if the block state is the same and the Y level equals the original y level (for mega trees)
        // blocks at the same Y level with the same state can be assumed to be saplings which trigger observers regardless of if the
        // tree grew or not
        if (craftBlockState.getPosition().getY() == pos.getY() && this.getBlockState(craftBlockState.getPosition()) == craftBlockState.getHandle()) {
            this.notifyAndUpdatePhysics(craftBlockState.getPosition(), null, craftBlockState.getHandle(), craftBlockState.getHandle(), craftBlockState.getHandle(), craftBlockState.getFlags(), 512);
        }
    }
    // Paper end - notify observers even if grow failed
}
