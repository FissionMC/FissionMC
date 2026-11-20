package net.fissionmc.backend.mixin.world.item;

import net.fissionmc.backend.support.ProjectileLaunch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/WindChargeItem.java.patch (PlayerLaunchProjectileEvent; replaced body of use) */
@Mixin(WindChargeItem.class)
public abstract class WindChargeItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void paper$use(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
        final ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            final net.fissionmc.craft.compat.DelayedProjectile<WindCharge> windCharge = ProjectileLaunch.fromRotation(
                (source, l, itemStack) -> new WindCharge(player, level, player.position().x(), player.getEyePosition().y(), player.position().z()), serverLevel, stack, player, 0.0F, 1.5F, 1.0F);
            // Paper start - PlayerLaunchProjectileEvent
            final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = ProjectileLaunch.event(player, stack, windCharge.projectile());
            if (!event.callEvent() || !windCharge.attemptSpawn()) {
                player.containerMenu.forceHeldSlot(hand);
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundCooldownPacket(player.getCooldowns().getCooldownGroup(stack), 0)); // prevent visual desync of cooldown on the slot
                }
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }
            player.awardStat(Stats.ITEM_USED.get((WindChargeItem) (Object) this));
            if (event.shouldConsume()) {
                stack.consume(1, player);
            } else if (!player.hasInfiniteMaterials()) {
                player.containerMenu.forceHeldSlot(hand);
            }
            // Paper end - PlayerLaunchProjectileEvent
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WIND_CHARGE_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
