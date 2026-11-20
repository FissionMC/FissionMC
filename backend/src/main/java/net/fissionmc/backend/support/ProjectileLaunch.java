package net.fissionmc.backend.support;

import net.fissionmc.craft.compat.DelayedProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Helpers for the Paper PlayerLaunchProjectileEvent hunks of the throwable items (Paper's Projectile.spawnProjectileFromRotationDelayed and the shared use() flow). */
public final class ProjectileLaunch {
    private ProjectileLaunch() {
    }

    /** Paper: Projectile.spawnProjectileFromRotationDelayed. */
    public static <T extends Projectile> DelayedProjectile<T> fromRotation(final Projectile.ProjectileFactory<T> creator, final ServerLevel level, final ItemStack stack,
                                                                         final LivingEntity source, final float yOffset, final float pow, final float uncertainty) {
        final T projectile = creator.create(level, source, stack);
        projectile.shootFromRotation(source, source.getXRot(), source.getYRot(), yOffset, pow, uncertainty);
        return new DelayedProjectile<>(projectile, level, stack);
    }

    public static com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event(final Player player, final ItemStack stack, final Projectile projectile) {
        return new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) player.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(stack), (org.bukkit.entity.Projectile) projectile.getBukkitEntity());
    }

    /** Paper: ThrowablePotionItem.use (the part shared by the splash and lingering potion). Returns FAIL when the event or the spawn was cancelled. */
    public static InteractionResult throwPotion(final net.minecraft.world.item.Item item, final Level level, final Player player, final InteractionHand hand,
                                                final Projectile.ProjectileFactory<? extends net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion> factory) {
        final ItemStack itemStack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            final DelayedProjectile<? extends net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion> thrownPotion = fromRotation(factory, serverLevel, itemStack, player, -20.0F, 0.5F, 1.0F);
            final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = event(player, itemStack, thrownPotion.projectile());
            if (event.callEvent() && thrownPotion.attemptSpawn()) {
                if (event.shouldConsume()) {
                    itemStack.consume(1, player);
                } else {
                    player.containerMenu.forceHeldSlot(hand);
                }
            } else {
                player.containerMenu.forceHeldSlot(hand);
                return InteractionResult.FAIL;
            }
        }
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(item));
        return InteractionResult.SUCCESS;
    }
}
