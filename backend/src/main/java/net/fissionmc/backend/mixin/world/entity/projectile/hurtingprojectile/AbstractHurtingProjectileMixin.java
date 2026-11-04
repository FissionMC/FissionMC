package net.fissionmc.backend.mixin.world.entity.projectile.hurtingprojectile;

import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/projectile/hurtingprojectile/AbstractHurtingProjectile.java.patch */
@Mixin(AbstractHurtingProjectile.class)
public abstract class AbstractHurtingProjectileMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private float bukkitYield = 1; // CraftBukkit
    private boolean isIncendiary = true; // CraftBukkit
    // TODO(behaviour): LargeFireball/SmallFireball/Ghast/explosions consume bukkitYield and isIncendiary.
}
