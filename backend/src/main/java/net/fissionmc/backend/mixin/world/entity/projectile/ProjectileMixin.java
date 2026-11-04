package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/projectile/Projectile.java.patch */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    // Surface: protected (see surface/members/entitytypes.txt); the loader widens it.
    private boolean hitCancelled = false; // CraftBukkit
    // TODO(behaviour): onHitBlock returns early when hitCancelled; vanilla callers of hitTargetOrDeflectSelf
    //  (subclass tick methods) should call preHitTargetOrDeflectSelf; Projectile.Delayed / spawnProjectile*Delayed not ported (unused by craft).

    @Shadow protected abstract ProjectileDeflection hitTargetOrDeflectSelf(HitResult hitResult);
    @Shadow protected abstract boolean canHitEntity(Entity target);

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
}
