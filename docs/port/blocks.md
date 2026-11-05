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
- [~] world/inventory/*Menu.java.patch (done: Bukkit views (getBukkitView) for Anvil, Beacon, BrewingStand, Cartography, Chest, Crafter, Dispenser, Furnace, Grindstone and similar menus; EnchantmentMenu: PrepareItemEnchantEvent in slotsChanged (replaced body), EnchantItemEvent in clickMenuButton lambda (replaced body), fillEnchantmentOffers; left: TODO(behaviour) in EnchantmentMenuMixin - InventoryHolders (enchantSlots with block holder/location); remaining menus not audited hunk-by-hunk, boot only checked that mixins apply)

## Package C pass 1 (menus, fire, pistons, fluids, spread, hoppers, dispensers)
- [~] world/inventory/AbstractContainerMenu.java.patch (done: InventoryDragEvent, drop-outside setCarried order, removed() SPIGOT-4556, getCarried normalise, slot sanity checks, dropOrPlaceInInventory isAlive; left: ContainerListener.slotChanged 4-arg (PlayerInventorySlotChangeEvent), synchronizer.sendOffHandSlotChange, SPIGOT-8010 drop loop break, moveItemStackTo isCheck (PlayerTradeEvent), click-sync packet for slot max stack. NOTE InventoryClickEvent / InventoryOpen/CloseEvent live in ServerGamePacketListenerImpl / ServerPlayer patches = player area)
- [~] world/inventory/CraftingMenu.java.patch (done: PrepareItemCraftEvent, checkReachable; left: current-recipe caching perf)
- [~] world/level/block/FireBlock.java.patch (done: BlockFade in updateShape, fireExtinguished, BlockIgnite/BlockSpread, BlockBurn, TNTPrimeEvent; left: onPlace(context), fire-tick-delay config)
- [~] world/level/block/BaseFireBlock.java.patch (done: fireExtinguished; left: EntityInsideBlockEvent, EntityCombustByBlockEvent, portal player)
- [~] world/level/block/piston/PistonBaseBlock.java.patch (done: BlockPistonExtend/RetractEvent incl. retract paths; left: headless-piston guard, dropResources source, desync capture, bedrock protection; config: allowPermanentBlockBreakExploits/allowPistonDuplication)
- [ ] world/level/block/piston/PistonMovingBlockEntity.java.patch
- [~] world/level/material/FlowingFluid.java.patch (done: BlockFromToEvent, FluidLevelChangeEvent; left: no-chunk-loading getBlockStateIfLoaded, beforeDestroyingBlock source)
- [~] world/level/material/LavaFluid.java.patch (done: BlockIgnite, BlockForm; left: lavaHurt pos)
- [~] world/level/block/LiquidBlock.java.patch (done: BlockFormEvent; config: water-over-lava-flow-speed)
- [x] world/level/block/SpongeBlock.java.patch (replaced body)
- [x] world/level/block/LeavesBlock.java.patch
- [~] world/level/block/SpreadingSnowyBlock.java.patch (config: grassSpread tick rate)
- [x] world/level/block/SnowLayerBlock.java.patch
- [~] world/level/block/VineBlock.java.patch (config: Spigot vineModifier)
- [~] world/level/block/IceBlock.java.patch (done: melt BlockFade; left: playerDestroy includeDrops/dropExp delegation)
- [x] world/level/block/FrostedIceBlock.java.patch (config: frosted-ice options)
- [~] world/level/block/entity/HopperBlockEntity.java.patch (done: InventoryMoveItemEvent both directions (replaced bodies), PrepareHopperTransferEvent for eject, InventoryPickupItemEvent, discard cause; left: HopperInventorySearchEvent, PrepareHopperTransferEvent in suckInItems, max stack size, spigot cooldown config)
- [~] world/level/block/DispenserBlock.java.patch (done: getDispenseBehavior fix (cast to mixin class was illegal), BlockFailedDispense, BlockPreDispense; left: openMenu().isPresent())
- [~] world/level/block/DropperBlock.java.patch (done: failed/pre-dispense, InventoryMoveItemEvent via stand-in return)
- [~] core/dispenser/DefaultDispenseItemBehavior.java.patch (done: BlockDispenseEvent in spawnItem(…, source) via wrapped addFreshEntity; facing not cached)
- [~] core/dispenser/{Boat,Minecart,ShulkerBox,Projectile}*Behavior.java.patch (PARTIAL: event fired up front, cancel/reroute honoured, item/velocity edits ignored)
- [ ] core/dispenser/DispenseItemBehavior.java.patch (bucket/armor stand/chest/bone meal/etc. behaviours - anonymous lambdas), FlintAndSteel, Shears, SpawnEgg, Equipment, SulfurCube
- [ ] remaining unlisted block entities: AbstractFurnaceBlockEntity (FurnaceSmelt/Burn/Extract/StartSmelt), BrewingStandBlockEntity (BrewEvent, BrewingStartEvent), Beacon (effect events), Beehive, Campfire, Lectern, Jukebox, SignBlockEntity (SignChangeEvent), ComposterBlock, NetherPortalBlock, TripWire, Farmland, ConcretePowder, Cauldrons, Bamboo, Turtle egg, ChorusFlower, Sapling, Tnt
