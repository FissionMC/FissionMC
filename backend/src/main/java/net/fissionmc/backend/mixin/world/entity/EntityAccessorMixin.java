package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessors for protected Entity members used by entity-area mixins on other classes. */
@Mixin(Entity.class)
public interface EntityAccessorMixin {
    @Accessor("firstTick")
    boolean fission$isFirstTick();
}
