package net.fissionmc.backend.mixin.world.entity.projectile.throwableitemprojectile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl.java.patch */
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin extends ThrowableItemProjectile {
    @Shadow private static boolean isAllowedToTeleportOwner(Entity owner, Level newLevel) { throw new AssertionError(); }
    @Shadow private void playSound(Level level, Vec3 position) { throw new AssertionError(); }

    protected ThrownEnderpearlMixin(final EntityType<? extends ThrowableItemProjectile> type, final Level level) {
        super(type, level);
    }

    // FissionMC: replaced body (CraftBukkit teleport moved up with ENDER_PEARL cause, endermite at pre-teleport position, remove causes)
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void fission$onHit(HitResult hitResult, CallbackInfo ci) {
        ci.cancel();
        super.onHit(hitResult);

        for (int i = 0; i < 32; i++) {
            this.level()
                .addParticle(
                    ParticleTypes.PORTAL,
                    this.getX(),
                    this.getY() + this.random.nextDouble() * 2.0,
                    this.getZ(),
                    this.random.nextGaussian(),
                    0.0,
                    this.random.nextGaussian()
                );
        }

        if (this.level() instanceof ServerLevel level && !this.isRemoved()) {
            Entity owner = this.getOwner();
            if (owner != null && isAllowedToTeleportOwner(owner, level)) {
                Vec3 teleportPos = this.oldPosition();
                if (owner instanceof ServerPlayer player) {
                    if (player.connection.isAcceptingMessages()) {
                        // CraftBukkit start
                        // Store pre teleportation position as the teleport has been moved up.
                        final double preTeleportX = player.getX(), preTeleportY = player.getY(), preTeleportZ = player.getZ();
                        final float preTeleportYRot = player.getYRot(), preTeleportXRot = player.getXRot();
                        ServerPlayer newOwner = player.teleport(new TeleportTransition(
                            level, teleportPos, Vec3.ZERO, 0.0F, 0.0F, Relative.ROTATION, TeleportTransition.DO_NOTHING).withCause(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
                        if (newOwner == null) {
                            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT);
                            return;
                        }
                        // CraftBukkit end
                        if (this.random.nextFloat() < 0.05F && level.isSpawningMonsters() && level.getLevelData().getDifficulty() != Difficulty.PEACEFUL) {
                            Endermite endermite = EntityTypes.ENDERMITE.create(level, EntitySpawnReason.TRIGGERED);
                            if (endermite != null) {
                                endermite.snapTo(preTeleportX, preTeleportY, preTeleportZ, preTeleportYRot, preTeleportXRot); // Paper - spawn endermite at pre teleport position as teleport has been moved up
                                level.addFreshEntity(endermite, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.ENDER_PEARL); // Paper - add reason
                            }
                        }

                        if (this.isOnPortalCooldown()) {
                            owner.setPortalCooldown();
                        }

                        // CraftBukkit start - moved up
                        // CraftBukkit end - moved up
                        if (newOwner != null) {
                            newOwner.resetFallDistance();
                            newOwner.resetCurrentImpulseContext();
                            newOwner.hurtServer(player.level(), this.damageSources().enderPearl().eventEntityDamager(this), 5.0F); // CraftBukkit // Paper - fix DamageSource API
                        }

                        this.playSound(level, teleportPos);
                    }
                } else {
                    Entity newOwner = owner.teleport(
                        new TeleportTransition(level, teleportPos, Vec3.ZERO, owner.getYRot(), owner.getXRot(), TeleportTransition.DO_NOTHING)
                    );
                    if (newOwner != null) {
                        newOwner.resetFallDistance();
                    }

                    if (newOwner instanceof LivingEntity livingEntity) {
                        livingEntity.resetCurrentImpulseContext();
                    }

                    this.playSound(level, teleportPos);
                }

                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
            } else {
                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
            }
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl;discard()V"))
    private void fission$discardDespawn(ThrownEnderpearl instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    @Redirect(method = "teleport", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;placePortalTicket(Lnet/minecraft/core/BlockPos;)V"))
    private void fission$legacyPearl(Entity newEntity, BlockPos pos) {
        if (!this.level().paperConfig().misc.legacyEnderPearlBehavior) newEntity.placePortalTicket(pos); // Paper - Allow using old ender pearl behavior
    }

    @Inject(method = "canTeleport", at = @At("HEAD"), cancellable = true)
    private void fission$canTeleport(Level from, Level to, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(from.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.END && to.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.OVERWORLD && this.getOwner() instanceof ServerPlayer player // CraftBukkit
            ? super.canTeleport(from, to) && player.seenCredits
            : super.canTeleport(from, to));
    }
}
