package net.fissionmc.backend.support;

import java.util.Optional;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.Level;

/** Paper: sources/net/minecraft/world/entity/Bucketable.java.patch - bucketMobPickup with PlayerBucketEntityEvent. The vanilla static (an interface method, not mixin friendly) is replaced at its four call sites. */
public final class BucketableSupport {
    private BucketableSupport() {
    }

    public static Optional<InteractionResult> bucketMobPickup(final Player player, final InteractionHand hand, final LivingEntity pickupEntity) {
        final Bucketable bucketable = (Bucketable) pickupEntity;
        ItemStack itemStack = player.getItemInHand(hand);
        if (bucketable.canBePickedUpWithBucket(itemStack) && pickupEntity.isAlive()) {
            // CraftBukkit start
            // pickupEntity.playSound(pickupEntity.getPickupSound(), 1.0F, 1.0F); // CraftBukkit - moved down
            ItemStack bucket = bucketable.getBucketItemStack();
            bucketable.saveToBucketTag(bucket);
            org.bukkit.event.player.PlayerBucketEntityEvent playerBucketFishEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerFishBucketEvent(pickupEntity, player, itemStack, bucket, hand);
            bucket = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(playerBucketFishEvent.getEntityBucket());
            if (playerBucketFishEvent.isCancelled()) {
                // Paper start - Fix inventory desync
                if (player.hasInfiniteMaterials() || itemStack.getCount() > 1) {
                    player.containerMenu.sendAllDataToRemote();
                } else {
                    player.containerMenu.forceHeldSlot(hand);
                }
                // Paper end - Fix inventory desync
                pickupEntity.resendPossiblyDesyncedEntityData((ServerPlayer) player); // Paper
                return Optional.of(InteractionResult.FAIL);
            }
            pickupEntity.playSound(bucketable.getPickupSound(), 1.0F, 1.0F);
            // CraftBukkit end
            ItemStack result = ItemUtils.createFilledResult(itemStack, player, bucket, false);
            player.setItemInHand(hand, result);
            Level level = pickupEntity.level();
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                CriteriaTriggers.FILLED_BUCKET.trigger(serverPlayer, bucket);
            }

            if (pickupEntity instanceof Leashable leashable) {
                leashable.dropLeash();
            }

            pickupEntity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
            return Optional.of(InteractionResult.SUCCESS);
        } else {
            return Optional.empty();
        }
    }
}
