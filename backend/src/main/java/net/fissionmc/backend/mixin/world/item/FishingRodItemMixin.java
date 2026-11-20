package net.fissionmc.backend.mixin.world.item;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/FishingRodItem.java.patch (PlayerFishEvent FISHING state, hand parameter of retrieve; replaced body of use). */
@Mixin(FishingRodItem.class)
public abstract class FishingRodItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void paper$use(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (player.fishing != null) {
            if (!level.isClientSide()) {
                int dmg = player.fishing.retrieve(itemStack, hand); // Paper - Add hand parameter to PlayerFishEvent
                itemStack.hurtAndBreak(dmg, player, hand.asEquipmentSlot());
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1.0F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
            itemStack.causeUseVibration(player, GameEvent.ITEM_INTERACT_FINISH);
        } else {
            // CraftBukkit - sound moved down
            if (level instanceof ServerLevel serverLevel) {
                int lureSpeed = (int) (EnchantmentHelper.getFishingTimeReduction(serverLevel, itemStack, player) * 20.0F);
                int luck = EnchantmentHelper.getFishingLuckBonus(serverLevel, itemStack, player);
                // CraftBukkit start
                FishingHook fishingHook = new FishingHook(player, level, luck, lureSpeed);
                org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) player.getBukkitEntity(), null, (org.bukkit.entity.FishHook) fishingHook.getBukkitEntity(), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand), org.bukkit.event.player.PlayerFishEvent.State.FISHING);
                if (!playerFishEvent.callEvent()) {
                    player.fishing = null;
                    cir.setReturnValue(InteractionResult.PASS);
                    return;
                }
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
                Projectile.spawnProjectile(fishingHook, serverLevel, itemStack);
                // CraftBukkit end
            }
            player.awardStat(Stats.ITEM_USED.get((FishingRodItem) (Object) this));
            itemStack.causeUseVibration(player, GameEvent.ITEM_INTERACT_START);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
