package net.fissionmc.backend.mixin.world.entity.projectile.hurtingprojectile.windcharge;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Paper: sources/net/minecraft/world/entity/projectile/hurtingprojectile/windcharge/AbstractWindCharge.java.patch */
@Mixin(AbstractWindCharge.class)
public abstract class AbstractWindChargeMixin extends AbstractHurtingProjectile {
    protected AbstractWindChargeMixin(final EntityType<? extends AbstractHurtingProjectile> type, final Level level) {
        super(type, level);
    }

    @Override
    public void push(final double xa, final double ya, final double za, @Nullable Entity pushingEntity) { // Paper - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
    }

    @Redirect(method = {"onHitBlock", "onHit"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/windcharge/AbstractWindCharge;discard()V"))
    private void fission$discardHit(AbstractWindCharge instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/windcharge/AbstractWindCharge;discard()V"))
    private void fission$discardOutOfWorld(AbstractWindCharge instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.OUT_OF_WORLD); // CraftBukkit - add Bukkit remove cause
    }
}
