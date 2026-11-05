# Server + world port (wave 2)

Lifecycle hunks already ported are in `lifecycle.md` (not repeated here). `config: x` = hunk only supports a Paper
config option whose default is vanilla behaviour. `moonrise` = belongs to the Moonrise chunk system (features/, skipped).

## net.minecraft.server.level
- [~] sources/net/minecraft/server/level/ChunkHolder.java.patch (done: getFullChunkNow(Unchecked), SPIGOT-6086 section bounds check, callEventIfUnloading (ChunkUnloadEvent), ChunkLoadEvent scheduling at the end of updateFutures; left: moonrise "cache ticking ready status" + PlatformHooks callbacks, final getTickingChunk)
- [~] sources/net/minecraft/server/level/ChunkMap.java.patch (done: callbackExecutor (backend ChunkCallbackExecutor), CustomChunkGenerator delegate RandomState, reduced spawn range from PlayerNaturallySpawnCreaturesEvent in collectSpawningChunks, addEntity illegal-add guard + suppressTrackerForLogin + Spigot tracking range, AsyncCatcher in removeEntity/tracker, TrackedEntity: vanish canSee, trackingRangeY, PlayerTrackEntityEvent, passenger tracking range; left: createState(..., spigotConfig), getChunkDataFixContextTag level stem, seenBy ReferenceOpenHashSet + ServerEntity(seenBy), moonrise PlatformHooks)
- [x] sources/net/minecraft/server/level/DistanceManager.java.patch (all hunks)
- [~] sources/net/minecraft/server/level/ServerChunkCache.java.patch (done: savedDataStorage (lifecycle), fullChunks/isChunkLoaded/tickets/close(boolean)/purgeUnload members, currently-unloading ticket guard, chunkAbsent oldTicketLevel, unloadFrozenChunks, no spawning without players, PlayerNaturallySpawnCreaturesEvent, ticksPerSpawnCategory + spawnFriendlies + per-world limits via NaturalSpawner 5-arg, MainThreadExecutor.pollTask runs chunk callbacks; left: getChunk cache "chunkAccess != null" check, chunkFutureAwaitCounter; config: unsupported-settings.ticking.chunks)

## net.minecraft.world.level
- [~] sources/net/minecraft/world/level/NaturalSpawner.java.patch (done: count only natural spawns, per-world spawn limits/ticks-per-spawn (getFilteredSpawningCategories 5-arg + canSpawnForCategoryGlobal(limit)), don't load chunks for mob spawn, PreCreatureSpawnEvent incl. abort, NATURAL/OCELOT_BABY/CHUNK_GEN spawn reasons, after-spawn callback only when added; left: cluster/group counters not incremented on cancelled spawn, ServerInternalException reports)

## net.minecraft.world.level.chunk
- [~] sources/net/minecraft/world/level/chunk/LevelChunk.java.patch (done: mustNotSave/needsDecoration, ProtoChunk upgrade needsDecoration + PDC copy, onPlace suppressed while capturing block states, capturedBlockEntities lookup, SPIGOT-5561 pending block entity removal, loadCallback (ChunkLoadEvent, BlockPopulators, ChunkPopulateEvent, fullChunks) / unloadCallback (ChunkUnloadEvent, save flag), isUnsaved && !mustNotSave, invalid block entity removal, block entity tick exception -> log + ServerExceptionEvent + remove; left: perf getBlockStateFinal/getFluidState, setBlockEntity ServerInternalException; config: chunks.fixed-chunk-inhabited-time, unsupported-settings.ticking.block-entities)
- [~] sources/net/minecraft/world/level/chunk/ChunkAccess.java.patch (done: PDC, setNoiseBiome, PDC dirty in tryMarkSaved/isUnsaved; left: coordinateKey/locX/locZ (perf), abstract getBlockState(int,int,int))
