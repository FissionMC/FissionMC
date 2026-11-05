# Player / network port (wave 2)

Written during cleanup from the commits + mixins present (the earlier agent was cut off by a usage limit before writing this file).
`[~] ... (mixin present, N TODO(behaviour))` = the mixin exists and compiles/applies at boot (verified by force-loading every mixin target); the TODO markers in that file list the hunks still missing; hunks were NOT re-audited against the patch one by one.
`[ ]` = no mixin for that class (only wave-1 surface members, if any).

Cleanup fixes: PrepareSpawnTask constructor args are passed via fission$init (ServerConfigurationPacketListenerImplMixin wraps the `new`), ServerCodeOfConductConfigurationTaskMixin was an empty file and is now implemented, anonymous Waitable classes inside mixins replaced by backend/network/SupplierWaitable (Mixin cannot load anonymous classes declared in a mixin: NoClassDefFoundError `...$Anonymous$hash`).

Important unfinished hunks (from TODO markers): ServerGamePacketListenerImpl handleMovePlayer justTeleported/ClientTickEndEvent/teleport(cause) delegation/handleContainerClose reason/async chat executor; ServerPlayer: tick() hooks (joining, containerUpdateDelay), updateOptions events, tab list name/order, shoulder entities SpawnReason, closeContainer/teleportTo/setGameMode/setRespawnPosition/sendChatMessage delegations with causes, drop stats; ServerConfigurationPacketListenerImpl events (PlayerConnectionInitialConfigureEvent, PlayerLinksSendEvent, code of conduct, login result); Connection: preparing/handleConnectionDisconnectOnNextTick/large packets/join buffering; PlayerList getPlayerStats per UUID; CachedUserNameToIdResolver locks and async save; Anti-Xray (features/, skipped); proxy protocol (config); playerShuffle (config); DiscardedPayload codec; ClientboundPlayerInfoUpdatePacket INITIALIZE_CHAT key expiry; ClientboundSetPlayerTeamPacket collision/nametag.

## Patches
- [~] net/minecraft/network/Connection.java.patch (mixin present: Connection; 4 TODO(behaviour))
- [~] net/minecraft/network/DisconnectionDetails.java.patch (mixin present: DisconnectionDetails; 0 TODO(behaviour))
- [ ] net/minecraft/network/FriendlyByteBuf.java.patch
- [ ] net/minecraft/network/PacketEncoder.java.patch
- [ ] net/minecraft/network/PacketProcessor.java.patch
- [ ] net/minecraft/network/RegistryFriendlyByteBuf.java.patch
- [ ] net/minecraft/network/VarInt.java.patch
- [ ] net/minecraft/network/Varint21FrameDecoder.java.patch
- [~] net/minecraft/network/chat/ChatDecorator.java.patch (mixin present: ChatDecorator; 1 TODO(behaviour))
- [~] net/minecraft/network/chat/Component.java.patch (mixin present: Component; 0 TODO(behaviour))
- [ ] net/minecraft/network/chat/ComponentSerialization.java.patch
- [ ] net/minecraft/network/chat/ComponentUtils.java.patch
- [~] net/minecraft/network/chat/MessageSignature.java.patch (mixin present: MessageSignature; 0 TODO(behaviour))
- [ ] net/minecraft/network/chat/MutableComponent.java.patch
- [~] net/minecraft/network/chat/OutgoingChatMessage.java.patch (mixin present: OutgoingChatMessage, OutgoingChatMessage_Disguised, OutgoingChatMessage_Player; 2 TODO(behaviour))
- [~] net/minecraft/network/chat/PlayerChatMessage.java.patch (mixin present: PlayerChatMessage; 1 TODO(behaviour))
- [ ] net/minecraft/network/chat/SignedMessageChain.java.patch
- [ ] net/minecraft/network/chat/contents/NbtContents.java.patch
- [ ] net/minecraft/network/chat/contents/ObjectContents.java.patch
- [ ] net/minecraft/network/chat/contents/SelectorContents.java.patch
- [ ] net/minecraft/network/chat/contents/TranslatableContents.java.patch
- [ ] net/minecraft/network/codec/ByteBufCodecs.java.patch
- [ ] net/minecraft/network/protocol/Packet.java.patch
- [ ] net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket.java.patch
- [~] net/minecraft/network/protocol/common/custom/DiscardedPayload.java.patch (mixin present: DiscardedPayload; 1 TODO(behaviour))
- [ ] net/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket.java.patch
- [ ] net/minecraft/network/protocol/game/ClientboundContainerSetContentPacket.java.patch
- [ ] net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData.java.patch
- [~] net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java.patch (mixin present: ClientboundLevelChunkWithLightPacket; 1 TODO(behaviour))
- [~] net/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket.java.patch (mixin present: ClientboundPlayerInfoUpdatePacket; 1 TODO(behaviour))
- [~] net/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket.java.patch (mixin present: ClientboundSectionBlocksUpdatePacket; 1 TODO(behaviour))
- [ ] net/minecraft/network/protocol/game/ClientboundSetEntityDataPacket.java.patch
- [ ] net/minecraft/network/protocol/game/ClientboundSetEquipmentPacket.java.patch
- [~] net/minecraft/network/protocol/game/ClientboundSetPlayerTeamPacket.java.patch (mixin present: ClientboundSetPlayerTeamPacket; 1 TODO(behaviour))
- [~] net/minecraft/network/protocol/game/ClientboundSystemChatPacket.java.patch (mixin present: ClientboundSystemChatPacket; 0 TODO(behaviour))
- [ ] net/minecraft/network/protocol/game/GameProtocols.java.patch
- [ ] net/minecraft/network/protocol/game/ServerboundChatCommandPacket.java.patch
- [ ] net/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket.java.patch
- [ ] net/minecraft/network/protocol/game/ServerboundSetCreativeModeSlotPacket.java.patch
- [ ] net/minecraft/network/protocol/game/ServerboundSetGameRulePacket.java.patch
- [ ] net/minecraft/network/protocol/game/ServerboundUseItemOnPacket.java.patch
- [ ] net/minecraft/network/protocol/game/ServerboundUseItemPacket.java.patch
- [ ] net/minecraft/network/protocol/game/VecDeltaCodec.java.patch
- [ ] net/minecraft/network/protocol/handshake/ClientIntentionPacket.java.patch
- [ ] net/minecraft/network/protocol/login/ClientboundCustomQueryPacket.java.patch
- [~] net/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket.java.patch (mixin present: ServerboundCustomQueryAnswerPacket; 0 TODO(behaviour))
- [ ] net/minecraft/network/syncher/EntityDataSerializers.java.patch
- [~] net/minecraft/network/syncher/SynchedEntityData.java.patch (mixin present: SynchedEntityData; 0 TODO(behaviour))
- [ ] net/minecraft/server/level/PlayerSpawnFinder.java.patch
- [~] net/minecraft/server/level/ServerPlayer.java.patch (mixin present: ServerPlayer; 17 TODO(behaviour))
- [~] net/minecraft/server/level/ServerPlayerGameMode.java.patch (done: changeGameModeForPlayer overload, interact/capture fields, handleBlockBreakAction PlayerInteractEvent(LEFT_CLICK_BLOCK)+BlockDamageEvent+insta-break, abortDestroyBlock BlockDamageAbortEvent, destroyBlock BlockBreakEvent/BlockDropItemEvent/exp drop (replaced body, uses 6-arg playerDestroy), useItemOn PlayerInteractEvent(RIGHT_CLICK_BLOCK) + interactResult; left: TODO changeGameModeForPlayer(GameType) delegation, tick() lag-compensated gameTicks/unloaded-chunk checks, distance early-out, debug stick left click, spectator openMenu result, Block#playerDestroy dropItems flag (blocks area), useItem stackBeforeUse/forceHeldSlot, 'properly cancel usable items' resync)
- [~] net/minecraft/server/network/CommonListenerCookie.java.patch (mixin present: CommonListenerCookie; 0 TODO(behaviour))
- [ ] net/minecraft/server/network/EventLoopGroupHolder.java.patch
- [ ] net/minecraft/server/network/LegacyQueryHandler.java.patch
- [ ] net/minecraft/server/network/PlayerChunkSender.java.patch
- [ ] net/minecraft/server/network/ServerCommandSuggestionsProvider.java.patch
- [~] net/minecraft/server/network/ServerCommonPacketListenerImpl.java.patch (mixin present: ServerCommonPacketListenerImpl; 2 TODO(behaviour))
- [~] net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch (mixin present: ServerConfigurationPacketListenerImpl; 1 TODO(behaviour))
- [~] net/minecraft/server/network/ServerConnectionListener.java.patch (mixin present: ServerConnectionListener, ServerConnectionListener_1; 2 TODO(behaviour))
- [~] net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch (done: teleport(cause) delegation + internalTeleport lastPos, handleMovePlayer PlayerMoveEvent/PlayerJumpEvent/onGround reset/position-desync/internalTeleport on fail/immobile/moved-wrongly threshold, handleMoveVehicle PlayerMoveEvent, handlePlayerInput (PlayerInputEvent, ToggleSneak), handlePlayerAction (swap hands event, drop limit, unloaded dig check, captured block entities), handleUseItemOn (immobile/limit, stopUsingItem, no swing on interactResult), handleUseItem (PlayerInteractEvent RIGHT_CLICK_AIR/BLOCK), handlePunch (LEFT_CLICK_AIR, PlayerArmSwingEvent), handleInteract (PlayerInteractAtEntityEvent + desync resend, self-interact kick), handleAttack self kick, handleSetCarriedItem (PlayerItemHeldEvent), handlePlayerCommand (sprint toggle), handlePlayerAbilities (flight toggle), chat (async executor for FutureChain, async chat future, broadcastChatMessage -> ChatProcessor, tryHandleChat sync only for commands, PlayerCommandPreprocessEvent signed+unsigned, hasDisconnected guard, hidden-sender disguised chat), handleContainerClose(reason) delegation, handleContainerClick (InventoryClickEvent/CraftItemEvent/SmithItemEvent/CartographyItemEvent via WrapOperation around menu.clicked, offhand resync), handleSetCreativeModeSlot (InventoryCreativeEvent), handleSelectTrade (TradeSelectEvent), handleEditBook (limits, tick throttle, EditBookEvent for write/sign), updateSignText (UncheckedSignChangeEvent, immobile), handleClientInformation (view distance check, locale), handleClientCommand (RespawnReason, hardcore GameMode cause), markClientLoaded/tickClientLoadTimeout (PlayerClientLoadedWorldEvent), handleClientTickEnd (ClientTickEndEvent), bundle select + recipe book settings events; left: perf: tab/recipe spam throttlers; config: parrots-on-shoulder, spawn-protection sign passthrough, prevent-moving-into-unloaded-chunks, updateEquipmentOnPlayerActions, clientInteractionLeniencyDistance; TODO: async tab complete (AsyncTabCompleteEvent + throttling), PlayerPickItemEvent (tryPickItem overload), PlayerFailMoveEvent, allowedPlayerTicks/moved-too-quickly speed multiplier, handlePlaceRecipe (PlayerRecipeBookClickEvent, auto recipe limit), handleConfigurationAcknowledged (PlayerConnectionReconfigureEvent), PlayerUseUnknownEntityEvent, spectator teleport cause, kick-cause variants of the vanilla disconnect() calls (all kick with UNKNOWN), command block minecart/gamerule/gamemode permission checks + gamerule event + GameModeCommand cause, location.isFinite check, 'Fix inventory desync' branches in useItemOn, hasLoggedExpiry log, chat spam exclusions, resetPlayerChatState sync broadcast, signature-cache synchronization)
- [~] net/minecraft/server/network/ServerHandshakePacketListenerImpl.java.patch (mixin present: ServerHandshakePacketListenerImpl; 0 TODO(behaviour))
- [~] net/minecraft/server/network/ServerLoginPacketListenerImpl.java.patch (mixin present: ServerLoginPacketListenerImpl; 0 TODO(behaviour))
- [ ] net/minecraft/server/network/ServerStatusPacketListenerImpl.java.patch
- [~] net/minecraft/server/network/config/PrepareSpawnTask.java.patch (mixin present: PrepareSpawnTask, PrepareSpawnTask_Preparing, PrepareSpawnTask_Ready; 0 TODO(behaviour))
- [~] net/minecraft/server/network/config/ServerCodeOfConductConfigurationTask.java.patch (mixin present: ServerCodeOfConductConfigurationTask; 0 TODO(behaviour))
- [ ] net/minecraft/server/players/BanListEntry.java.patch
- [~] net/minecraft/server/players/CachedUserNameToIdResolver.java.patch (mixin present: CachedUserNameToIdResolver, CachedUserNameToIdResolver_GameProfileInfo; 2 TODO(behaviour))
- [~] net/minecraft/server/players/NameAndId.java.patch (mixin present: NameAndId; 0 TODO(behaviour))
- [ ] net/minecraft/server/players/OldUsersConverter.java.patch
- [~] net/minecraft/server/players/PlayerList.java.patch (mixin present: PlayerList, PlayerList_1; 2 TODO(behaviour))
- [ ] net/minecraft/server/players/ProfileResolver.java.patch
- [ ] net/minecraft/server/players/SleepStatus.java.patch
- [~] net/minecraft/server/players/StoredUserList.java.patch (mixin present: StoredUserList; 0 TODO(behaviour))
- [ ] net/minecraft/server/players/UserBanListEntry.java.patch
- [~] net/minecraft/server/players/UserNameToIdResolver.java.patch (mixin present: UserNameToIdResolver; 0 TODO(behaviour))
- [ ] net/minecraft/server/players/UserWhiteList.java.patch
