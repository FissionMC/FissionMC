package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.support.ProjectileLaunch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/TridentItem.java.patch - PlayerRiptideEvent and the PlayerLaunchProjectileEvent throw hunk (damage moves after the spawn onto the thrown copy, consume after the event). */
@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
    @Inject(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V"), cancellable = true)
    private void paper$riptideEvent(final ItemStack itemStack, final net.minecraft.world.level.Level level, final net.minecraft.world.entity.LivingEntity entity, final int remainingTime,
                                    final CallbackInfoReturnable<Boolean> cir, @Local(ordinal = 2) final float xd, @Local(ordinal = 3) final float yd, @Local(ordinal = 4) final float zd) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerRiptideEvent((Player) entity, itemStack, xd, yd, zd)) {
            cir.setReturnValue(false);
        }
    }

    // CraftBukkit - the damage call is moved down for the throw (riptide keeps it here, SPIGOT-5458)
    @WrapWithCondition(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hurtWithoutBreaking(ILnet/minecraft/world/entity/player/Player;)V"))
    private boolean paper$moveDamageDown(final ItemStack itemStack, final int amount, final Player player, @Local(ordinal = 0) final float riptideStrength) {
        return riptideStrength != 0.0F;
    }

    @Inject(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;consumeAndReturn(ILnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"), cancellable = true)
    private void paper$throwEvent(final ItemStack itemStack, final net.minecraft.world.level.Level level, final net.minecraft.world.entity.LivingEntity entity, final int remainingTime,
                                  final CallbackInfoReturnable<Boolean> cir) {
        final Player player = (Player) entity;
        final ServerLevel serverLevel = (ServerLevel) level;
        final ItemStack thrownItemStack = itemStack.copyWithCount(1); // Paper
        final net.fissionmc.craft.compat.DelayedProjectile<ThrownTrident> tridentDelayed = ProjectileLaunch.fromRotation(ThrownTrident::new, serverLevel, thrownItemStack, player, 0.0F, 2.5F, 1.0F);
        // Paper start - PlayerLaunchProjectileEvent
        final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = ProjectileLaunch.event(player, itemStack, tridentDelayed.projectile());
        if (!event.callEvent() || !tridentDelayed.attemptSpawn()) {
            cir.setReturnValue(false);
            return;
        }
        final ThrownTrident trident = tridentDelayed.projectile();
        if (event.shouldConsume()) {
            thrownItemStack.hurtWithoutBreaking(1, player); // Paper - pickup item damage
        }
        trident.setPickupItemStack(thrownItemStack.copy()); // SPIGOT-4511 update since damage call moved - count = 1
        if (event.shouldConsume()) {
            itemStack.consume(1, player);
        }
        // Paper end - PlayerLaunchProjectileEvent
        if (player.hasInfiniteMaterials()) {
            trident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        }
        final net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound = EnchantmentHelper.pickHighestLevel(itemStack, EnchantmentEffectComponents.TRIDENT_SOUND).orElse(SoundEvents.TRIDENT_THROW);
        level.playSound(null, trident, sound.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
        cir.setReturnValue(true);
    }
}
