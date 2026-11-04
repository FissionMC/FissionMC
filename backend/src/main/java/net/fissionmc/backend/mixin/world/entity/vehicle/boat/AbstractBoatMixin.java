package net.fissionmc.backend.mixin.world.entity.vehicle.boat;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/vehicle/boat/AbstractBoat.java.patch */
@Mixin(AbstractBoat.class)
public abstract class AbstractBoatMixin {
    // CraftBukkit start
    // PAIL: Some of these haven't worked since a few updates, and since 1.9 they are less and less applicable.
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private double maxSpeed = 0.4D;
    private double occupiedDeceleration = 0.2D;
    private double unoccupiedDeceleration = -1;
    private boolean landBoats = false;
    private org.bukkit.Location lastLocation;
    // CraftBukkit end
    // TODO(behaviour): tick() fires VehicleUpdateEvent/VehicleMoveEvent using lastLocation; remove(reason, cause) override;
    //  isCollidable(boolean) override (Paper renames isPushable). getDropItem() only widened (surface).
}
