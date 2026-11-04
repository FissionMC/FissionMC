package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Members Paper adds to {@link LightningBolt} (paper-server/patches/sources/net/minecraft/world/entity/LightningBolt.java.patch).
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltMixin {
    private boolean isEffect; // Paper - Properly handle lightning effects api // TODO(behaviour): tick() skips fire/damage when isEffect
}
