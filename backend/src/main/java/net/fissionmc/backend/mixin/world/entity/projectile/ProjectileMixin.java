package net.fissionmc.backend.mixin.world.entity.projectile;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/projectile/Projectile.java.patch */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    // Surface: protected (see surface/members/entitytypes.txt); the loader widens it.
    private boolean hitCancelled = false; // CraftBukkit
    // Not ported: Projectile.Delayed / spawnProjectile*Delayed (nested record; unused by craft).

    @Shadow protected @Nullable EntityReference<Entity> owner;
    @Shadow protected abstract ProjectileDeflection hitTargetOrDeflectSelf(HitResult hitResult);
    @Shadow protected abstract boolean canHitEntity(Entity target);
    @Shadow public abstract @Nullable Entity getOwner();

    // CraftBukkit start - call projectile hit event
    public ProjectileDeflection preHitTargetOrDeflectSelf(HitResult hitResult) {
        org.bukkit.event.entity.ProjectileHitEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callProjectileHitEvent((Projectile) (Object) this, hitResult);
        this.hitCancelled = event != null && event.isCancelled();
        if (hitResult.getType() == HitResult.Type.BLOCK || !this.hitCancelled) {
            return this.hitTargetOrDeflectSelf(hitResult);
        }
        return ProjectileDeflection.NONE;
    }
    // CraftBukkit end

    // Paper start
    public boolean canHitEntityPublic(final Entity target) {
        return this.canHitEntity(target);
    }
    // Paper end

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void fission$resetPearlOwner(ValueInput input, CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (self instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl && self.level().paperConfig().fixes.disableUnloadedChunkEnderpearlExploit && self.level().paperConfig().misc.legacyEnderPearlBehavior) { this.owner = null; } // Paper - Reset pearls when they stop being ticked; Don't store shooter name for pearls to block enderpearl travel exploit
    }

    // Paper start - allow disabling relative velocity
    @ModifyExpressionValue(method = "shootFromRotation", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getKnownMovement()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 fission$finiteSourceMovement(Vec3 sourceMovement) {
        if (!sourceMovement.isFinite()) {
            sourceMovement = Vec3.ZERO;
        }
        return sourceMovement;
    }

    @WrapWithCondition(method = "shootFromRotation", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private boolean fission$relativeVelocity(Projectile instance, Vec3 movement, Entity source, float xRot, float yRot, float yOffset, float pow, float uncertainty) {
        return !source.level().paperConfig().misc.disableRelativeProjectileVelocity;
    }
    // Paper end - allow disabling relative velocity

    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void fission$hitCancelled(BlockHitResult hitResult, CallbackInfo ci) {
        // CraftBukkit start - cancellable hit event
        if (this.hitCancelled) {
            ci.cancel();
        }
        // CraftBukkit end
    }

    @ModifyReturnValue(method = "canHitEntity", at = @At("RETURN"))
    private boolean fission$cancelHitVanished(boolean original, Entity entity) {
        if (!original) return false;
        Entity owner = this.getOwner();
        // Paper start - Cancel hit for vanished entities
        if (owner instanceof net.minecraft.server.level.ServerPlayer) {
            org.bukkit.entity.Entity collided = entity.getBukkitEntity();
            org.bukkit.entity.Player shooter = (org.bukkit.entity.Player) owner.getBukkitEntity();
            if (!shooter.canSee(collided)) {
                return false;
            }
        }
        // Paper end - Cancel hit for vanished entities
        return true;
    }

    @Inject(method = "lerpRotation", at = @At("HEAD"), cancellable = true)
    private static void fission$lerpRotation(float rotO, float rot, CallbackInfoReturnable<Float> cir) {
        rotO += Math.round((rot - rotO) / 360.0F) * 360.0F; // Paper - stop large look changes from crashing the server
        cir.setReturnValue(Mth.lerp(0.2F, rotO, rot));
    }
}
