package net.fissionmc.backend.mixin.world.level.portal;

import java.util.Set;
import net.fissionmc.craft.compat.PassengerTeleportationMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/portal/TeleportTransition.java.patch.
 * FissionMC: Paper adds two record components (cause, passengerTeleportationMode); they are plain fields here with
 * Paper's accessor names, and the 11-argument canonical constructor becomes the static factory {@code create}.
 */
@Mixin(TeleportTransition.class)
public abstract class TeleportTransitionMixin {
    @Shadow public abstract ServerLevel newLevel();
    @Shadow public abstract Vec3 position();
    @Shadow public abstract Vec3 deltaMovement();
    @Shadow public abstract float yRot();
    @Shadow public abstract float xRot();
    @Shadow public abstract boolean missingRespawnBlock();
    @Shadow public abstract boolean asPassenger();
    @Shadow public abstract Set<Relative> relatives();
    @Shadow public abstract TeleportTransition.PostTeleportTransition postTeleportTransition();

    // Paper start - additional transition data
    private org.bukkit.event.player.PlayerTeleportEvent.TeleportCause paper$cause = org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN;
    private PassengerTeleportationMode paper$passengerTeleportationMode = PassengerTeleportationMode.RELATIVE;
    // TODO(behaviour): withRotation/withPosition/transitionAsPassenger hunks keep cause + passengerTeleportationMode

    public org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause() {
        return this.paper$cause;
    }

    public PassengerTeleportationMode passengerTeleportationMode() {
        return this.paper$passengerTeleportationMode;
    }

    private static TeleportTransition create(
        final ServerLevel newLevel,
        final Vec3 position,
        final Vec3 deltaMovement,
        final float yRot,
        final float xRot,
        final boolean missingRespawnBlock,
        final boolean asPassenger,
        final Set<Relative> relatives,
        final TeleportTransition.PostTeleportTransition postTeleportTransition,
        final org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause,
        final PassengerTeleportationMode passengerTeleportationMode
    ) {
        final TeleportTransition transition = new TeleportTransition(newLevel, position, deltaMovement, yRot, xRot, missingRespawnBlock, asPassenger, relatives, postTeleportTransition);
        final TeleportTransitionMixin self = (TeleportTransitionMixin) (Object) transition;
        self.paper$cause = cause;
        self.paper$passengerTeleportationMode = passengerTeleportationMode;
        return transition;
    }

    public TeleportTransition withChangedLocation(final org.bukkit.Location location) {
        return create(
            ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle(),
            org.bukkit.craftbukkit.util.CraftLocation.toVec3(location),
            this.deltaMovement(),
            location.getYaw(),
            location.getPitch(),
            this.missingRespawnBlock(),
            this.asPassenger(),
            this.relatives(),
            this.postTeleportTransition(),
            this.cause(),
            this.passengerTeleportationMode()
        );
    }

    public TeleportTransition withVelocity(final Vec3 velocity) {
        return create(
            this.newLevel(),
            this.position(),
            velocity,
            this.yRot(),
            this.xRot(),
            this.missingRespawnBlock(),
            this.asPassenger(),
            this.relatives(),
            this.postTeleportTransition(),
            this.cause(),
            this.passengerTeleportationMode()
        );
    }

    public TeleportTransition withRelatives(final Set<Relative> relatives) {
        return create(
            this.newLevel(),
            this.position(),
            this.deltaMovement(),
            this.yRot(),
            this.xRot(),
            this.missingRespawnBlock(),
            this.asPassenger(),
            relatives,
            this.postTeleportTransition(),
            this.cause(),
            this.passengerTeleportationMode()
        );
    }

    public TeleportTransition withCause(final org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        return create(
            this.newLevel(),
            this.position(),
            this.deltaMovement(),
            this.yRot(),
            this.xRot(),
            this.missingRespawnBlock(),
            this.asPassenger(),
            this.relatives(),
            this.postTeleportTransition(),
            cause,
            this.passengerTeleportationMode()
        );
    }
    // Paper end - additional transition data
}
