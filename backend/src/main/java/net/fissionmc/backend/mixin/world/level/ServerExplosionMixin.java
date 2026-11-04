package net.fissionmc.backend.mixin.world.level;

import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/ServerExplosion.java.patch */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    // CraftBukkit - add field
    private boolean wasCanceled = false;
    private float yield;
    // CraftBukkit end
    private boolean excludeSourceFromDamage = true; // Paper - Allow explosions to damage source
    // TODO(behaviour): constructor (yield), explode/hurtEntities/interactWithBlocks hunks (EntityExplodeEvent/BlockExplodeEvent, wasCanceled, density cache)
}
