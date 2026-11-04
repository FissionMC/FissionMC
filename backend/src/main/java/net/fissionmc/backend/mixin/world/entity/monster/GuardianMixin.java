package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.world.entity.monster.Guardian;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/monster/Guardian.java.patch */
@Mixin(Guardian.class)
public abstract class GuardianMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private Guardian.@Nullable GuardianAttackGoal guardianAttackGoal; // CraftBukkit - add field
    // TODO(behaviour): registerGoals assigns the GuardianAttackGoal it adds to this field.
}
