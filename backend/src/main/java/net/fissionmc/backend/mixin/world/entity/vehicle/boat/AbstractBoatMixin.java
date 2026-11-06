package net.fissionmc.backend.mixin.world.entity.vehicle.boat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/vehicle/boat/AbstractBoat.java.patch */
@Mixin(AbstractBoat.class)
public abstract class AbstractBoatMixin extends VehicleEntity {
    // CraftBukkit start
    // PAIL: Some of these haven't worked since a few updates, and since 1.9 they are less and less applicable.
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private double maxSpeed = 0.4D;
    private double occupiedDeceleration = 0.2D;
    private double unoccupiedDeceleration = -1;
    private boolean landBoats = false;
    private org.bukkit.Location lastLocation;
    // CraftBukkit end

    protected AbstractBoatMixin(final EntityType<?> type, final Level level) {
        super(type, level);
    }

    @Override
    public boolean isCollidable(boolean ignoreClimbing) { // Paper - Climbing should not bypass cramming gamerule
        return true;
    }

    // FissionMC: replaced body (Paper collision option, VehicleEntityCollisionEvent)
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void fission$push(Entity entity, CallbackInfo ci) {
        ci.cancel();
        if (!this.level().paperConfig().collisions.allowVehicleCollisions && this.level().paperConfig().collisions.onlyPlayersCollide && !(entity instanceof Player)) return; // Paper - Collision option for requiring a player participant
        if (entity instanceof AbstractBoat) {
            if (entity.getBoundingBox().minY < this.getBoundingBox().maxY) {
                // CraftBukkit start
                if (!this.isPassengerOfSameVehicle(entity)) {
                    org.bukkit.event.vehicle.VehicleEntityCollisionEvent event = new org.bukkit.event.vehicle.VehicleEntityCollisionEvent(
                        (org.bukkit.entity.Vehicle) this.getBukkitEntity(),
                        entity.getBukkitEntity()
                    );
                    if (!event.callEvent()) return;
                }
                // CraftBukkit end
                super.push(entity);
            }
        } else if (entity.getBoundingBox().minY <= this.getBoundingBox().minY) {
            // CraftBukkit start
            if (!this.isPassengerOfSameVehicle(entity)) {
                org.bukkit.event.vehicle.VehicleEntityCollisionEvent event = new org.bukkit.event.vehicle.VehicleEntityCollisionEvent(
                    (org.bukkit.entity.Vehicle) this.getBukkitEntity(),
                    entity.getBukkitEntity()
                );
                if (!event.callEvent()) return;
            }
            // CraftBukkit end
            super.push(entity);
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;applyEffectsFromBlocks()V", ordinal = 0))
    private void fission$vehicleMove(CallbackInfo ci) {
        // CraftBukkit start
        org.bukkit.Location to = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.position(), this.level(), this.getYRot(), this.getXRot());
        org.bukkit.entity.Vehicle vehicle = (org.bukkit.entity.Vehicle) this.getBukkitEntity();

        new org.bukkit.event.vehicle.VehicleUpdateEvent(vehicle).callEvent();

        if (this.lastLocation != null && !this.lastLocation.equals(to)) {
            org.bukkit.event.vehicle.VehicleMoveEvent event = new org.bukkit.event.vehicle.VehicleMoveEvent(vehicle, this.lastLocation, to);
            event.callEvent();
        }
        this.lastLocation = vehicle.getLocation();
        // CraftBukkit end
    }

    @Redirect(method = "floatBoat", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;setPos(DDD)V"))
    private void fission$floatMove(AbstractBoat instance, double x, double targetY, double z) {
        this.move(MoverType.SELF, new Vec3(0.0, targetY - this.getY(), 0.0)); // Paper - Fix some exploit with boats // TODO Still needed?
    }

    // CraftBukkit start - add Bukkit remove cause
    @Inject(method = "remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("HEAD"), cancellable = true)
    private void fission$removeDelegate(Entity.RemovalReason reason, CallbackInfo ci) {
        if (this.fission$inRemoveDelegate) {
            return;   // re-entered through Entity.remove(reason, cause) -> remove(reason): let the vanilla body run (the unguarded delegate recursed until the stack overflowed and crashed the server)
        }
        ci.cancel();
        this.fission$inRemoveDelegate = true;
        try {
            this.remove(reason, null);
        } finally {
            this.fission$inRemoveDelegate = false;
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean fission$inRemoveDelegate;

    @Override
    public void remove(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause eventCause) {
        AbstractBoat self = (AbstractBoat) (Object) this;
        if (!this.level().isClientSide() && reason.shouldDestroy() && self.isLeashed()) {
            // Paper start - Fire EntityUnleashEvent on boat destruction
            final org.bukkit.event.entity.EntityUnleashEvent event = new org.bukkit.event.entity.EntityUnleashEvent(this.getBukkitEntity(), org.bukkit.event.entity.EntityUnleashEvent.UnleashReason.LEASHED_GONE, true);
            event.callEvent();
            if (event.isDropLeash()) {
                self.dropLeash();
            } else {
                self.removeLeash();
            }
            // Paper end - Fire EntityUnleashEvent on boat destruction
        }

        super.remove(reason, eventCause);
    }
    // CraftBukkit end
}
