package net.fissionmc.backend.mixin.world.entity.projectile.throwableitemprojectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownLingeringPotion.java.patch */
@Mixin(ThrownLingeringPotion.class)
public abstract class ThrownLingeringPotionMixin extends AbstractThrownPotion {
    protected ThrownLingeringPotionMixin(final EntityType<? extends AbstractThrownPotion> type, final Level level) {
        super(type, level);
    }

    // FissionMC: Paper's onHitAsPotion (boolean, nullable HitResult); called from AbstractThrownPotionMixin.splash
    public boolean fission$onHitAsPotion(final ServerLevel level, final ItemStack potionItem, final @Nullable HitResult hitResult) { // Paper - More projectile API
        AreaEffectCloud cloud;
        if (hitResult instanceof EntityHitResult entityHitResult) {
            cloud = new AreaEffectCloud(this.level(), entityHitResult.getEntity().getX(), entityHitResult.getEntity().getY(), entityHitResult.getEntity().getZ());
        } else {
            cloud = new AreaEffectCloud(this.level(), this.getX(), this.getY(), this.getZ());
        }

        if (this.getOwner() instanceof LivingEntity livingEntity) {
            cloud.setOwner(livingEntity);
        }

        cloud.setRadius(3.0F);
        cloud.setRadiusOnUse(-0.5F);
        cloud.setDuration(600);
        cloud.setWaitTime(10);
        cloud.setRadiusPerTick(-cloud.getRadius() / cloud.getDuration());
        cloud.applyComponentsFromItemStack(potionItem);
        boolean noEffects = !this.getItem().getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS, net.minecraft.world.item.alchemy.PotionContents.EMPTY).hasEffects(); // Paper - Fix potions splash events
        // CraftBukkit start
        org.bukkit.event.entity.LingeringPotionSplashEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callLingeringPotionSplashEvent((ThrownLingeringPotion) (Object) this, hitResult, cloud);
        if (!(event.isCancelled() || cloud.isRemoved() || (!event.allowsEmptyCreation() && (noEffects && !cloud.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS).hasEffects())))) { // Paper - don't spawn area effect cloud if the effects were empty and not changed during the event handling
        level.addFreshEntity(cloud);
        } else {
            cloud.discard(null);
        }
        // CraftBukkit end
        return !event.isCancelled(); // Paper - Fix potions splash events
    }
}
