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
    private GameRules gameRules; // Paper - per-level GameRules
    private WeatherData weatherData; // Paper - per-level WeatherData
    private TimerQueue<MinecraftServer> scheduledEvents; // Paper - per-level scheduledEvents
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

    // ---- wave 2: behaviour hunks (Paper: sources/net/minecraft/server/level/ServerLevel.java.patch) ----

    // CraftBukkit start - vanilla add methods delegate with a spawn reason
    @org.spongepowered.asm.mixin.injection.Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void paper$addFreshEntityDefault(final Entity entity, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.addFreshEntity(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT));
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "addWithUUID(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void paper$addWithUUIDDefault(final Entity entity, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.addWithUUID(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT));
    }

    // SPIGOT-6415: Don't call spawn event for entities which travel trough worlds,
    // since it is only an implementation detail, that a new entity is created when
    // they are traveling between worlds.
    @org.spongepowered.asm.mixin.injection.Inject(method = "addDuringTeleport(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void paper$addDuringTeleportNoReason(final Entity entity, final CallbackInfo ci) {
        this.addDuringTeleport(entity, null);
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void paper$tryAddFreshEntityWithPassengersDefault(final Entity entity, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.tryAddFreshEntityWithPassengers(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT));
    }
    // CraftBukkit end

    @org.spongepowered.asm.mixin.injection.Redirect(method = "removePlayerImmediately", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void paper$removePlayerCause(final ServerPlayer player, final Entity.RemovalReason reason) {
        player.remove(reason, null); // CraftBukkit - add Bukkit remove cause
    }

    // Spigot start - close inventories of unloading containers
    @org.spongepowered.asm.mixin.injection.Inject(method = "unload", at = @At("HEAD"))
    private void paper$unloadCloseInventories(final LevelChunk levelChunk, final CallbackInfo ci) {
        for (net.minecraft.world.level.block.entity.BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
            if (blockEntity instanceof net.minecraft.world.Container) {
                // Paper start - this area looks like it can load chunks, change the behavior
                // chests for example can apply physics to the world
                // so instead we just change the active container and call the event
                for (org.bukkit.entity.HumanEntity human : com.google.common.collect.Lists.newArrayList(((net.minecraft.world.Container) blockEntity).getViewers())) {
                    ((org.bukkit.craftbukkit.entity.CraftHumanEntity) human).getHandle().closeUnloadedInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED); // Paper - Inventory close reason
                }
                // Paper end - this area looks like it can load chunks, change the behavior
            }
        }
    }
    // Spigot end

    // Paper start - per-level GameRules / WeatherData / scheduledEvents / WorldGenSettings
    // FissionMC: during construction (before paper$init) the values come from ServerLevelInit
    @org.spongepowered.asm.mixin.injection.Inject(method = "getGameRules", at = @At("HEAD"), cancellable = true)
    private void paper$perLevelGameRules(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<GameRules> cir) {
        if (this.gameRules != null) {
            cir.setReturnValue(this.gameRules); // Paper - per-level GameRules
        } else if (ServerLevelInit.peek() != null) {
            cir.setReturnValue(ServerLevelInit.peek().gameRules());
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "getWeatherData", at = @At("HEAD"), cancellable = true)
    private void paper$perLevelWeatherData(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<WeatherData> cir) {
        if (this.weatherData != null) {
            cir.setReturnValue(this.weatherData); // Paper - per-level WeatherData
        }
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getWeatherData()Lnet/minecraft/world/level/saveddata/WeatherData;"))
    private WeatherData paper$prepareLevelWeather(final MinecraftServer server) {
        final ServerLevelInit init = ServerLevelInit.peek();
        if (init == null) {
            return server.getWeatherData();
        }
        final WeatherData weatherData = init.savedDataStorage().computeIfAbsent(WeatherData.TYPE);
        weatherData.setLevel((ServerLevel) (Object) this);
        return weatherData; // Paper - per-level WeatherData
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getScheduledEvents()Lnet/minecraft/world/level/timers/TimerQueue;"))
    private TimerQueue<MinecraftServer> paper$perLevelScheduledEvents(final MinecraftServer server) {
        return this.scheduledEvents != null ? this.scheduledEvents : server.getScheduledEvents(); // Paper - per-level scheduledEvents
    }

    private @Nullable WorldGenSettings fission$worldGenSettings() {
        if (this.worldGenSettings != null) {
            return this.worldGenSettings;
        }
        final ServerLevelInit init = ServerLevelInit.peek();
        return init != null ? init.worldGenSettings() : null;
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isFlat", at = @At("HEAD"), cancellable = true)
    private void paper$isFlat(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        final WorldGenSettings settings = this.fission$worldGenSettings();
        if (settings != null && this.typeKey != null) {
            cir.setReturnValue(settings.dimensions().get(this.getTypeKey()).map(levelStem -> levelStem.generator() instanceof net.minecraft.world.level.levelgen.FlatLevelSource).orElse(false)); // Paper
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void paper$getSeed(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Long> cir) {
        final WorldGenSettings settings = this.fission$worldGenSettings();
        if (settings != null) {
            cir.setReturnValue(settings.options().seed()); // CraftBukkit
        }
    }
    // Paper end

    // Spigot / Paper - thunder chance, disable thunder
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "tickThunder", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I", ordinal = 0))
    private int paper$thunderChance(final net.minecraft.util.RandomSource random, final int bound, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
        if (this.paperConfig().environment.disableThunder || this.spigotConfig.thunderChance <= 0) { // Paper - Option to disable thunder
            return 1;
        }
        return original.call(random, this.spigotConfig.thunderChance); // Spigot
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "tickThunder", at = @At(value = "CONSTANT", args = "doubleValue=0.01"))
    private double paper$skeletonHorseChance(final double chance) {
        return this.paperConfig().entities.spawning.skeletonHorseThunderSpawnChance.or(chance); // Paper - Configurable spawn chances for skeleton horses
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "tickThunder", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", ordinal = 0))
    private boolean paper$trapHorseReason(final ServerLevel level, final Entity horse) {
        return this.addFreshEntity(horse, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.LIGHTNING); // CraftBukkit
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "tickThunder", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", ordinal = 1))
    private boolean paper$weatherLightning(final ServerLevel level, final Entity bolt) {
        return this.strikeLightning(bolt, org.bukkit.event.weather.LightningStrikeEvent.Cause.WEATHER); // CraftBukkit
    }

    // CraftBukkit - BlockFormEvent for ice and snow
    @org.spongepowered.asm.mixin.injection.Redirect(method = "tickPrecipitation", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$precipitationFormEvent(final ServerLevel level, final BlockPos pos, final net.minecraft.world.level.block.state.BlockState state) {
        return org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(level, pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL, null); // CraftBukkit
    }

    // Paper - Add methods to find targets for lightning strikes
    @org.spongepowered.asm.mixin.injection.Inject(method = "findLightningTargetAround(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private void paper$findLightningTarget(final BlockPos pos, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<BlockPos> cir) {
        cir.setReturnValue(this.findLightningTargetAround(pos, false));
    }

    // Paper start - Add cause to Weather/ThunderChangeEvents
    @org.spongepowered.asm.mixin.injection.Redirect(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;setThundering(Z)V"))
    private void paper$naturalThunder(final WeatherData weatherData, final boolean thundering) {
        weatherData.setThundering(thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause.NATURAL);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;setRaining(Z)V"))
    private void paper$naturalRain(final WeatherData weatherData, final boolean raining) {
        weatherData.setRaining(raining, org.bukkit.event.weather.WeatherChangeEvent.Cause.NATURAL);
    }
    // Paper end - Add cause to Weather/ThunderChangeEvents

    // CraftBukkit start - weather packets are sent per player (player weather API)
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V"))
    private boolean paper$noDimensionWeatherBroadcast(final net.minecraft.server.players.PlayerList playerList, final net.minecraft.network.protocol.Packet<?> packet, final ResourceKey<Level> dimension) {
        return false;
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
    private boolean paper$noGlobalWeatherBroadcast(final net.minecraft.server.players.PlayerList playerList, final net.minecraft.network.protocol.Packet<?> packet) {
        return false;
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "advanceWeatherCycle", at = @At("TAIL"))
    private void paper$playerWeather(final CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(ordinal = 0) final boolean wasRaining) {
        for (ServerPlayer player : this.players) {
            if (player.level() == (Object) this) {
                player.tickWeather();
            }
        }

        if (wasRaining != this.isRaining()) {
            // Only send weather packets to those affected
            for (ServerPlayer player : this.players) {
                if (player.level() == (Object) this) {
                    player.setPlayerWeather((!wasRaining ? org.bukkit.WeatherType.DOWNFALL : org.bukkit.WeatherType.CLEAR), false);
                }
            }
        }
        for (ServerPlayer player : this.players) {
            if (player.level() == (Object) this) {
                player.updateWeather(this.oRainLevel, this.rainLevel, this.oThunderLevel, this.thunderLevel);
            }
        }
    }
    // CraftBukkit end

    @org.spongepowered.asm.mixin.injection.Inject(method = "resetWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void paper$resetWeatherCycle(final CallbackInfo ci) {
        WeatherData weatherData = ((ServerLevel) (Object) this).getWeatherData();
        // CraftBukkit start
        weatherData.setRaining(false, org.bukkit.event.weather.WeatherChangeEvent.Cause.SLEEP); // Paper - Add cause to Weather/ThunderChangeEvents
        // If we stop due to everyone sleeping we should reset the weather duration to some other random value.
        // Not that everyone ever manages to get the whole server to sleep at the same time....
        if (!weatherData.isRaining()) {
            weatherData.setRainTime(0);
        }
        // CraftBukkit end
        weatherData.setThundering(false, org.bukkit.event.weather.ThunderChangeEvent.Cause.SLEEP); // Paper - Add cause to Weather/ThunderChangeEvents
        // CraftBukkit start
        if (!weatherData.isThundering()) {
            weatherData.setThunderTime(0);
        }
        // CraftBukkit end
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void paper$tickNonPassengerThread(final Entity entity, final CallbackInfo ci) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread("Cannot tick an entity off-main"); // Paper - log detailed entity tick information
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER))
    private void paper$postTick(final Entity entity, final CallbackInfo ci) {
        entity.postTick(); // CraftBukkit
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "tickPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V", shift = At.Shift.AFTER))
    private void paper$postRideTick(final Entity vehicle, final Entity entity, final CallbackInfo ci) {
        entity.postTick(); // CraftBukkit
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "save", at = @At("HEAD"))
    private void paper$worldSaveEvent(final net.minecraft.util.ProgressListener progressListener, final boolean flush, final boolean noSave, final CallbackInfo ci) {
        if (!noSave) {
            new org.bukkit.event.world.WorldSaveEvent(this.getWorld()).callEvent(); // CraftBukkit
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "saveLevelData", at = @At("HEAD"))
    private void paper$savePdc(final boolean sync, final CallbackInfo ci) {
        this.getChunkSource().getDataStorage().computeIfAbsent(io.papermc.paper.world.saveddata.PaperWorldPDC.TYPE).setFrom(this.persistentDataContainer); // Paper
    }

    // Paper start - Add BlockBreakProgressUpdateEvent
    @org.spongepowered.asm.mixin.injection.Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void paper$blockBreakProgressEvent(final int id, final BlockPos blockPos, final int progress, final CallbackInfo ci) {
        Entity entity = this.getEntity(id);
        // If a plugin is using this method to send destroy packets for a client-side only entity id, no block progress occurred on the server.
        // Hence, do not call the event.
        if (entity != null) {
            float progressFloat = Mth.clamp(progress, 0, 10) / 10.0f;
            org.bukkit.craftbukkit.block.CraftBlock bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at((ServerLevel) (Object) this, blockPos);
            new io.papermc.paper.event.block.BlockBreakProgressUpdateEvent(bukkitBlock, progressFloat, entity.getBukkitEntity())
                .callEvent();
        }
    }
    // Paper end - Add BlockBreakProgressUpdateEvent

    // CraftBukkit - don't show the progress of hidden players
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private boolean paper$destroyProgressCanSee(final net.minecraft.server.network.ServerGamePacketListenerImpl connection, final net.minecraft.network.protocol.Packet<?> packet, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true, ordinal = 0) final int id) {
        final Entity entity = this.getEntity(id);
        return !(entity instanceof net.minecraft.world.entity.player.Player breakerPlayer) || connection.player.getBukkitEntity().canSee(breakerPlayer.getBukkitEntity());
    }

    // Paper start - Prevent GameEvents being fired from unloaded chunks
    @org.spongepowered.asm.mixin.injection.Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
    private void paper$gameEventLoadedOnly(final Holder<net.minecraft.world.level.gameevent.GameEvent> gameEvent, final Vec3 position, final net.minecraft.world.level.gameevent.GameEvent.Context context, final CallbackInfo ci) {
        if (this.getChunkIfLoadedImmediately((Mth.floor(position.x) >> 4), (Mth.floor(position.z) >> 4)) == null) {
            ci.cancel();
        }
    }
    // Paper end - Prevent GameEvents being fired from unloaded chunks

    @org.spongepowered.asm.mixin.injection.Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"), cancellable = true)
    private void paper$noNeighborUpdatesWhilePopulating(final BlockPos pos, final net.minecraft.world.level.block.Block sourceBlock, final CallbackInfo ci) {
        // CraftBukkit start
        if (this.populating) {
            ci.cancel();
            return;
        }
        // CraftBukkit end
        if (this.captureBlockStates) { ci.cancel(); } // Paper - Cancel all physics during placement
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V", at = @At("HEAD"), cancellable = true)
    private void paper$noNeighborUpdatesWhileCapturing(final BlockPos pos, final net.minecraft.world.level.block.Block sourceBlock, final net.minecraft.world.level.redstone.@Nullable Orientation orientation, final CallbackInfo ci) {
        if (this.captureBlockStates) { ci.cancel(); } // Paper - Cancel all physics during placement
    }

    // CraftBukkit - vanilla explode delegates to explode0 (STANDARD interaction, wasCanceled)
    @org.spongepowered.asm.mixin.injection.Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void paper$explode0(final @Nullable Entity source, final @Nullable DamageSource damageSource, final @Nullable ExplosionDamageCalculator damageCalculator, final double x, final double y, final double z, final float r, final boolean fire, final Level.ExplosionInteraction interactionType, final ParticleOptions smallExplosionParticles, final ParticleOptions largeExplosionParticles, final WeightedList<ExplosionParticleInfo> blockParticles, final Holder<SoundEvent> explosionSound, final CallbackInfo ci) {
        this.explode0(source, damageSource, damageCalculator, x, y, z, r, fire, interactionType, smallExplosionParticles, largeExplosionParticles, blockParticles, explosionSound);
        ci.cancel();
    }

    // Paper start - Call missing map initialize event and set id
    @org.spongepowered.asm.mixin.injection.Inject(method = "getMapData", at = @At("HEAD"), cancellable = true)
    private void paper$getMapData(final net.minecraft.world.level.saveddata.maps.MapId id, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.saveddata.maps.MapItemSavedData> cir) {
        final SavedDataStorage storage = this.getServer().getDataStorage();

        final Optional<net.minecraft.world.level.saveddata.SavedData> cacheEntry = storage.cache.get(net.minecraft.world.level.saveddata.maps.MapItemSavedData.type(id));
        if (cacheEntry == null) { // Cache did not contain, try to load and may init
            final net.minecraft.world.level.saveddata.maps.MapItemSavedData mapData = storage.get(net.minecraft.world.level.saveddata.maps.MapItemSavedData.type(id)); // get populates the cache
            if (mapData != null) { // map was read, init it and return
                mapData.id = id;
                new org.bukkit.event.server.MapInitializeEvent(mapData.mapView).callEvent();
                cir.setReturnValue(mapData);
                return;
            }

            cir.setReturnValue(null); // Map does not exist, reading failed.
            return;
        }
        // Cache entry exists, update it with the id ref and return.
        if (cacheEntry.orElse(null) instanceof final net.minecraft.world.level.saveddata.maps.MapItemSavedData mapItemSavedData) {
            mapItemSavedData.id = id;
            cir.setReturnValue(mapItemSavedData);
            return;
        }

        cir.setReturnValue(null);
    }
    // Paper end - Call missing map initialize event and set id

    @org.spongepowered.asm.mixin.injection.Inject(method = "setMapData", at = @At("HEAD"))
    private void paper$setMapData(final net.minecraft.world.level.saveddata.maps.MapId id, final net.minecraft.world.level.saveddata.maps.MapItemSavedData data, final CallbackInfo ci) {
        // CraftBukkit start
        data.id = id;
        org.bukkit.event.server.MapInitializeEvent event = new org.bukkit.event.server.MapInitializeEvent(data.mapView);
        event.callEvent();
        // CraftBukkit end
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "getEntities()Lnet/minecraft/world/level/entity/LevelEntityGetter;", at = @At("HEAD"))
    private void paper$asyncGetEntities(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> cir) {
        org.spigotmc.AsyncCatcher.catchOp("Chunk getEntities call"); // Spigot
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V", shift = At.Shift.AFTER))
    private void paper$resetPrimedTnt(final java.util.function.BooleanSupplier haveTime, final CallbackInfo ci) {
        this.spigotConfig.currentPrimedTnt = 0; // Spigot
    }

    // CraftBukkit - this prevents entity cleanup, other issues on servers with no players // Paper - restore this
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;hasActiveTickets()Z"))
    private boolean paper$tickWhenEmpty(final boolean hasActiveTickets) {
        return !this.paperConfig().unsupportedSettings.disableWorldTickingWhenEmpty || hasActiveTickets;
    }
}
