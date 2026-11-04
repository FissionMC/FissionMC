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
    @Shadow private @Nullable ChunkHolder getVisibleChunkIfPresent(long key) { throw new AssertionError(); }
    @Shadow abstract boolean runDistanceManagerUpdates(); // package-private in vanilla (widened by paper.at)
    @Shadow private void clearCache() {}
    @Shadow public abstract void save(boolean flushStorage);

    private boolean spawnFriendlies = true; // Paper - add back spawnFriendlies field
    // TODO(behaviour): tickChunks hunks (spawnFriendlies, ticksPerSpawnCategory, PlayerNaturallySpawnCreaturesEvent), getChunk unloading checks, pollTask callbackExecutor
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
    // TODO(behaviour): LevelChunk load/unload hunks call addLoadedChunk/removeLoadedChunk (until then fullChunks stays empty)
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
    // TODO(behaviour): vanilla close() -> close(true)
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

    // TODO(behaviour): vanilla setSpawnSettings(boolean) -> setSpawnSettings(spawnEnemies, this.spawnFriendlies)
    public void setSpawnSettings(final boolean spawnEnemies, final boolean spawnFriendlies) {
        this.spawnEnemies = spawnEnemies;
        this.spawnFriendlies = spawnFriendlies;
        // CraftBukkit end
    }
}
