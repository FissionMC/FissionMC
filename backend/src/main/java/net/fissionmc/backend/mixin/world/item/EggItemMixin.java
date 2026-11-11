package net.fissionmc.backend.mixin.world.item;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/EggItem.java.patch (PlayerLaunchProjectileEvent; replaced body of use; Projectile.Delayed is net.fissionmc.craft.compat.DelayedProjectile) */
@Mixin(EggItem.class)
public abstract class EggItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void paper$use(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        // Paper start - PlayerLaunchProjectileEvent
        final net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg projectile = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg(serverLevel, player, itemStack);
        projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, EggItem.PROJECTILE_SHOOT_POWER, 1.0F);
        final net.fissionmc.craft.compat.DelayedProjectile<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg> delayed = new net.fissionmc.craft.compat.DelayedProjectile<>(projectile, serverLevel, itemStack);
        com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) player.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemStack), (org.bukkit.entity.Projectile) projectile.getBukkitEntity());
        if (event.callEvent() && delayed.attemptSpawn()) {
            player.awardStat(Stats.ITEM_USED.get((EggItem) (Object) this));
            if (event.shouldConsume()) {
                itemStack.consume(1, player);
            } else {
                player.containerMenu.forceHeldSlot(hand);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EGG_THROW, SoundSource.PLAYERS, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        } else {
            player.containerMenu.forceHeldSlot(hand);
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        // Paper end - PlayerLaunchProjectileEvent
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
