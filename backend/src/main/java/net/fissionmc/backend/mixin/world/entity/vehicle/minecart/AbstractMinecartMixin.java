package net.fissionmc.backend.mixin.world.entity.vehicle.minecart;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java.patch */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin extends VehicleEntity {

    // CraftBukkit start
    // Surface: slowWhenEmpty, maxSpeed, frictionState are public (see surface/members/entitytypes.txt); the loader widens them.
    private boolean slowWhenEmpty = true;
    private double derailedX = 0.5;
    private double derailedY = 0.5;
    private double derailedZ = 0.5;
    private double flyingX = this.getAirDrag();
    private double flyingY = this.getAirDrag();
    private double flyingZ = this.getAirDrag();
    private @Nullable Double maxSpeed;
    private net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API
    // CraftBukkit end

    @Shadow protected abstract double getMaxSpeed(ServerLevel level);

    // CraftBukkit - previous position for VehicleMoveEvent
    private double fission$prevX, fission$prevY, fission$prevZ;
    private float fission$prevYaw, fission$prevPitch;

    protected AbstractMinecartMixin(final EntityType<?> type, final Level level) {
        super(type, level);
    }

    @Inject(method = "canCollideWith", at = @At("RETURN"), cancellable = true)
    private void fission$collisionEvent(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // Paper start - fix VehicleEntityCollisionEvent not called when colliding with player
        if (!cir.getReturnValueZ()) {
            return;
        }

        org.bukkit.event.vehicle.VehicleEntityCollisionEvent collisionEvent = new org.bukkit.event.vehicle.VehicleEntityCollisionEvent((org.bukkit.entity.Vehicle) this.getBukkitEntity(), entity.getBukkitEntity());
        cir.setReturnValue(collisionEvent.callEvent());
        // Paper end - fix VehicleEntityCollisionEvent not called when colliding with player
    }

    @Override
    public boolean isCollidable(boolean ignoreClimbing) { // Paper - Climbing should not bypass cramming gamerule
        return true;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fission$capturePrev(CallbackInfo ci) {
        // CraftBukkit start
        this.fission$prevX = this.getX();
        this.fission$prevY = this.getY();
        this.fission$prevZ = this.getZ();
        this.fission$prevYaw = this.getYRot();
        this.fission$prevPitch = this.getXRot();
        // CraftBukkit end
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;handlePortal()V"))
    private void fission$noPortalInTick(AbstractMinecart instance) {
        // this.handlePortal(); // CraftBukkit - handled in postTick
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/MinecartBehavior;tick()V", shift = At.Shift.AFTER))
    private void fission$vehicleMove(CallbackInfo ci) {
        // CraftBukkit start
        org.bukkit.World bworld = this.level().getWorld();
        org.bukkit.Location from = new org.bukkit.Location(bworld, this.fission$prevX, this.fission$prevY, this.fission$prevZ, this.fission$prevYaw, this.fission$prevPitch);
        org.bukkit.Location to = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.position(), this.level(), this.getYRot(), this.getXRot());
        org.bukkit.entity.Vehicle vehicle = (org.bukkit.entity.Vehicle) this.getBukkitEntity();

        new org.bukkit.event.vehicle.VehicleUpdateEvent(vehicle).callEvent();

        if (!from.equals(to)) {
            new org.bukkit.event.vehicle.VehicleMoveEvent(vehicle, from, to).callEvent();
        }
        // CraftBukkit end
    }

    @Inject(method = "comeOffTrack", at = @At("HEAD"), cancellable = true)
    private void fission$comeOffTrack(ServerLevel level, CallbackInfo ci) {
        ci.cancel();
        double maxSpeed = this.getMaxSpeed(level);
        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(Mth.clamp(movement.x, -maxSpeed, maxSpeed), movement.y, Mth.clamp(movement.z, -maxSpeed, maxSpeed));
        if (this.onGround()) {
            // CraftBukkit start - replace magic numbers with our variables
            this.setDeltaMovement(new Vec3(this.getDeltaMovement().x * this.derailedX, this.getDeltaMovement().y * this.derailedY, this.getDeltaMovement().z * this.derailedZ));
            // CraftBukkit end
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        if (!this.onGround()) {
            // CraftBukkit start - replace magic numbers with our variables
            this.setDeltaMovement(new Vec3(this.getDeltaMovement().x * this.flyingX, this.getDeltaMovement().y * this.flyingY, this.getDeltaMovement().z * this.flyingZ));
            // CraftBukkit end
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void fission$readFriction(ValueInput input, CallbackInfo ci) {
        // Paper start - Friction API
        input.getString("Paper.FrictionState").ifPresent(frictionState -> {
            try {
                this.frictionState = net.kyori.adventure.util.TriState.valueOf(frictionState);
            } catch (Exception ignored) {
                com.mojang.logging.LogUtils.getLogger().error("Unknown friction state {} for {}", frictionState, this);
            }
        });
        // Paper end - Friction API
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void fission$saveFriction(ValueOutput output, CallbackInfo ci) {
        // Paper start - Friction API
        if (this.frictionState != net.kyori.adventure.util.TriState.NOT_SET) {
            output.putString("Paper.FrictionState", this.frictionState.toString());
        }
        // Paper end - Friction API
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;hasPassenger(Lnet/minecraft/world/entity/Entity;)Z"), cancellable = true)
    private void fission$pushEvents(Entity entity, CallbackInfo ci) {
        if (!this.level().paperConfig().collisions.allowVehicleCollisions && this.level().paperConfig().collisions.onlyPlayersCollide && !(entity instanceof Player)) { ci.cancel(); return; } // Paper - Collision option for requiring a player participant
        if (!this.hasPassenger(entity)) {
            // Paper start - Collision Events
            if (io.papermc.paper.event.entity.EntityCollideWithEntityEvent.getHandlerList().getRegisteredListeners().length != 0 &&
                    !new io.papermc.paper.event.entity.EntityCollideWithEntityEvent(this.getBukkitEntity(), entity.getBukkitEntity()).callEvent()) { ci.cancel(); return; }

            org.bukkit.event.vehicle.VehicleEntityCollisionEvent collisionEvent = new org.bukkit.event.vehicle.VehicleEntityCollisionEvent(
                (org.bukkit.entity.Vehicle) this.getBukkitEntity(),
                entity.getBukkitEntity()
            );
            if (!collisionEvent.callEvent()) { ci.cancel(); return; }
            // Paper end - Collision Events
        }
    }

    // CraftBukkit start - Methods for getting and setting flying and derailed velocity modifiers
    public org.bukkit.util.Vector getFlyingVelocityMod() {
        return new org.bukkit.util.Vector(this.flyingX, this.flyingY, this.flyingZ);
    }

    public void setFlyingVelocityMod(org.bukkit.util.Vector flying) {
        this.flyingX = flying.getX();
        this.flyingY = flying.getY();
        this.flyingZ = flying.getZ();
    }

    public org.bukkit.util.Vector getDerailedVelocityMod() {
        return new org.bukkit.util.Vector(this.derailedX, this.derailedY, this.derailedZ);
    }

    public void setDerailedVelocityMod(org.bukkit.util.Vector derailed) {
        this.derailedX = derailed.getX();
        this.derailedY = derailed.getY();
        this.derailedZ = derailed.getZ();
    }
    // CraftBukkit end
}
