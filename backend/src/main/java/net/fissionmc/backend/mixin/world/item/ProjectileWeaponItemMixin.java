package net.fissionmc.backend.mixin.world.item;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/item/ProjectileWeaponItem.java.patch - EntityShootBowEvent in shoot (bows, crossbows, tridents-as-weapons of mods).
 * The draw strength Paper passes through BowItem is derived here: bows shoot with power = strength * 3, everything else reports 1.0.
 */
@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileWeaponItemMixin {
    @Shadow protected abstract Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack projectile, boolean isCrit);
    @Shadow protected abstract void shootProjectile(LivingEntity shooter, Projectile projectileEntity, int index, float power, float uncertainty, float angle, @Nullable LivingEntity targetOverride);
    @Shadow protected abstract int getDurabilityUse(ItemStack projectile);

    @Inject(method = "shoot", at = @At("HEAD"), cancellable = true)
    private void fission$shoot(final ServerLevel level, final LivingEntity shooter, final InteractionHand hand, final ItemStack weapon, final List<ItemStack> projectiles,
                               final float power, final float uncertainty, final boolean isCrit, final @Nullable LivingEntity targetOverride, final CallbackInfo ci) {
        ci.cancel();
        final float drawStrength = (Object) this instanceof BowItem ? Math.min(power / 3.0F, 1.0F) : 1.0F;
        final float maxAngle = EnchantmentHelper.processProjectileSpread(level, weapon, shooter, 0.0F);
        final float angleStep = projectiles.size() == 1 ? 0.0F : 2.0F * maxAngle / (projectiles.size() - 1);
        final float angleOffset = (projectiles.size() - 1) % 2 * angleStep / 2.0F;
        float direction = 1.0F;

        for (int i = 0; i < projectiles.size(); i++) {
            final ItemStack projectile = projectiles.get(i);
            if (projectile.isEmpty()) {
                continue;
            }

            final float angle = angleOffset + direction * ((i + 1) / 2) * angleStep;
            direction = -direction;
            // CraftBukkit start
            final Projectile projectileEntity = this.createProjectile(level, shooter, weapon, projectile, isCrit);
            this.shootProjectile(shooter, projectileEntity, i, power, uncertainty, angle, targetOverride);

            final org.bukkit.event.entity.EntityShootBowEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityShootBowEvent(shooter, weapon, projectile, projectileEntity, hand, drawStrength, true);
            if (event.isCancelled()) {
                event.getProjectile().remove();
                return;
            }

            if (event.getProjectile() == projectileEntity.getBukkitEntity()) {
                if (Projectile.spawnProjectile(projectileEntity, level, projectile).isRemoved()) {
                    return;
                }
            }
            // CraftBukkit end
            weapon.hurtAndBreak(this.getDurabilityUse(projectile), shooter, hand.asEquipmentSlot());
            if (weapon.isEmpty()) {
                break;
            }
        }
    }
}
