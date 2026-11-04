package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/projectile/FireworkRocketEntity.java.patch */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private java.util.@org.jspecify.annotations.Nullable UUID spawningEntity; // Paper
    // TODO(behaviour): saved/loaded as "SpawningEntity" in add/readAdditionalSaveData.
}
