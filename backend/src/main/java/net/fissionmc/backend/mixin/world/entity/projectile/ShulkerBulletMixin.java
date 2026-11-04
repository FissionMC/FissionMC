package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/projectile/ShulkerBullet.java.patch */
@Mixin(ShulkerBullet.class)
public abstract class ShulkerBulletMixin {
    @Shadow private EntityReference<Entity> finalTarget;
    @Shadow private Direction currentMoveDirection;

    @Shadow protected abstract void selectNextMoveDirection(Direction.Axis invalidAxis, @Nullable Entity target);

    // TODO(behaviour): constructor sets projectileSource = owner.getBukkitEntity().

    // CraftBukkit start
    @Nullable
    public Entity getTarget() {
        return EntityReference.get(this.finalTarget, ((Entity) (Object) this).level(), Entity.class);
    }

    public void setTarget(@Nullable Entity finalTarget) {
        this.finalTarget = finalTarget == null ? null : net.minecraft.world.entity.EntityReference.of(finalTarget);
        this.currentMoveDirection = Direction.UP;
        this.selectNextMoveDirection(Direction.Axis.X, finalTarget);
    }
    // CraftBukkit end
}
