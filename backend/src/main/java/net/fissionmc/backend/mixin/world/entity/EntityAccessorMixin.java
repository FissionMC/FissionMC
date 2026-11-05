package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessors for protected Entity members used by entity-area mixins on other classes. */
@Mixin(value = Entity.class, priority = 1100) // after EntityMixin, which adds numCollisions
public interface EntityAccessorMixin {
    @Accessor("firstTick")
    boolean fission$isFirstTick();

    @Accessor("numCollisions")
    int fission$getNumCollisions();

    @Accessor("numCollisions")
    void fission$setNumCollisions(int value);
}
