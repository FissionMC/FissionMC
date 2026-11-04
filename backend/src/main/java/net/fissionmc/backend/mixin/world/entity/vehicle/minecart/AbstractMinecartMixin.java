package net.fissionmc.backend.mixin.world.entity.vehicle.minecart;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java.patch */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin {
    @Shadow protected abstract float getAirDrag();

    // CraftBukkit start
    // Surface: slowWhenEmpty, maxSpeed, frictionState are public (see surface/members/entitytypes.txt); the loader widens them.
    private boolean slowWhenEmpty = true;
    private double derailedX = 0.5;
    private double derailedY = 0.5;
    private double derailedZ = 0.5;
    private double flyingX = this.getAirDrag();
    private double flyingY = this.getAirDrag();
    private double flyingZ = this.getAirDrag();
    private @Nullable Double maxSpeed;
    private net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API
    // CraftBukkit end
    // TODO(behaviour): comeOffTrack applies derailed*/flying* multipliers; Old/NewMinecartBehavior read maxSpeed,
    //  slowWhenEmpty, frictionState; "Paper.FrictionState" save/load; collision events; isCollidable(boolean).

    // CraftBukkit start - Methods for getting and setting flying and derailed velocity modifiers
    public org.bukkit.util.Vector getFlyingVelocityMod() {
        return new org.bukkit.util.Vector(this.flyingX, this.flyingY, this.flyingZ);
    }

    public void setFlyingVelocityMod(org.bukkit.util.Vector flying) {
        this.flyingX = flying.getX();
        this.flyingY = flying.getY();
        this.flyingZ = flying.getZ();
    }

    public org.bukkit.util.Vector getDerailedVelocityMod() {
        return new org.bukkit.util.Vector(this.derailedX, this.derailedY, this.derailedZ);
    }

    public void setDerailedVelocityMod(org.bukkit.util.Vector derailed) {
        this.derailedX = derailed.getX();
        this.derailedY = derailed.getY();
        this.derailedZ = derailed.getZ();
    }
    // CraftBukkit end
}
