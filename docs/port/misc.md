# Misc area port (wave 2)

## Area: patches left after subtracting the other five areas

Computed from all 946 `paper-server/patches/sources/**` patches minus entity core, entity subtypes, player+network,
server+world and blocks+items (package rules from the launch brief). 126 patches:

```
ca/spottedleaf/moonrise/paper/PaperHooks.java.patch
ca/spottedleaf/moonrise/paper/util/BaseChunkSystemHooks.java.patch
com/mojang/authlib/services/MinecraftServicesDiscoveryService.java.patch
com/mojang/authlib/services/MinecraftServicesProfileRepository.java.patch
com/mojang/brigadier/builder/ArgumentBuilder.java.patch
com/mojang/brigadier/CommandDispatcher.java.patch
com/mojang/brigadier/exceptions/CommandSyntaxException.java.patch
com/mojang/brigadier/suggestion/IntegerSuggestion.java.patch
com/mojang/brigadier/suggestion/Suggestion.java.patch
com/mojang/brigadier/tree/CommandNode.java.patch
com/mojang/brigadier/tree/LiteralCommandNode.java.patch
com/mojang/datafixers/DataFixerBuilder.java.patch
com/mojang/datafixers/util/Either.java.patch
com/mojang/logging/LogUtils.java.patch
com/mojang/math/OctahedralGroup.java.patch
com/mojang/serialization/Dynamic.java.patch
io/papermc/paper/FeatureHooks.java.patch
net/minecraft/advancements/AdvancementHolder.java.patch
net/minecraft/advancements/AdvancementTree.java.patch
net/minecraft/advancements/predicates/LocationPredicate.java.patch
net/minecraft/advancements/triggers/SimpleCriterionTrigger.java.patch
net/minecraft/core/BlockPos.java.patch
net/minecraft/core/ClientAsset.java.patch
net/minecraft/core/component/BlockTransformer.java.patch
net/minecraft/core/component/DataComponentPatch.java.patch
net/minecraft/core/component/DataComponents.java.patch
net/minecraft/core/Direction.java.patch
net/minecraft/core/Holder.java.patch
net/minecraft/core/HolderLookup.java.patch
net/minecraft/core/HolderSet.java.patch
net/minecraft/core/MappedRegistry.java.patch
net/minecraft/core/particles/SpellParticleOption.java.patch
net/minecraft/core/registries/BootstrapRegistry.java.patch
net/minecraft/core/registries/BuiltInRegistries.java.patch
net/minecraft/core/registries/PatchedRegistry.java.patch
net/minecraft/core/Rotations.java.patch
net/minecraft/core/Vec3i.java.patch
net/minecraft/CrashReport.java.patch
net/minecraft/data/loot/packs/VanillaChestLoot.java.patch
net/minecraft/gametest/framework/GameTestInfo.java.patch
net/minecraft/gametest/framework/GameTestServer.java.patch
net/minecraft/gametest/framework/StructureUtils.java.patch
net/minecraft/gametest/framework/TestEnvironmentDefinition.java.patch
net/minecraft/nbt/ByteArrayTag.java.patch
net/minecraft/nbt/CompoundTag.java.patch
net/minecraft/nbt/IntArrayTag.java.patch
net/minecraft/nbt/LongArrayTag.java.patch
net/minecraft/nbt/NbtAccounter.java.patch
net/minecraft/nbt/NbtIo.java.patch
net/minecraft/nbt/SnbtGrammar.java.patch
net/minecraft/resources/Identifier.java.patch
net/minecraft/resources/NetworkRegistryLoadTask.java.patch
net/minecraft/resources/RegistryDataLoader.java.patch
net/minecraft/resources/RegistryLoadTask.java.patch
net/minecraft/resources/RegistryOps.java.patch
net/minecraft/resources/ResourceManagerRegistryLoadTask.java.patch
net/minecraft/server/bossevents/CustomBossEvent.java.patch
net/minecraft/server/dialog/body/ItemBody.java.patch
net/minecraft/server/gui/MinecraftServerGui.java.patch
net/minecraft/server/gui/StatsComponent.java.patch
net/minecraft/server/jsonrpc/internalapi/MinecraftGameRuleServiceImpl.java.patch
net/minecraft/server/jsonrpc/JsonRpcNotificationService.java.patch
net/minecraft/server/jsonrpc/methods/BanlistService.java.patch
net/minecraft/server/jsonrpc/methods/IpBanlistService.java.patch
net/minecraft/server/jsonrpc/methods/PlayerService.java.patch
net/minecraft/server/notifications/EmptyNotificationService.java.patch
net/minecraft/server/notifications/NotificationManager.java.patch
net/minecraft/server/notifications/NotificationService.java.patch
net/minecraft/server/packs/repository/PackRepository.java.patch
net/minecraft/server/packs/repository/ServerPacksSource.java.patch
net/minecraft/server/permissions/PermissionProviderCheck.java.patch
net/minecraft/server/rcon/RconConsoleSource.java.patch
net/minecraft/server/rcon/thread/QueryThreadGs4.java.patch
net/minecraft/server/rcon/thread/RconClient.java.patch
net/minecraft/server/rcon/thread/RconThread.java.patch
net/minecraft/server/waypoints/ServerWaypointManager.java.patch
net/minecraft/stats/ServerRecipeBook.java.patch
net/minecraft/stats/ServerStatsCounter.java.patch
net/minecraft/stats/StatsCounter.java.patch
net/minecraft/tags/TagLoader.java.patch
net/minecraft/util/datafix/DataFixers.java.patch
net/minecraft/util/datafix/DataFixTypes.java.patch
net/minecraft/util/datafix/fixes/ItemStackMapIdFix.java.patch
net/minecraft/util/datafix/fixes/ItemStackTheFlatteningFix.java.patch
net/minecraft/util/datafix/fixes/RemoveBlockEntityTagFix.java.patch
net/minecraft/util/EncoderCache.java.patch
net/minecraft/util/filefix/FileFixerUpper.java.patch
net/minecraft/util/filefix/fixes/LegacyStructureFileFix.java.patch
net/minecraft/util/parsing/packrat/Scope.java.patch
net/minecraft/util/PlaceholderLookupProvider.java.patch
net/minecraft/util/random/WeightedList.java.patch
net/minecraft/util/SimpleBitStorage.java.patch
net/minecraft/util/SpawnUtil.java.patch
net/minecraft/util/StringUtil.java.patch
net/minecraft/util/thread/BlockableEventLoop.java.patch
net/minecraft/util/TickThrottler.java.patch
net/minecraft/util/Util.java.patch
net/minecraft/util/worldupdate/WorldUpgrader.java.patch
net/minecraft/util/ZeroBitStorage.java.patch
net/minecraft/world/BossEvent.java.patch
net/minecraft/world/clock/ServerClockManager.java.patch
net/minecraft/world/CompoundContainer.java.patch
net/minecraft/world/Container.java.patch
net/minecraft/world/food/FoodData.java.patch
net/minecraft/world/food/FoodProperties.java.patch
net/minecraft/world/InteractionResult.java.patch
net/minecraft/world/level/biome/MobSpawnSettings.java.patch
net/minecraft/world/level/dimension/end/DragonRespawnStage.java.patch
net/minecraft/world/level/dimension/end/EnderDragonFight.java.patch
net/minecraft/world/level/gameevent/DynamicGameEventListener.java.patch
net/minecraft/world/level/gameevent/GameEventDispatcher.java.patch
net/minecraft/world/level/gameevent/GameEvent.java.patch
net/minecraft/world/level/gameevent/vibrations/VibrationSystem.java.patch
net/minecraft/world/level/pathfinder/WalkNodeEvaluator.java.patch
net/minecraft/world/level/redstone/CollectingNeighborUpdater.java.patch
net/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator.java.patch
net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java.patch
net/minecraft/world/level/redstone/NeighborUpdater.java.patch
net/minecraft/world/LockCode.java.patch
net/minecraft/world/RandomizableContainer.java.patch
net/minecraft/world/scores/Objective.java.patch
net/minecraft/world/scores/PlayerTeam.java.patch
net/minecraft/world/scores/Scoreboard.java.patch
net/minecraft/world/SimpleContainer.java.patch
net/minecraft/world/waypoints/WaypointTransmitter.java.patch
org/jline/utils/ShutdownHooks.java.patch
```

Not mine although left over (wave-1 mixins written by another area, or not runtime code):
- `world/{Container,CompoundContainer,SimpleContainer,RandomizableContainer,LockCode}` -> blocks+items (their wave-1 mixins)
- `world/food/{FoodData,FoodProperties}` -> player (wave-1 FoodDataMixin)
- `world/BossEvent`, `server/bossevents/CustomBossEvent`, `server/rcon/**` -> server+world (wave-1 mixins)
- `world/level/dimension/end/**` -> server+world (EnderDragonFight, gateway logic)
- `gametest/**`, `data/loot/**` (datagen / gametest server, not run), `server/gui/**` (--nogui)
- `ca/spottedleaf/moonrise/**`, `io/papermc/paper/FeatureHooks` (Moonrise / features wave)

## Checklist

### com.mojang (libraries)
- [x] com/mojang/brigadier/CommandDispatcher.java.patch (all hunks: getRelevantNodes with source, TagParseCommandSyntaxException stops parsing, getSmartUsage null source (+ children lambda), no suggestions when requirement fails, propagate suggestion failures)
- [x] com/mojang/brigadier/tree/CommandNode.java.patch (all hunks; vanilla getRelevantNodes(StringReader) left as-is = Paper's delegation with null source; command-block check compares the class name instead of instanceof, see note at the bottom)
- [x] com/mojang/brigadier/tree/LiteralCommandNode.java.patch (all hunks: nonPrefixed second pass)
- [x] com/mojang/brigadier/builder/ArgumentBuilder.java.patch (requirement = defaultRequirement())
- [x] com/mojang/brigadier/exceptions/CommandSyntaxException.java.patch (wave 1: interface + componentMessage)
- [x] com/mojang/brigadier/suggestion/Suggestion.java.patch (compare0; not class-loaded during the boot test, unverified at runtime)
- [x] com/mojang/brigadier/suggestion/IntegerSuggestion.java.patch (same)
- [x] com/mojang/authlib/services/MinecraftServicesDiscoveryService.java.patch (wave 1: access changes via misc.at/libs2.txt)
- [x] com/mojang/authlib/services/MinecraftServicesProfileRepository.java.patch (skip first sleep, debug-only log details)
- [x] com/mojang/datafixers/DataFixerBuilder.java.patch (Paper.minPrecachedDatafixVersion: optimize() iterates the tail set; default precaches nothing)
- [x] com/mojang/datafixers/util/Either.java.patch (cached Optional in Left/Right)
- [x] com/mojang/serialization/Dynamic.java.patch (Paper.debugDynamicMissingKeys)
- [x] com/mojang/logging/LogUtils.java.patch (wave 1)
- [x] com/mojang/math/OctahedralGroup.java.patch (static init of rotation maps; the lazy branch in rotate() stays, never taken)

### advancements
- [x] net/minecraft/advancements/AdvancementHolder.java.patch (wave 1 toBukkit)
- [x] net/minecraft/advancements/AdvancementTree.java.patch (log levels)
- [x] net/minecraft/advancements/predicates/LocationPredicate.java.patch (strictAdvancementDimensionCheck, default false = main dimension key)
- [x] net/minecraft/advancements/triggers/SimpleCriterionTrigger.java.patch (lazy LootContext)

### registries / resources / tags
- [~] net/minecraft/core/MappedRegistry.java.patch (done: temporaryUnfrozenMap fill/clear, getValueForCopying, clearIntrusiveHolder; left: map presizing 2048 (perf only))
- [x] net/minecraft/resources/RegistryDataLoader.java.patch (providerForBuilders set on the returned lookup at createContext RETURN)
- [x] net/minecraft/resources/RegistryLoadTask.java.patch (registerRegistry; registerElements -> PaperRegistryListenerManager.registerWithListeners with the task's Conversions. FissionMC guard: plain register while the BOOTSTRAPPER entrypoint has not been entered, because Bootstrap.java.patch (server area) is not ported and registerWithListeners checkState()s it)
- [x] net/minecraft/resources/ResourceManagerRegistryLoadTask.java.patch (Conversions per load, lockReferenceHolders, runFreezeListeners, tag lifecycle overload with registry + Cause.INITIAL)
- [x] net/minecraft/resources/NetworkRegistryLoadTask.java.patch (null conversions: the field defaults to null)
- [x] net/minecraft/tags/TagLoader.java.patch (pre/post flatten tag events around build(); Paper's Cause overloads added as public statics (thread-local cause/registry) for WorldLoader/MinecraftServer reload callers in the server area)

### scoreboards
- [x] net/minecraft/world/scores/Objective.java.patch (lazy formatted display name, using the vanilla field)
- [x] net/minecraft/world/scores/PlayerTeam.java.patch (lenient TeamColor codec)
- [x] net/minecraft/world/scores/Scoreboard.java.patch (clear team entries on removal; saveEmptyScoreboardTeams filter)

### nbt
- [x] net/minecraft/nbt/ByteArrayTag.java.patch (length < 1 << 24)
- [x] net/minecraft/nbt/IntArrayTag.java.patch (same)
- [x] net/minecraft/nbt/LongArrayTag.java.patch (same)
- [x] net/minecraft/nbt/NbtAccounter.java.patch (pushDepth(int) member, for ByteBufCodecs in the player area)
- [x] net/minecraft/nbt/NbtIo.java.patch (LimitStream for ByteBufInputStream)
- [~] net/minecraft/nbt/CompoundTag.java.patch (done: readQuiet x4; left: Object2ObjectOpenHashMap backing maps / copy() (memory footprint, perf only))

### util
- [~] net/minecraft/util/Util.java.patch (done: COLLISION_EPSILON, DIMENSION_DATA_IO_POOL (wave 1), Download pool limited to 4 threads, getNanos = System.nanoTime, background executor threads at NORM_PRIORITY-1 and never expiring, Paper worker thread count / Paper.WorkerThreadCount; left: nothing functional — Paper's makeExecutor(name, priority) signature itself is not added since the only caller is the static initialiser)
- [x] net/minecraft/util/thread/BlockableEventLoop.java.patch (executeAllRecentInternalTasks, runTaskIf, scheduleOnMain (wave 1))
- [x] net/minecraft/util/TickThrottler.java.patch (atomic counter, isIncrementAndUnderThreshold overloads; not class-loaded during the boot test)

## Audit added during cleanup (patches that had no checklist entry)
Entries marked `wave-1 only` have at most the wave-1 member/access declarations; in-method hunks were NOT audited or ported.

- [ ] ca/spottedleaf/moonrise/paper/PaperHooks.java.patch (wave-1 only / not started)
- [ ] ca/spottedleaf/moonrise/paper/util/BaseChunkSystemHooks.java.patch (wave-1 only / not started)
- [ ] io/papermc/paper/FeatureHooks.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/BlockPos.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/ClientAsset.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/component/BlockTransformer.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/component/DataComponentPatch.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/component/DataComponents.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/Direction.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/Holder.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/HolderLookup.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/HolderSet.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/particles/SpellParticleOption.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/registries/BootstrapRegistry.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/core/registries/BuiltInRegistries.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/core/registries/PatchedRegistry.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/core/Rotations.java.patch (wave-1 only / not started)
- [ ] net/minecraft/core/Vec3i.java.patch (wave-1 only / not started)
- [ ] net/minecraft/CrashReport.java.patch (wave-1 only / not started)
- [ ] net/minecraft/data/loot/packs/VanillaChestLoot.java.patch (wave-1 only / not started)
- [ ] net/minecraft/gametest/framework/GameTestInfo.java.patch (wave-1 only / not started)
- [ ] net/minecraft/gametest/framework/GameTestServer.java.patch (wave-1 only / not started)
- [ ] net/minecraft/gametest/framework/StructureUtils.java.patch (wave-1 only / not started)
- [ ] net/minecraft/gametest/framework/TestEnvironmentDefinition.java.patch (wave-1 only / not started)
- [ ] net/minecraft/nbt/SnbtGrammar.java.patch (wave-1 only / not started)
- [ ] net/minecraft/resources/Identifier.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/resources/RegistryOps.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/bossevents/CustomBossEvent.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/server/dialog/body/ItemBody.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/gui/MinecraftServerGui.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/gui/StatsComponent.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/jsonrpc/internalapi/MinecraftGameRuleServiceImpl.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/jsonrpc/JsonRpcNotificationService.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/jsonrpc/methods/BanlistService.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/jsonrpc/methods/IpBanlistService.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/jsonrpc/methods/PlayerService.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/notifications/EmptyNotificationService.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/notifications/NotificationManager.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/notifications/NotificationService.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/packs/repository/PackRepository.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/packs/repository/ServerPacksSource.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/permissions/PermissionProviderCheck.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/rcon/RconConsoleSource.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/server/rcon/thread/QueryThreadGs4.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/rcon/thread/RconClient.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/rcon/thread/RconThread.java.patch (wave-1 only / not started)
- [ ] net/minecraft/server/waypoints/ServerWaypointManager.java.patch (wave-1 only / not started)
- [ ] net/minecraft/stats/ServerRecipeBook.java.patch (wave-1 only / not started)
- [ ] net/minecraft/stats/ServerStatsCounter.java.patch (wave-1 only / not started)
- [ ] net/minecraft/stats/StatsCounter.java.patch (wave-1 only / not started)
- [x] net/minecraft/util/datafix/DataFixers.java.patch (CraftBukkit Player CustomName fix registered after schema 1458; DataFixersMixin)
- [x] net/minecraft/util/datafix/DataFixTypes.java.patch (DataFixTypesMixin + invoker)
- [x] net/minecraft/util/datafix/fixes/ItemStackMapIdFix.java.patch (ItemStackMapIdFixMixin; compiles, not class-loaded at boot)
- [x] net/minecraft/util/datafix/fixes/ItemStackTheFlatteningFix.java.patch (ItemStackTheFlatteningFixMixin; compiles, not class-loaded at boot)
- [ ] net/minecraft/util/datafix/fixes/RemoveBlockEntityTagFix.java.patch (no mixin present)
- [ ] net/minecraft/util/EncoderCache.java.patch (wave-1 only / not started)
- [~] net/minecraft/util/filefix/FileFixerUpper.java.patch (done: warn and delay initial migration; left: correct stacktrace MC-308790)
- [ ] net/minecraft/util/filefix/fixes/LegacyStructureFileFix.java.patch (no mixin present)
- [ ] net/minecraft/util/parsing/packrat/Scope.java.patch (wave-1 only / not started)
- [ ] net/minecraft/util/PlaceholderLookupProvider.java.patch (wave-1 only / not started)
- [~] net/minecraft/util/random/WeightedList.java.patch (access changes in surface/misc.at only; no mixin body checked)
- [ ] net/minecraft/util/SimpleBitStorage.java.patch (wave-1 only / not started)
- [x] net/minecraft/util/SpawnUtil.java.patch (SpawnUtilMixin: trySpawnMob with spawn reason + onAbort; compiles, not class-loaded at boot)
- [x] net/minecraft/util/StringUtil.java.patch (StringUtilMixin isReasonablePlayerName; not class-loaded at boot)
- [ ] net/minecraft/util/worldupdate/WorldUpgrader.java.patch (wave-1 only / not started)
- [ ] net/minecraft/util/ZeroBitStorage.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/BossEvent.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/clock/ServerClockManager.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/CompoundContainer.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/Container.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/food/FoodData.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/food/FoodProperties.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/InteractionResult.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/biome/MobSpawnSettings.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/dimension/end/DragonRespawnStage.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/dimension/end/EnderDragonFight.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/level/gameevent/DynamicGameEventListener.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/gameevent/GameEventDispatcher.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/gameevent/GameEvent.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/gameevent/vibrations/VibrationSystem.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/pathfinder/WalkNodeEvaluator.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/redstone/CollectingNeighborUpdater.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/level/redstone/NeighborUpdater.java.patch (wave-1 only / not started)
- [ ] net/minecraft/world/LockCode.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/RandomizableContainer.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/SimpleContainer.java.patch (mixin file exists, contents not audited)
- [ ] net/minecraft/world/waypoints/WaypointTransmitter.java.patch (wave-1 only / not started)
- [ ] org/jline/utils/ShutdownHooks.java.patch (wave-1 only / not started)
