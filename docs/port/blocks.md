# Blocks / block entities / inventory / items port (wave 2)

Patches under `paper-server/patches/sources/net/minecraft/`. Mixins in `backend/.../mixin/{world/level/block,world/inventory,world/item,core,world}`.
Shared main-thread state for signature/record changes Mixin cannot make: `backend/.../blocks/PaperBlocks.java`.

## Done
- [~] world/item/ItemStack.java.patch (done: useOn replaced body (BlockPlaceEvent/BlockMultiPlaceEvent, BlockFertilizeEvent/StructureGrowEvent, capture + revert, deferred onPlace/physics, sign editor, place sound, wither skull), hurtAndBreak(…, LivingEntity, …, force) with PlayerItemDamageEvent/EntityDamageItemEvent/PlayerItemBreakEvent, hurtWithoutBreaking (Expand PlayerItemDamageEvent), hurtAndBreak(LivingEntity, slot) passes the owner; left: STREAM_CODEC sanitizer/adventure translatable hunks + CountingOps (network area))
- [~] world/item/BlockItem.java.patch (done: PlaceOnWater/SolidBucket BlockPlaceEvent, place sound only without player, placed position tracking (PaperSuccessContext via PaperBlocks.placedPos), BlockCanBuildEvent in canPlace, minecraft.nbt.place permission; left: reset placed block on exception)
- [~] world/InteractionResult.java.patch (PaperSuccessContext cannot be added to the record; placed position goes through PaperBlocks.placedPos instead)
- [~] world/level/block/Block.java.patch (done: isDestroyable, dropResources(…, source) BlockBreakBlockEvent, dropResources(…, dropExperience), captureDrops in popResource, popExperience(…, entity) with SpawnReason, playerDestroy(…, includeDrops, dropExp) + EntityExhaustionEvent reason, tryDropExperience no longer pops (XP via getExpDrop); left: UPDATE_SKIP_POI consumers (Level/PoiManager, server area))
- [~] world/level/block/state/BlockBehaviour.java.patch (done: asBlockData, isDestroyable, explosion indestructible + yield, canBeReplaced, getPistonPushReaction, spawnAfterBreak pops getExpDrop; onPlace(…, UseOnContext) via PaperBlocks.onPlaceContext; left: AsyncCatcher, perf-only final/shapeExceedsCube)
- [x] world/level/block/SignBlock.java.patch (PlayerOpenSignEvent/PlayerSignOpenEvent, openTextEdit(cause), no ticker, editor opened from ItemStack#useOn)
- [x] world/level/block/LeverBlock.java.patch
- [x] world/level/block/ButtonBlock.java.patch
- [x] world/level/block/DiodeBlock.java.patch
- [~] world/level/block/RedstoneTorchBlock.java.patch (done: BlockRedstoneEvent; left: perf - Faster redstone torch rapid clock removal)
- [x] world/level/block/RedstoneLampBlock.java.patch
- [x] world/level/block/PoweredRailBlock.java.patch
- [x] world/level/block/DetectorRailBlock.java.patch
- [x] world/level/block/DoorBlock.java.patch
- [x] world/level/block/TrapDoorBlock.java.patch
- [x] world/level/block/FenceGateBlock.java.patch
- [x] world/level/block/ObserverBlock.java.patch
- [x] world/level/block/ComparatorBlock.java.patch
- [x] world/level/block/CommandBlock.java.patch
- [~] world/level/block/NoteBlock.java.patch (done: BlockRedstoneEvent, SPIGOT-5617, NotePlayEvent in triggerEvent; config: blockUpdates.disableNoteblockUpdates)
- [x] world/level/block/BasePressurePlateBlock.java.patch
- [x] world/level/block/PressurePlateBlock.java.patch
- [x] world/level/block/WeightedPressurePlateBlock.java.patch
- [x] world/level/block/TargetBlock.java.patch
- [x] world/level/block/LightningRodBlock.java.patch
- [x] world/level/block/DaylightDetectorBlock.java.patch
- [x] world/level/block/CopperBulbBlock.java.patch
- [x] world/level/block/AbstractSkullBlock.java.patch
- [x] world/level/block/ShelfBlock.java.patch
- [~] world/level/block/CropBlock.java.patch (done: BlockGrowEvent, EntityInsideBlockEvent, ravager EntityChangeBlockEvent; config: Spigot growth modifiers)
- [~] world/level/block/StemBlock.java.patch (done: BlockGrowEvent; config: Spigot modifiers)
- [~] world/level/block/SugarCaneBlock.java.patch (done: BlockGrowEvent; config: caneModifier, maxGrowthHeight.reeds)
- [~] world/level/block/CactusBlock.java.patch (done: BlockGrowEvent, EntityInsideBlockEvent, block damager; config: maxGrowthHeight.cactus)
- [~] world/level/block/NetherWartBlock.java.patch (done: BlockGrowEvent; config: wartModifier)
- [~] world/level/block/CocoaBlock.java.patch (done: BlockGrowEvent; config: cocoaModifier)
- [~] world/level/block/SweetBerryBushBlock.java.patch (done: BlockGrowEvent, EntityInsideBlockEvent, block damager, PlayerHarvestBlockEvent; config: sweetBerryModifier)
