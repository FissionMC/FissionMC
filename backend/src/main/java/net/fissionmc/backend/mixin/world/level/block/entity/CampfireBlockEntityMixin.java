package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/CampfireBlockEntity.java.patch
 * TODO(behaviour): cookTick skips slots with stopCooking set; "Paper.StopCooking" load/save.
 */
@Mixin(CampfireBlockEntity.class)
public abstract class CampfireBlockEntityMixin {
    // Surface: public final (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private final boolean[] stopCooking = new boolean[4]; // Paper - Add more Campfire API
}
