package net.fissionmc.backend.mixin.world.item;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.fissionmc.backend.blocks.PaperBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/ItemStack.java.patch */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Shadow @Final @Mutable private Holder<Item> item;
    @Shadow @Final @Mutable private PatchedDataComponentMap components;

    @Shadow public abstract DataComponentPatch getComponentsPatch();
    @Shadow public abstract void applyComponents(DataComponentPatch patch);

    // CraftBukkit start
    public void restorePatch(DataComponentPatch datacomponentpatch) {
        this.components.restorePatch(datacomponentpatch);
    }
    // CraftBukkit end

    // CraftBukkit start
    @Deprecated
    public void setItem(Item item) {
        this.item = item.builtInRegistryHolder();
        // Paper start - change base component prototype
        final DataComponentPatch patch = this.getComponentsPatch();
        this.components = new PatchedDataComponentMap(this.item.components());
        this.applyComponents(patch);
        // Paper end - change base component prototype
    }
    // CraftBukkit end

    @Shadow public abstract ItemStack copy();
    @Shadow public abstract Item getItem();
    @Shadow public abstract int getCount();
    @Shadow public abstract void setCount(int count);
    @Shadow public abstract boolean canPlaceOnBlockInAdventureMode(BlockInWorld blockInWorld);
    @Shadow private ItemStack applyAfterUseComponentSideEffects(LivingEntity user, ItemStack stackBeforeUse) { throw new AssertionError(); }
    @Shadow public abstract boolean isDamageableItem();
    @Shadow public abstract int getDamageValue();
    @Shadow public abstract int getMaxDamage();
    @Shadow public abstract void setDamageValue(int value);
    @Shadow public abstract boolean isBroken();
    @Shadow public abstract void shrink(int amount);

    // CraftBukkit start - handle all block place event logic here
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void fission$useOn(final UseOnContext context, final CallbackInfoReturnable<InteractionResult> cir) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final ItemStack self = (ItemStack) (Object) this;
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        if (player != null && !player.getAbilities().mayBuild && !this.canPlaceOnBlockInAdventureMode(new BlockInWorld(context.getLevel(), pos, false))) {
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }
        ItemStack stackBeforeUse = this.copy();
        Item usedItem = this.getItem();
        DataComponentPatch previousPatch = this.components.asPatch();
        int previousCount = this.getCount();
        boolean isBonemeal = usedItem == Items.BONE_MEAL;

        if (!(usedItem instanceof BucketItem)) {
            level.captureBlockStates = true;
            level.captureTreeGeneration = isBonemeal;
        }

        List<org.bukkit.craftbukkit.block.CraftBlockState> capturedBlockStates;
        List<net.minecraft.world.level.block.entity.BlockEntity> capturedBlockEntities;
        org.bukkit.TreeType treeType;
        InteractionResult result;
        BlockPos placedPos; // Paper - track placed block position from block item
        PaperBlocks.placedPos = null;
        try {
            result = usedItem.useOn(context);
        } finally {
            level.captureBlockStates = false;
            level.captureTreeGeneration = false;

            capturedBlockStates = new java.util.ArrayList<>(level.capturedBlockStates.values());
            level.capturedBlockStates.clear();

            capturedBlockEntities = new java.util.ArrayList<>(level.capturedBlockEntities.values());
            level.capturedBlockEntities.clear();

            treeType = net.minecraft.world.level.block.SaplingBlock.treeType;
            net.minecraft.world.level.block.SaplingBlock.treeType = null;
            placedPos = PaperBlocks.placedPos;
            PaperBlocks.placedPos = null;
        }

        DataComponentPatch newPatch = this.components.asPatch();
        int newCount = this.getCount();
        this.setCount(previousCount);
        this.restorePatch(previousPatch);

        if (result.consumesAction() && isBonemeal && !capturedBlockStates.isEmpty()) {
            org.bukkit.Location location = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level);
            org.bukkit.event.world.StructureGrowEvent structureEvent = null;
            if (treeType != null) {
                structureEvent = new org.bukkit.event.world.StructureGrowEvent(location, treeType, true, player == null ? null : (org.bukkit.entity.Player) player.getBukkitEntity(), (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
                org.bukkit.Bukkit.getPluginManager().callEvent(structureEvent);
            }

            org.bukkit.event.block.BlockFertilizeEvent fertilizeEvent = new org.bukkit.event.block.BlockFertilizeEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), player == null ? null : (org.bukkit.entity.Player) player.getBukkitEntity(), (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
            fertilizeEvent.setCancelled(structureEvent != null && structureEvent.isCancelled());
            org.bukkit.Bukkit.getPluginManager().callEvent(fertilizeEvent);

            if (!fertilizeEvent.isCancelled()) {
                // Change the stack to its new contents if it hasn't been tampered with.
                if (this.getCount() == previousCount && Objects.equals(this.components.asPatch(), previousPatch)) {
                    this.restorePatch(newPatch);
                    this.setCount(newCount);
                }
                for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                    // SPIGOT-7572 - Move fix for SPIGOT-7248 to CapturedBlockState, to allow bees in bee nest
                    snapshot.place(snapshot.getFlags());
                    level.checkCapturedTreeStateForObserverNotify(pos, snapshot); // Paper - notify observers even if grow failed
                }
                if (player != null) player.awardStat(Stats.ITEM_USED.get(usedItem)); // SPIGOT-7236 - award stat
            }
            cir.setReturnValue(result);
            return;
        }

        if (player != null && result instanceof InteractionResult.Success success && success.wasItemInteraction()) {
            InteractionHand hand = context.getHand();
            org.bukkit.event.block.BlockPlaceEvent placeEvent = null;
            if (capturedBlockStates.size() > 1) {
                placeEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockMultiPlaceEvent(level, player, hand, (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates, pos);
            } else if (capturedBlockStates.size() == 1 && usedItem != Items.POWDER_SNOW_BUCKET) { // Paper - Fix cancelled powdered snow bucket placement
                placeEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockPlaceEvent(level, player, hand, capturedBlockStates.getFirst(), pos);
            }

            if (placeEvent != null && (placeEvent.isCancelled() || !placeEvent.canBuild())) {
                result = InteractionResult.FAIL; // cancel placement
                // PAIL: Remove this when MC-99075 fixed
                player.containerMenu.forceHeldSlot(hand);
                // revert back all captured blocks
                for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                    snapshot.revertPlace(true); // poi updates needs to be skipped since the poi were never registered as part of the capture
                }
            } else {
                // Change the stack to its new contents if it hasn't been tampered with.
                if (this.getCount() == previousCount && Objects.equals(this.components.asPatch(), previousPatch)) {
                    this.restorePatch(newPatch);
                    this.setCount(newCount);
                }

                for (net.minecraft.world.level.block.entity.BlockEntity blockEntity : capturedBlockEntities) {
                    level.setBlockEntity(blockEntity);
                }

                for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                    net.minecraft.world.level.block.state.BlockState oldBlock = snapshot.getHandle();
                    BlockPos newPos = snapshot.getPosition();
                    net.minecraft.world.level.block.state.BlockState block = level.getBlockState(newPos);

                    if (!(block.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock)) { // Containers get placed automatically
                        PaperBlocks.onPlaceContext = context; // FissionMC: Paper's onPlace(..., context) overload
                        try {
                            block.onPlace(level, newPos, oldBlock, true);
                        } finally {
                            PaperBlocks.onPlaceContext = null;
                        }
                    }

                    level.notifyAndUpdatePhysics(newPos, null, oldBlock, block, level.getBlockState(newPos), snapshot.getFlags(), net.minecraft.world.level.block.Block.UPDATE_LIMIT); // send null chunk as chunk.k() returns false by this point
                }

                if (usedItem == Items.WITHER_SKELETON_SKULL && placedPos != null) { // Special case skulls to allow wither spawns to be cancelled
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(placedPos);
                    if (blockEntity instanceof net.minecraft.world.level.block.entity.SkullBlockEntity placedSkull) {
                        net.minecraft.world.level.block.WitherSkullBlock.checkSpawn(level, placedPos, placedSkull);
                    }
                }

                if (placedPos != null) {
                    if (level.getBlockState(placedPos).getBlock() instanceof net.minecraft.world.level.block.SignBlock sign
                        && level.getBlockEntity(placedPos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity signEntity
                        && !signEntity.isWaxed()
                        && signEntity.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).hasEditableText(player.isTextFilteringEnabled())
                    ) {
                        sign.openTextEdit(player, signEntity, net.minecraft.world.level.block.entity.SignTextSlot.FRONT, io.papermc.paper.event.player.PlayerOpenSignEvent.Cause.PLACE);
                    }
                }

                if (usedItem instanceof BlockItem blockItem && placedPos != null) {
                    BlockState placedState = level.getBlockState(placedPos);
                    net.minecraft.world.level.block.SoundType soundType = placedState.getSoundType();
                    level.playSound(player, pos, ((BlockItemAccessorMixin) blockItem).fission$getPlaceSound(placedState), net.minecraft.sounds.SoundSource.BLOCKS, (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
                }

                player.awardStat(Stats.ITEM_USED.get(usedItem));
                ItemStack transformTo = success.heldItemTransformedTo() == null ? self : success.heldItemTransformedTo();
                ItemStack resultItemStack = ((ItemStackMixin) (Object) transformTo).applyAfterUseComponentSideEffects(player, stackBeforeUse);
                cir.setReturnValue(success.heldItemTransformedTo(resultItemStack));
                return;
            }
            cir.setReturnValue(result);
            return;
            // CraftBukkit end
        }
        cir.setReturnValue(result);
    }

    // Paper start - add force boolean overload
    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V", at = @At("HEAD"), cancellable = true)
    private void fission$hurtAndBreak(final int amount, final ServerLevel level, final @org.jspecify.annotations.Nullable ServerPlayer player, final Consumer<ItemStack> onBreak, final CallbackInfo ci) {
        this.hurtAndBreak(amount, level, (LivingEntity) player, onBreak, false);
        ci.cancel();
    }

    public void hurtAndBreak(final int amount, final ServerLevel level, final @org.jspecify.annotations.Nullable LivingEntity player, final Consumer<ItemStack> onBreak, final boolean force) { // Paper - Add EntityDamageItemEvent
        // Paper end
        final int originalDamage = amount; // Paper - Expand PlayerItemDamageEvent
        int newAmount = this.fission$processDurabilityChange(amount, level, player, force); // Paper
        // CraftBukkit start
        if (newAmount > 0 && player instanceof final ServerPlayer serverPlayer) { // Paper - Add EntityDamageItemEvent - limit to positive damage and run for player
            org.bukkit.event.player.PlayerItemDamageEvent event = new org.bukkit.event.player.PlayerItemDamageEvent(serverPlayer.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror((ItemStack) (Object) this), newAmount, originalDamage); // Paper - Add EntityDamageItemEvent
            event.getPlayer().getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }

            newAmount = event.getDamage();
            // Paper start - Add EntityDamageItemEvent
        } else if (newAmount > 0 && player != null) {
            io.papermc.paper.event.entity.EntityDamageItemEvent event = new io.papermc.paper.event.entity.EntityDamageItemEvent(player.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror((ItemStack) (Object) this), newAmount);
            if (!event.callEvent()) {
                return;
            }
            newAmount = event.getDamage();
            // Paper end - Add EntityDamageItemEvent
        }
        // CraftBukkit end
        if (newAmount != 0) { // Paper - Add EntityDamageItemEvent - diff on change for above event ifs.
            this.fission$applyDamage(this.getDamageValue() + newAmount, player, onBreak);
        }
    }

    private int fission$processDurabilityChange(final int amount, final ServerLevel level, final @org.jspecify.annotations.Nullable LivingEntity player, final boolean force) {
        // Paper end - itemstack damage api
        if (!this.isDamageableItem()) {
            return 0;
        } else if (player instanceof ServerPlayer && player.hasInfiniteMaterials() && !force) { // Paper - Add EntityDamageItemEvent
            return 0;
        } else {
            return amount > 0 ? net.minecraft.world.item.enchantment.EnchantmentHelper.processDurabilityChange(level, (ItemStack) (Object) this, amount) : amount;
        }
    }

    private void fission$applyDamage(final int newDamage, final @org.jspecify.annotations.Nullable LivingEntity player, final Consumer<ItemStack> onBreak) { // Paper - Add EntityDamageItemEvent
        if (player instanceof final ServerPlayer serverPlayer) { // Paper - Add EntityDamageItemEvent
            net.minecraft.advancements.triggers.CriteriaTriggers.ITEM_DURABILITY_CHANGED.trigger(serverPlayer, (ItemStack) (Object) this, newDamage); // Paper - Add EntityDamageItemEvent
        }

        this.setDamageValue(newDamage);
        if (this.isBroken()) {
            ItemStack broken = this.copy();
            // CraftBukkit start - Check for item breaking
            if (this.getCount() == 1 && player instanceof final ServerPlayer serverPlayer) { // Paper - Add EntityDamageItemEvent
                org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerItemBreakEvent(serverPlayer, (ItemStack) (Object) this); // Paper - Add EntityDamageItemEvent
            }
            // CraftBukkit end
            this.shrink(1);
            onBreak.accept(broken);
        }
    }

    // Paper start - Expand PlayerItemDamageEvent (replaced body)
    @Inject(method = "hurtWithoutBreaking", at = @At("HEAD"), cancellable = true)
    private void fission$hurtWithoutBreaking(final int amount, final Player player, final CallbackInfo ci) {
        ci.cancel();
        if (player instanceof ServerPlayer serverPlayer) {
            int newAmount = this.fission$processDurabilityChange(amount, serverPlayer.level(), serverPlayer, false);
            if (newAmount == 0) {
                return;
            }

            int newDamage = Math.min(this.getDamageValue() + newAmount, this.getMaxDamage() - 1); // Paper - Expand PlayerItemDamageEvent - diff on change as min computation is copied post event.

            // Paper start - Expand PlayerItemDamageEvent
            if (newDamage - this.getDamageValue() > 0) {
                org.bukkit.event.player.PlayerItemDamageEvent event = new org.bukkit.event.player.PlayerItemDamageEvent(
                    serverPlayer.getBukkitEntity(),
                    org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror((ItemStack) (Object) this),
                    newDamage - this.getDamageValue(),
                    amount
                );
                if (!event.callEvent() || event.getDamage() == 0) {
                    return;
                }

                // Prevent breaking the item in this code path as callers may expect the item to survive
                // (given the method name)
                newDamage = Math.min(this.getDamageValue() + event.getDamage(), this.getMaxDamage() - 1);
            }
            // Paper end - Expand PlayerItemDamageEvent

            this.fission$applyDamage(newDamage, serverPlayer, i -> {});
        }
    }

    // Paper - Add EntityDamageItemEvent: pass the LivingEntity owner (not only players)
    @Inject(method = "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V", at = @At("HEAD"), cancellable = true)
    private void fission$hurtAndBreakSlot(final int amount, final LivingEntity owner, final EquipmentSlot slot, final CallbackInfo ci) {
        this.hurtAndBreak(amount, owner, slot, false);
        ci.cancel();
    }

    // Paper start - add param to skip infinite mats check
    public void hurtAndBreak(final int amount, final LivingEntity owner, final EquipmentSlot slot, final boolean force) {
        // Paper end - add param to skip infinite mats check
        if (owner.level() instanceof ServerLevel serverLevel) {
            this.hurtAndBreak(
                amount, serverLevel, owner, brokenItem -> {if (slot != null) owner.onEquippedItemBroken(brokenItem, slot); }, force // Paper - Add EntityDamageItemEvent & itemstack damage API - do not process entity related callbacks when damaging from API
            );
        }
    }
}
