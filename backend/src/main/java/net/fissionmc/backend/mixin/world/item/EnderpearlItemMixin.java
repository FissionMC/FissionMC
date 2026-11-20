package net.fissionmc.backend.mixin.world.item;

import net.fissionmc.backend.support.ProjectileLaunch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/EnderpearlItem.java.patch (PlayerLaunchProjectileEvent; replaced body of use; the sound and consume move after the event) */
@Mixin(EnderpearlItem.class)
public abstract class EnderpearlItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void paper$use(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
        final ItemStack itemStack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            // Paper start - PlayerLaunchProjectileEvent
            final net.fissionmc.craft.compat.DelayedProjectile<ThrownEnderpearl> thrown = ProjectileLaunch.fromRotation(ThrownEnderpearl::new, serverLevel, itemStack, player, 0.0F, 1.5F, 1.0F);
            final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = ProjectileLaunch.event(player, itemStack, thrown.projectile());
            if (event.callEvent() && thrown.attemptSpawn()) {
                if (event.shouldConsume()) {
                    itemStack.consume(1, player);
                } else {
                    player.containerMenu.forceHeldSlot(hand);
                }
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDER_PEARL_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
                player.awardStat(Stats.ITEM_USED.get((EnderpearlItem) (Object) this));
            } else {
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    serverPlayer.deregisterEnderPearl(thrown.projectile());
                    serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundCooldownPacket(player.getCooldowns().getCooldownGroup(itemStack), 0)); // prevent visual desync of cooldown on the slot
                }
                player.containerMenu.forceHeldSlot(hand);
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }
            // Paper end - PlayerLaunchProjectileEvent
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
