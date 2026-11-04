package net.fissionmc.backend.mixin.world.entity.vehicle.minecart;

import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/vehicle/minecart/MinecartTNT.java.patch */
@Mixin(MinecartTNT.class)
public abstract class MinecartTNTMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private boolean isIncendiary = false; // CraftBukkit
    // TODO(behaviour): explode() passes isIncendiary and fires ExplosionPrimeEvent.
}
