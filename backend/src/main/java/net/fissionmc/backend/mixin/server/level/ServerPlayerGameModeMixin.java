package net.fissionmc.backend.mixin.server.level;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/level/ServerPlayerGameMode.java.patch */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow private GameType gameModeForPlayer;
    @Shadow @Final protected ServerPlayer player;
    // (ServerPlayer imported above)

    @Shadow public abstract boolean changeGameModeForPlayer(GameType gameModeForPlayer);
    @Shadow protected net.minecraft.server.level.ServerLevel level;
    @Shadow public abstract boolean isCreative();

    // Paper fields (declared in surface/members/player.txt; the loader widens them)
    private boolean captureSentBlockEntities = false; // Paper - Send block entities after destroy prediction
    private boolean capturedBlockEntity = false; // Paper - Send block entities after destroy prediction
    // CraftBukkit start - whole method
    private boolean interactResult = false;
    private boolean firedInteract = false;
    private BlockPos interactPosition;
    private InteractionHand interactHand;
    private ItemStack interactItemStack;
    private org.bukkit.event.player.PlayerInteractEvent fission$leftClickEvent;

    private boolean fission$eventFired;

    // Paper: the vanilla 1-arg method delegates to the event overload (Cause.UNKNOWN); this overload reuses the vanilla body.
    @Inject(method = "changeGameModeForPlayer(Lnet/minecraft/world/level/GameType;)Z", at = @At("HEAD"), cancellable = true)
    private void paper$changeGameModeUnknown(final GameType gameModeForPlayer, final CallbackInfoReturnable<Boolean> cir) {
        if (this.fission$eventFired) {
            return;
        }
        org.bukkit.event.player.PlayerGameModeChangeEvent event = this.changeGameModeForPlayer(gameModeForPlayer, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.UNKNOWN, null);
        cir.setReturnValue(event != null && !event.isCancelled());
    }

    public org.bukkit.event.player.@Nullable PlayerGameModeChangeEvent changeGameModeForPlayer(final GameType gameModeForPlayer, final org.bukkit.event.player.PlayerGameModeChangeEvent.Cause playerGameModeChangeCause, final net.kyori.adventure.text.@Nullable Component cancelMessage) {
        // Paper end - Expand PlayerGameModeChangeEvent
        if (gameModeForPlayer == this.gameModeForPlayer) {
            return null; // Paper - Expand PlayerGameModeChangeEvent
        }
        // CraftBukkit start
        org.bukkit.event.player.PlayerGameModeChangeEvent event = new org.bukkit.event.player.PlayerGameModeChangeEvent(
            this.player.getBukkitEntity(),
            org.bukkit.GameMode.getByValue(gameModeForPlayer.getId()),
            playerGameModeChangeCause,
            cancelMessage
        );
        if (!event.callEvent()) {
            return event; // Paper - Expand PlayerGameModeChangeEvent
        }
        // CraftBukkit end
        this.fission$eventFired = true;
        try {
            this.changeGameModeForPlayer(gameModeForPlayer); // vanilla body (event already fired)
        } finally {
            this.fission$eventFired = false;
        }
        return event; // Paper - Expand PlayerGameModeChangeEvent
    }

    // ---- handleBlockBreakAction (START_DESTROY_BLOCK): PlayerInteractEvent + BlockDamageEvent ----
    // CraftBukkit start - fire PlayerInteractEvent. Deviation: a cancelled event is reported to vanilla as "may not interact"
    // (which also resends the block), Paper simply returns.
    @WrapOperation(method = "handleBlockBreakAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;mayInteract(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean paper$leftClickEvent(final net.minecraft.server.level.ServerLevel level, final net.minecraft.world.entity.Entity entity, final BlockPos pos, final Operation<Boolean> original, @Local(argsOnly = true) final Direction direction) {
        boolean mayInteract = original.call(level, entity, pos);
        org.bukkit.event.player.PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(this.player, org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, pos, direction, this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
        this.fission$leftClickEvent = event;
        if (!mayInteract || event.isCancelled()) {
            this.capturedBlockEntity = true; // Paper - Send block entities after destroy prediction
            return false;
        }
        return true;
    }

    @ModifyExpressionValue(method = "handleBlockBreakAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z", ordinal = 0))
    private boolean paper$denyHitBlock(final boolean isAir) {
        return isAir || (this.fission$leftClickEvent != null && this.fission$leftClickEvent.useInteractedBlock() == org.bukkit.event.Event.Result.DENY); // Paper
    }

    @Inject(method = "handleBlockBreakAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z", ordinal = 1), cancellable = true)
    private void paper$blockDamageEvent(final BlockPos pos, final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action action, final Direction direction, final int maxY, final int sequence, final CallbackInfo ci,
                                        @Local(ordinal = 0) final LocalFloatRef progress) {
        // CraftBukkit start
        // Note that we don't need to resync blocks, block acks will handle it properly for everything but block entities already
        if (this.fission$leftClickEvent != null && this.fission$leftClickEvent.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            ci.cancel();
            return;
        }

        org.bukkit.event.block.BlockDamageEvent blockEvent = CraftEventFactory.callBlockDamageEvent(this.player, pos, direction, this.player.getInventory().getSelectedItem(), progress.get() >= 1.0f); // Paper - Add BlockFace to BlockDamageEvent

        if (blockEvent.isCancelled()) {
            ci.cancel();
            return;
        }

        if (blockEvent.getInstaBreak()) {
            progress.set(2.0f);
        }
        // CraftBukkit end
    }

    @Inject(method = "abortDestroyBlock", at = @At("TAIL"))
    private void paper$blockDamageAbort(final BlockPos pos, final int sequence, final CallbackInfo ci) {
        CraftEventFactory.callBlockDamageAbortEvent(this.player, pos, this.player.getInventory().getSelectedItem()); // CraftBukkit
    }

    // ---- destroyBlock: BlockBreakEvent, BlockDropItemEvent, exp drop (replaced body, Paper's 8-arg playerDestroy not available yet) ----
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void paper$destroyBlock(final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        BlockState state = this.level.getBlockState(pos);
        // CraftBukkit start - fire BlockBreakEvent
        org.bukkit.block.Block bblock = org.bukkit.craftbukkit.block.CraftBlock.at(this.level, pos);
        org.bukkit.event.block.BlockBreakEvent event = null;
        if (this.player instanceof net.minecraft.server.level.ServerPlayer) {
            // Paper start - Ensure BlockBreakEvent only fires for valid adventure breaks - moved up from below
            if (this.player.blockActionRestricted(this.level, pos, this.gameModeForPlayer)) {
                cir.setReturnValue(false);
                return;
            }
            // Paper end - Ensure BlockBreakEvent only fires for valid adventure breaks
            // Sword + Creative mode pre-cancel
            boolean canAttackBlock = !this.player.getMainHandItem().canDestroyBlock(state, this.level, pos, this.player);
            event = new org.bukkit.event.block.BlockBreakEvent(bblock, this.player.getBukkitEntity());

            // Sword + Creative mode pre-cancel
            event.setCancelled(canAttackBlock);

            // Calculate default block experience
            BlockState updatedBlockState = this.level.getBlockState(pos);
            Block block = updatedBlockState.getBlock();

            if (!event.isCancelled() && !this.isCreative() && this.player.hasCorrectToolForDrops(block.defaultBlockState())) {
                ItemStack itemInHand = this.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                event.setExpToDrop(block.getExpDrop(updatedBlockState, this.level, pos, itemInHand, true));
            }

            if (!event.callEvent()) {
                if (canAttackBlock) {
                    cir.setReturnValue(false);
                    return;
                }

                // Block entity data is not reset by the block acks, send after destroy prediction
                if (!this.captureSentBlockEntities) {
                    BlockEntity blockEntity = this.level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        this.player.connection.send(blockEntity.getUpdatePacket());
                    }
                } else {
                    this.capturedBlockEntity = true;
                }
                cir.setReturnValue(false);
                return;
            }
        }
        // CraftBukkit end

        state = this.level.getBlockState(pos); // CraftBukkit - update state from plugins
        if (state.isAir()) {
            cir.setReturnValue(false); // CraftBukkit - A plugin set block to air without cancelling
            return;
        }
        BlockEntity blockEntity = this.level.getBlockEntity(pos);
        Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.GameMasterBlock && !this.player.canUseGameMasterBlocks() && !(block instanceof net.minecraft.world.level.block.CommandBlock && (this.player.isCreative() && this.player.getBukkitEntity().hasPermission("minecraft.commandblock")))) { // Paper - command block permission
            this.level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
            cir.setReturnValue(false);
            return;
        }

        // CraftBukkit start
        org.bukkit.block.BlockState bState = bblock.getState();
        this.level.captureDrops = new java.util.ArrayList<>();
        // CraftBukkit end
        BlockState adjustedState = block.playerWillDestroy(this.level, pos, state, this.player);
        boolean changed = this.level.removeBlock(pos, false);
        if (net.minecraft.SharedConstants.DEBUG_BLOCK_BREAK) {
            LOGGER_DEBUG_BLOCK_BREAK(pos, changed);
        }

        if (changed) {
            block.destroy(this.level, pos, adjustedState);
        }

        ItemStack destroyedWith = null; // Paper - Trigger bee_nest_destroyed trigger in the correct place
        boolean canDestroy = false; // Paper - Trigger bee_nest_destroyed trigger in the correct place

        if (!this.player.preventsBlockDrops()) { // CraftBukkit
            ItemStack itemStack = this.player.getMainHandItem();
            destroyedWith = itemStack.copy(); // Paper
            canDestroy = this.player.hasCorrectToolForDrops(adjustedState); // Paper
            itemStack.mineBlock(this.level, adjustedState, pos, this.player);
            if (changed && canDestroy) {
                // TODO(behaviour): Paper passes event.isDropItems() (and a second flag) into Block#playerDestroy so stats/exhaustion
                // still apply when drops are disabled; the 8-arg overload belongs to the blocks area. Captured drops are discarded below instead.
                block.playerDestroy(this.level, this.player, pos, adjustedState, blockEntity, destroyedWith);
            }
        } // CraftBukkit

        // CraftBukkit start
        java.util.List<net.minecraft.world.entity.item.ItemEntity> itemsToDrop = this.level.captureDrops; // Paper - capture all item additions to the world
        this.level.captureDrops = null; // Paper - capture all item additions to the world; Remove this earlier so that we can actually drop stuff
        if (event != null && event.isDropItems()) {
            CraftEventFactory.handleBlockDropItemEvent(bblock, bState, this.player, itemsToDrop); // Paper - capture all item additions to the world
        }

        // Drop event experience
        if (changed && event != null) {
            block.popExperience(this.level, pos, event.getExpToDrop()); // Paper
        }
        // Paper start - Trigger bee_nest_destroyed trigger in the correct place (check impls of block#playerDestroy)
        if (destroyedWith != null) {
            if (changed && canDestroy && event != null && event.isDropItems() && block instanceof net.minecraft.world.level.block.BeehiveBlock && blockEntity instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity beehiveBlockEntity) { // simulates the guard on block#playerDestroy above
                net.minecraft.advancements.triggers.CriteriaTriggers.BEE_NEST_DESTROYED.trigger(this.player, state, destroyedWith, beehiveBlockEntity.getOccupantCount());
            }
        }
        // Paper end - Trigger bee_nest_destroyed trigger in the correct place
        // CraftBukkit end

        cir.setReturnValue(true);
    }

    private void LOGGER_DEBUG_BLOCK_BREAK(final BlockPos pos, final boolean changed) {
        // vanilla debug-only logging (SharedConstants.DEBUG_BLOCK_BREAK) is not reproduced
    }

    // ---- useItemOn: PlayerInteractEvent (RIGHT_CLICK_BLOCK) ----
    @Inject(method = "useItemOn", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;gameModeForPlayer:Lnet/minecraft/world/level/GameType;", ordinal = 0), cancellable = true)
    private void paper$useItemOnEvent(final net.minecraft.server.level.ServerPlayer player, final Level level, final ItemStack itemStack, final InteractionHand hand, final BlockHitResult hitResult, final CallbackInfoReturnable<InteractionResult> cir) {
        BlockPos pos = hitResult.getBlockPos();
        BlockState state = level.getBlockState(pos);
        boolean cancelledBlock = false;
        boolean cancelledItem = false; // Paper - correctly handle items on cooldown
        if (this.gameModeForPlayer == GameType.SPECTATOR) {
            net.minecraft.world.MenuProvider menuProvider = state.getMenuProvider(level, pos);
            cancelledBlock = menuProvider == null && !(state.getBlock() instanceof net.minecraft.world.level.block.Portal);
        }

        if (player.getCooldowns().isOnCooldown(itemStack)) {
            cancelledItem = true; // Paper - correctly handle items on cooldown
        }
        org.bukkit.event.player.PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, pos, hitResult.getDirection(), itemStack, cancelledBlock, cancelledItem, hand, hitResult.getLocation()); // Paper - correctly handle items on cooldown
        this.firedInteract = true;
        this.interactResult = event.useItemInHand() == org.bukkit.event.Event.Result.DENY;
        this.interactPosition = pos.immutable();
        this.interactHand = hand;
        this.interactItemStack = itemStack.copy();

        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            // Block acks will take care of most of it, just handle some special cases here
            if (state.getBlock() instanceof net.minecraft.world.level.block.CakeBlock) {
                player.getBukkitEntity().sendHealthUpdate(); // SPIGOT-1341 - reset health for cake
            } else if (state.is(net.minecraft.world.level.block.Blocks.JIGSAW) || state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_BLOCK) || state.getBlock() instanceof net.minecraft.world.level.block.CommandBlock) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerClosePacket(this.player.containerMenu.containerId));
            }
            // Paper start - Fix inventory desync; SPIGOT-2867
            if (io.papermc.paper.util.MCUtil.clientPredictsInteraction(this.player, state, itemStack)) {
                this.player.containerMenu.sendAllDataToRemote();
            } else {
                this.player.containerMenu.forceHeldSlot(hand);
            }
            // Paper end - Fix inventory desync
            this.player.resyncUsingItem(this.player); // Paper - Properly cancel usable items
            cir.setReturnValue((event.useItemInHand() != org.bukkit.event.Event.Result.ALLOW) ? InteractionResult.SUCCESS : InteractionResult.PASS);
        }
    }

    // Spigot - add !this.interactResult SPIGOT-764 (replaces the cooldown check)
    @ModifyExpressionValue(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemCooldowns;isOnCooldown(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean paper$useItemOnInteractResult(final boolean onCooldown) {
        return this.interactResult;
    }
}
