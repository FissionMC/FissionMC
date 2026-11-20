package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/item/BucketItem.java.patch (PlayerBucketFillEvent, PlayerBucketEmptyEvent, result itemstack fix).
 * The extra emptyContents parameters travel through {@link PaperBlocks} (bucketDirection/bucketClicked/bucketItem/bucketHand).
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    // CraftBukkit - set the player context around the empty call in use()
    @WrapOperation(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/BucketItem;emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z"))
    private boolean paper$emptyContentsContext(final BucketItem self, final LivingEntity user, final Level level, final BlockPos placePos, final BlockHitResult hitResult, final Operation<Boolean> original,
                                               @Local(argsOnly = true) final Player player, @Local(argsOnly = true) final InteractionHand hand) {
        PaperBlocks.bucketDirection = hitResult.getDirection();
        PaperBlocks.bucketClicked = hitResult.getBlockPos();
        PaperBlocks.bucketItem = player.getItemInHand(hand);
        PaperBlocks.bucketHand = hand;
        try {
            return original.call(self, user, level, placePos, hitResult);
        } finally {
            PaperBlocks.clearBucketContext();
            // itemLeftInHandAfterPlayerBucketEmptyEvent is consumed by getEmptySuccessItem; reset when it was not (e.g. failed placement)
        }
    }

    // CraftBukkit start - fill: dummy pickup first so the event is not fired when the bucket would not be filled
    @WrapOperation(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack paper$pickupEvent(final BucketPickup pickup, final LivingEntity user, final LevelAccessor accessor, final BlockPos pos, final BlockState state, final Operation<ItemStack> original,
                                        @Local(argsOnly = true) final Level level, @Local(argsOnly = true) final Player player, @Local(argsOnly = true) final InteractionHand hand, @Local final BlockHitResult hit) {
        final ItemStack dummyFluid = original.call(pickup, user, org.bukkit.craftbukkit.util.DummyLevelAccessor.INSTANCE, pos, state);
        if (dummyFluid.isEmpty()) {
            return ItemStack.EMPTY; // Don't fire event if the bucket won't be filled.
        }
        final org.bukkit.event.player.PlayerBucketFillEvent event = CraftEventFactory.callPlayerBucketFillEvent(level, player, pos, pos,
            hit.getDirection(), player.getItemInHand(hand), dummyFluid.getItem(), hand);
        if (event.isCancelled()) {
            player.containerMenu.sendAllDataToRemote(); // SPIGOT-4541
            return ItemStack.EMPTY;
        }
        PaperBlocks.bucketFillResult = CraftItemStack.asNMSCopy(event.getItemStack());
        return original.call(pickup, user, accessor, pos, state);
    }
    // CraftBukkit end

    @ModifyArg(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemUtils;createFilledResult(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"), index = 2)
    private ItemStack paper$fillResult(final ItemStack taken) {
        final ItemStack result = PaperBlocks.bucketFillResult;
        PaperBlocks.bucketFillResult = null;
        return result != null ? result : taken;
    }

    // CraftBukkit start - PlayerBucketEmptyEvent once the fluid can be placed
    @Inject(method = "emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;environmentAttributes()Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;", ordinal = 0), cancellable = true)
    private void paper$bucketEmptyEvent(final LivingEntity user, final Level level, final BlockPos pos, final BlockHitResult hitResult, final CallbackInfoReturnable<Boolean> cir) {
        if (user instanceof Player player && PaperBlocks.bucketItem != null) {
            final org.bukkit.event.player.PlayerBucketEmptyEvent event = CraftEventFactory.callPlayerBucketEmptyEvent(level, player, pos, PaperBlocks.bucketClicked, PaperBlocks.bucketDirection, PaperBlocks.bucketItem, PaperBlocks.bucketHand);
            if (event.isCancelled()) {
                player.containerMenu.sendAllDataToRemote(); // SPIGOT-4541
                cir.setReturnValue(false);
                return;
            }
            // Paper - Fix PlayerBucketEmptyEvent result itemstack
            PaperBlocks.itemLeftInHandAfterPlayerBucketEmptyEvent = event.getItemStack() != null
                ? (event.getItemStack().equals(org.bukkit.inventory.ItemStack.of(org.bukkit.Material.BUCKET)) ? null : CraftItemStack.asNMSCopy(event.getItemStack()))
                : ItemStack.EMPTY;
        }
    }
    // CraftBukkit end

    // Paper - clear the left-in-hand item when the placement fails
    @Inject(method = "emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z", at = @At("RETURN"))
    private void paper$clearLeftOnFail(final LivingEntity user, final Level level, final BlockPos pos, final BlockHitResult hitResult, final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            PaperBlocks.itemLeftInHandAfterPlayerBucketEmptyEvent = null;
        }
    }

    // Paper - Fix PlayerBucketEmptyEvent result itemstack
    @Inject(method = "getEmptySuccessItem", at = @At("HEAD"), cancellable = true)
    private static void paper$emptySuccessItem(final ItemStack itemStack, final Player player, final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack left = PaperBlocks.itemLeftInHandAfterPlayerBucketEmptyEvent;
        if (left != null) {
            PaperBlocks.itemLeftInHandAfterPlayerBucketEmptyEvent = null;
            cir.setReturnValue(left);
        }
    }
}
