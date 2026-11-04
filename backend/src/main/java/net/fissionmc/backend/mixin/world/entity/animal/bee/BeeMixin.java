package net.fissionmc.backend.mixin.world.entity.animal.bee;

import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/animal/bee/Bee.java.patch */
@Mixin(Bee.class)
public abstract class BeeMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private net.kyori.adventure.util.TriState rollingOverride = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Rolling override
    // TODO(behaviour): setRolling applies rollingOverride.toBooleanOrElse(rolling).
}
