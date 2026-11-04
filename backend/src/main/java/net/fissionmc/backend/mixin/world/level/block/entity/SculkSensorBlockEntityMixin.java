package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/SculkSensorBlockEntity.java.patch
 * TODO(behaviour): listener range load/save and VibrationUser#getListenerRadius honour rangeOverride.
 */
@Mixin(SculkSensorBlockEntity.class)
public abstract class SculkSensorBlockEntityMixin {
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private @Nullable Integer rangeOverride = null; // Paper - Configurable sculk sensor listener range
}
