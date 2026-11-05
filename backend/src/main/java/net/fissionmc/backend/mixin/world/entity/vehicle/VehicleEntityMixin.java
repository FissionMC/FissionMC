package net.fissionmc.backend.mixin.world.entity.vehicle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/vehicle/VehicleEntity.java.patch */
@Mixin(VehicleEntity.class)
public abstract class VehicleEntityMixin extends Entity {
    @Shadow public abstract void setHurtDir(int hurtDir);
    @Shadow public abstract int getHurtDir();
    @Shadow public abstract void setHurtTime(int hurtTime);
    @Shadow public abstract void setDamage(float damage);
    @Shadow public abstract float getDamage();
    @Shadow protected abstract boolean shouldSourceDestroy(DamageSource source);
    @Shadow protected abstract void destroy(ServerLevel level, DamageSource source);

    protected VehicleEntityMixin(final EntityType<?> type, final Level level) {
        super(type, level);
    }

    // FissionMC: replaced body (VehicleDamageEvent, VehicleDestroyEvent, remove cause)
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void fission$hurtServer(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.fission$hurt(level, source, damage));
    }

    private boolean fission$hurt(final ServerLevel level, final DamageSource source, float damage) { // Paper - unfinal damage
        if (this.isRemoved()) {
            return true;
        }

        if (this.isInvulnerableToBase(source)) {
            return false;
        }

        // CraftBukkit start
        org.bukkit.entity.Vehicle vehicle = (org.bukkit.entity.Vehicle) this.getBukkitEntity();
        org.bukkit.craftbukkit.damage.CraftDamageSource damageSourceBukkit = new org.bukkit.craftbukkit.damage.CraftDamageSource(source);
        org.bukkit.entity.Entity attacker = net.minecraft.Optionull.map(
            source.eventEntityDamager() != null ? source.eventEntityDamager() : source.getDirectEntity(), Entity::getBukkitEntity
        );
        org.bukkit.event.vehicle.VehicleDamageEvent event = new org.bukkit.event.vehicle.VehicleDamageEvent(vehicle, damageSourceBukkit, attacker, damage);
        if (!event.callEvent()) return false;
        damage = (float) event.getDamage();
        // CraftBukkit end
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.markHurt();
        this.setDamage(this.getDamage() + damage * 10.0F);
        this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
        boolean creativePlayer = source.getEntity() instanceof Player player && player.getAbilities().instabuild;
        if ((creativePlayer || !(this.getDamage() > 40.0F)) && !this.shouldSourceDestroy(source)) {
            if (creativePlayer) {
                // CraftBukkit start
                org.bukkit.event.vehicle.VehicleDestroyEvent destroyEvent = new org.bukkit.event.vehicle.VehicleDestroyEvent(vehicle, damageSourceBukkit, attacker);
                if (!destroyEvent.callEvent()) {
                    this.setDamage(40.0F); // Maximize damage so this doesn't get triggered again right away
                    return true;
                }
                // CraftBukkit end
                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
            }
        } else {
            // CraftBukkit start
            org.bukkit.event.vehicle.VehicleDestroyEvent destroyEvent = new org.bukkit.event.vehicle.VehicleDestroyEvent(vehicle, damageSourceBukkit, attacker);
            if (!destroyEvent.callEvent()) {
                this.setDamage(40.0F); // Maximize damage so this doesn't get triggered again right away
                return true;
            }
            // CraftBukkit end
            this.destroy(level, source);
        }

        return true;
    }
}
