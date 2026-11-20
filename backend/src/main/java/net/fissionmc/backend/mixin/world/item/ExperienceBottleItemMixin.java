package net.fissionmc.backend.mixin.world.item;

import net.fissionmc.backend.support.ProjectileLaunch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/ExperienceBottleItem.java.patch (PlayerLaunchProjectileEvent; replaced body of use; the sound and consume move after the event) */
@Mixin(ExperienceBottleItem.class)
public abstract class ExperienceBottleItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void paper$use(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
        final ItemStack itemStack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            // Paper start - PlayerLaunchProjectileEvent
            final net.fissionmc.craft.compat.DelayedProjectile<ThrownExperienceBottle> thrown = ProjectileLaunch.fromRotation(ThrownExperienceBottle::new, serverLevel, itemStack, player, -20.0F, 0.7F, 1.0F);
            final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = ProjectileLaunch.event(player, itemStack, thrown.projectile());
            if (event.callEvent() && thrown.attemptSpawn()) {
                if (event.shouldConsume()) {
                    itemStack.consume(1, player);
                } else {
                    player.containerMenu.forceHeldSlot(hand);
                }
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EXPERIENCE_BOTTLE_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
                player.awardStat(Stats.ITEM_USED.get((ExperienceBottleItem) (Object) this));
            } else {

                player.containerMenu.forceHeldSlot(hand);
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }
            // Paper end - PlayerLaunchProjectileEvent
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
