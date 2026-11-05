package net.fissionmc.backend.mixin.server.level;

import java.io.IOException;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/level/ServerChunkCache.java.patch */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final private TicketStorage ticketStorage;
    @Shadow @Final private SavedDataStorage savedDataStorage;
    @Shadow @Final private ThreadedLevelLightEngine lightEngine;
    @Shadow public boolean spawnEnemies;
    @Shadow @Final private net.minecraft.server.level.ServerLevel level;
    @Shadow private @Nullable ChunkHolder getVisibleChunkIfPresent(long key) { throw new AssertionError(); }
    @Shadow abstract boolean runDistanceManagerUpdates(); // package-private in vanilla (widened by paper.at)
    @Shadow private void clearCache() {}
    @Shadow public abstract void save(boolean flushStorage);

    // Paper - initialize SavedDataStorage earlier: Paper passes the level's SavedDataStorage (created in
    // MinecraftServer.createLevel and holding PaperLevelOverrides/PaperWorldMetadata/PaperWorldPDC) into the constructor.
    // FissionMC: it arrives through ServerLevelInit (ServerLevel.create); without it Paper's per-world data is never saved.
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "<init>", at = @org.spongepowered.asm.mixin.injection.At(value = "NEW", target = "(Ljava/nio/file/Path;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/level/storage/SavedDataStorage;"))
    private SavedDataStorage paper$levelSavedDataStorage(final java.nio.file.Path dataFolder, final com.mojang.datafixers.DataFixer fixerUpper, final net.minecraft.core.HolderLookup.Provider registries, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<SavedDataStorage> original) {
        final net.fissionmc.backend.world.ServerLevelInit init = net.fissionmc.backend.world.ServerLevelInit.peek();
        return init != null ? init.savedDataStorage() : original.call(dataFolder, fixerUpper, registries);
    }

    private boolean spawnFriendlies = true; // Paper - add back spawnFriendlies field

    // CraftBukkit start - don't add new ticket for currently unloading chunk
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "getChunkFutureMainThread", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean paper$notWhileUnloading(final boolean loadOrGenerate, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true, ordinal = 0) final int x, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true, ordinal = 1) final int z) {
        ChunkHolder chunkHolder = this.getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        boolean currentlyUnloading = false;
        if (chunkHolder != null) {
            net.minecraft.server.level.FullChunkStatus oldChunkState = net.minecraft.server.level.ChunkLevel.fullStatus(chunkHolder.oldTicketLevel);
            net.minecraft.server.level.FullChunkStatus currentChunkState = net.minecraft.server.level.ChunkLevel.fullStatus(chunkHolder.getTicketLevel());
            currentlyUnloading = (oldChunkState.isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL) && !currentChunkState.isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL));
        }
        return loadOrGenerate && !currentlyUnloading;
        // CraftBukkit end
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "chunkAbsent", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTicketLevel()I"))
    private int paper$chunkAbsentOldTicketLevel(final ChunkHolder chunkHolder) {
        return chunkHolder.oldTicketLevel; // CraftBukkit - using oldTicketLevel for isLoaded checks
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "tick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/TickRateManager;runsNormally()Z", ordinal = 0))
    private boolean paper$unloadFrozenChunks(final boolean runsNormally) {
        return runsNormally || this.level.spigotConfig.unloadFrozenChunks; // Spigot
    }

    // CraftBukkit - this prevents entity cleanup, other issues on servers with no players
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Ljava/lang/Boolean;booleanValue()Z", ordinal = 0))
    private boolean paper$noSpawningWithoutPlayers(final boolean doMobSpawning) {
        return doMobSpawning && !this.level.players().isEmpty(); // CraftBukkit
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;getFilteredSpawningCategories(Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZ)Ljava/util/List;"))
    private java.util.List<net.minecraft.world.entity.MobCategory> paper$filteredSpawningCategories(final net.minecraft.world.level.NaturalSpawner.SpawnState spawnCookie, final boolean spawnEnemies, final boolean vanillaSpawnPersistent, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.util.List<net.minecraft.world.entity.MobCategory>> original) {
        if (!(this.spawnEnemies || this.spawnFriendlies)) { // Paper
            return java.util.List.of();
        }
        // Paper start - PlayerNaturallySpawnCreaturesEvent
        for (net.minecraft.server.level.ServerPlayer player : this.level.players()) {
            int chunkRange = Math.min(level.spigotConfig.mobSpawnRange, player.getBukkitEntity().getViewDistance());
            chunkRange = Math.min(chunkRange, 8);
            player.playerNaturallySpawnedEvent = new com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent(player.getBukkitEntity(), (byte) chunkRange);
            player.playerNaturallySpawnedEvent.callEvent();
        }
        // Paper end - PlayerNaturallySpawnCreaturesEvent
        boolean spawnPersistent = this.level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) != 0L && this.level.getGameTime() % this.level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) == 0L; // CraftBukkit
        return net.minecraft.world.level.NaturalSpawner.getFilteredSpawningCategories(spawnCookie, this.spawnFriendlies, this.spawnEnemies, spawnPersistent, this.level); // CraftBukkit
    }
    // TODO(behaviour): getChunk cache check (CraftBukkit - the chunk can become accessible in the meantime; not ported: needs a LOAD-only modification)
    // config: unsupported-settings.ticking.chunks (tickChunks gate)
    // Paper start
    private final ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable<LevelChunk> fullChunks = ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable.createWithCapacity(ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable.defaultCapacity(), 0.25f);
    public int getFullChunksCount() {
        return this.fullChunks.size();
    }
    // Paper end

    // CraftBukkit start - properly implement isChunkLoaded
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ChunkHolder chunk = this.chunkMap.getUpdatingChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
        if (chunk == null) {
            return false;
        }
        return chunk.getFullChunkNow() != null;
    }
    // CraftBukkit end
    // Paper start
    public void addLoadedChunk(LevelChunk chunk) {
        this.fullChunks.put(chunk.getPos().pack(), chunk); // FissionMC: Paper uses the cached ChunkAccess#coordinateKey
    }

    public void removeLoadedChunk(LevelChunk chunk) {
        this.fullChunks.remove(chunk.getPos().pack()); // FissionMC: Paper uses the cached ChunkAccess#coordinateKey
    }

    public @Nullable ChunkAccess getChunkAtImmediately(int x, int z) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        if (holder == null) {
            return null;
        }

        return holder.getLatestChunk();
    }

    public void addTicketAtLevel(TicketType ticketType, ChunkPos chunkPos, int ticketLevel) {
        this.ticketStorage.addTicket(new Ticket(ticketType, ticketLevel), chunkPos);
    }

    public void removeTicketAtLevel(TicketType ticketType, ChunkPos chunkPos, int ticketLevel) {
        this.ticketStorage.removeTicket(new Ticket(ticketType, ticketLevel), chunkPos);
    }

    // "real" get chunk if loaded
    // Note: Partially copied from the getChunkAt method below
    public @Nullable LevelChunk getChunkAtIfCachedImmediately(int x, int z) {
        long key = ChunkPos.pack(x, z);

        // Note: Bypass cache since we need to check ticket level, and to make this MT-Safe

        ChunkHolder playerChunk = this.getVisibleChunkIfPresent(key);
        if (playerChunk == null) {
            return null;
        }

        return playerChunk.getFullChunkNowUnchecked();
    }

    public @Nullable LevelChunk getChunkAtIfLoadedImmediately(int x, int z) {
        return this.fullChunks.get(ChunkPos.pack(x, z));
    }
    // Paper end

    // CraftBukkit start
    // FissionMC: vanilla close() is close(true) already
    public void close(boolean save) throws IOException {
        if (save) {
            this.save(true);
        }
        // CraftBukkit end
        this.savedDataStorage.close();
        this.lightEngine.close();
        this.chunkMap.close();
    }

    // CraftBukkit start - modelled on below
    public void purgeUnload() {
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("purge");
        this.ticketStorage.purgeStaleTickets(this.chunkMap);
        this.runDistanceManagerUpdates();
        gameprofilerfiller.popPush("unload");
        ((ChunkMapAccessorMixin) this.chunkMap).fission$tick(() -> true);
        gameprofilerfiller.pop();
        this.clearCache();
    }
    // CraftBukkit end

    // FissionMC: vanilla setSpawnSettings(boolean) only sets spawnEnemies, as Paper's delegation does
    public void setSpawnSettings(final boolean spawnEnemies, final boolean spawnFriendlies) {
        this.spawnEnemies = spawnEnemies;
        this.spawnFriendlies = spawnFriendlies;
        // CraftBukkit end
    }
}
