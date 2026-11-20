package net.fissionmc.backend.mixin.core.cauldron;

import java.util.function.Predicate;
import net.fissionmc.backend.blocks.PaperCauldrons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.cauldron.CauldronInteractions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason;
import org.bukkit.event.player.PlayerBucketEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/core/cauldron/CauldronInteractions.java.patch (CauldronLevelChangeEvent, PlayerBucketFill/EmptyEvent
 * for cauldrons). Each target body is replicated at HEAD with the event fired before any item/state change, then cancelled.
 * The hit direction Paper threads as a parameter travels through {@link PaperCauldrons#hitDirection}.
 * Lambdas: $0 = EMPTY water potion, $3 = WATER glass bottle, $4 = WATER potion (javap checked against 26.3).
 */
@Mixin(CauldronInteractions.class)
public abstract class CauldronInteractionsMixin {
    @Inject(method = "lambda$bootStrap$0", at = @At("HEAD"), cancellable = true)
    private static void paper$emptyPotion(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand,
                                          final CallbackInfoReturnable<InteractionResult> cir) {
        final PotionContents potion = itemInHand.get(DataComponents.POTION_CONTENTS);
        if (potion == null || !potion.is(Potions.WATER)) {
            return; // vanilla returns TRY_WITH_EMPTY_HAND
        }
        if (!level.isClientSide()) {
            if (!CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, Blocks.WATER_CAULDRON.defaultBlockState(), player, ChangeReason.BOTTLE_EMPTY)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            final Item usedItem = itemInHand.getItem();
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, new ItemStack(Items.GLASS_BOTTLE)));
            player.awardStat(Stats.USE_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(usedItem));
            level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    @Inject(method = "lambda$bootStrap$3", at = @At("HEAD"), cancellable = true)
    private static void paper$waterBottle(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand,
                                          final CallbackInfoReturnable<InteractionResult> cir) {
        if (!level.isClientSide()) {
            if (!PaperCauldrons.lowerFillLevel(state, level, pos, player, ChangeReason.BOTTLE_FILL)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            final Item usedItem = itemInHand.getItem();
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, PotionContents.createItemStack(Items.POTION, Potions.WATER)));
            player.awardStat(Stats.USE_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(usedItem));
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    @Inject(method = "lambda$bootStrap$4", at = @At("HEAD"), cancellable = true)
    private static void paper$waterPotion(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand,
                                          final CallbackInfoReturnable<InteractionResult> cir) {
        if (state.getValue(LayeredCauldronBlock.LEVEL) == 3) {
            return;
        }
        final PotionContents potion = itemInHand.get(DataComponents.POTION_CONTENTS);
        if (potion == null || !potion.is(Potions.WATER)) {
            return;
        }
        if (!level.isClientSide()) {
            if (!CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, state.cycle(LayeredCauldronBlock.LEVEL), player, ChangeReason.BOTTLE_EMPTY)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, new ItemStack(Items.GLASS_BOTTLE)));
            player.awardStat(Stats.USE_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(itemInHand.getItem()));
            level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    @Inject(method = "fillBucket", at = @At("HEAD"), cancellable = true)
    private static void paper$fillBucket(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand,
                                         final ItemStack newItem, final Predicate<BlockState> canFill, final SoundEvent soundEvent, final CallbackInfoReturnable<InteractionResult> cir) {
        if (!canFill.test(state)) {
            cir.setReturnValue(InteractionResult.TRY_WITH_EMPTY_HAND);
            return;
        }
        if (!level.isClientSide()) {
            ItemStack filled = newItem;
            final Direction hitDirection = PaperCauldrons.hitDirection;
            if (hitDirection != null) {
                // Paper - fire PlayerBucketFillEvent
                final PlayerBucketEvent event = CraftEventFactory.callPlayerBucketFillEvent(level, player, pos, pos, hitDirection, itemInHand, newItem.getItem(), hand);
                if (event.isCancelled()) {
                    cir.setReturnValue(InteractionResult.PASS);
                    return;
                }
                filled = event.getItemStack() != null ? CraftItemStack.asNMSCopy(event.getItemStack()) : ItemStack.EMPTY;
            }
            if (!CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, Blocks.CAULDRON.defaultBlockState(), player, ChangeReason.BUCKET_FILL)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            final Item itemUsed = itemInHand.getItem();
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, filled));
            player.awardStat(Stats.USE_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(itemUsed));
            level.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    @Inject(method = "emptyBucket", at = @At("HEAD"), cancellable = true)
    private static void paper$emptyBucket(final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand, final BlockState newState,
                                          final SoundEvent soundEvent, final CallbackInfoReturnable<InteractionResult> cir) {
        if (!level.isClientSide()) {
            ItemStack output = new ItemStack(Items.BUCKET);
            final Direction hitDirection = PaperCauldrons.hitDirection;
            if (hitDirection != null) {
                // Paper - fire PlayerBucketEmptyEvent
                final PlayerBucketEvent event = CraftEventFactory.callPlayerBucketEmptyEvent(level, player, pos, pos, hitDirection, itemInHand, hand);
                if (event.isCancelled()) {
                    cir.setReturnValue(InteractionResult.PASS);
                    return;
                }
                output = event.getItemStack() != null ? CraftItemStack.asNMSCopy(event.getItemStack()) : ItemStack.EMPTY;
            }
            if (!CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, newState, player, ChangeReason.BUCKET_EMPTY)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            final Item itemUsed = itemInHand.getItem();
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, output));
            player.awardStat(Stats.FILL_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(itemUsed));
            level.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    @Inject(method = "shulkerBoxInteraction", at = @At("HEAD"), cancellable = true)
    private static void paper$shulkerWash(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand,
                                          final CallbackInfoReturnable<InteractionResult> cir) {
        if (!(Block.byItem(itemInHand.getItem()) instanceof ShulkerBoxBlock)) {
            return;
        }
        if (!level.isClientSide()) {
            if (!PaperCauldrons.lowerFillLevel(state, level, pos, player, ChangeReason.SHULKER_WASH)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            final ItemStack cleaned = itemInHand.transmuteCopy(Blocks.SHULKER_BOX, 1);
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, cleaned, false));
            player.awardStat(Stats.CLEAN_SHULKER_BOX);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    @Inject(method = "bannerInteraction", at = @At("HEAD"), cancellable = true)
    private static void paper$bannerWash(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand,
                                         final CallbackInfoReturnable<InteractionResult> cir) {
        final BannerPatternLayers patterns = itemInHand.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        if (patterns.layers().isEmpty()) {
            return;
        }
        if (!level.isClientSide()) {
            if (!PaperCauldrons.lowerFillLevel(state, level, pos, player, ChangeReason.BANNER_WASH)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            final ItemStack cleaned = itemInHand.copyWithCount(1);
            cleaned.set(DataComponents.BANNER_PATTERNS, patterns.removeLast());
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, cleaned, false));
            player.awardStat(Stats.CLEAN_BANNER);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    @Inject(method = "dyedItemIteration", at = @At("HEAD"), cancellable = true)
    private static void paper$armorWash(final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand,
                                        final CallbackInfoReturnable<InteractionResult> cir) {
        if (!itemInHand.has(DataComponents.DYED_COLOR)) {
            return;
        }
        if (!level.isClientSide()) {
            if (!PaperCauldrons.lowerFillLevel(state, level, pos, player, ChangeReason.ARMOR_WASH)) {
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            itemInHand.remove(DataComponents.DYED_COLOR);
            player.awardStat(Stats.CLEAN_ARMOR);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
