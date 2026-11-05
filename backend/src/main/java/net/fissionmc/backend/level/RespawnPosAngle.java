package net.fissionmc.backend.level;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Paper's {@code ServerPlayer.RespawnPosAngle} with the components Paper adds (sources/net/minecraft/server/level/ServerPlayer.java.patch).
 * FissionMC: Mixin cannot add record components, so ServerPlayerMixin uses this copy.
 */
public record RespawnPosAngle(Vec3 position, float yaw, float pitch, boolean isBedSpawn, boolean isAnchorSpawn, @Nullable Runnable consumeAnchorCharge) { // Paper - Fix SPIGOT-5989
    public static RespawnPosAngle of(final Vec3 position, final BlockPos lookAtBlockPos, final float pitch, final boolean isBedSpawn, final boolean isAnchorSpawn, final @Nullable Runnable consumeAnchorCharge) { // Paper - Fix SPIGOT-5989
        return new RespawnPosAngle(position, calculateLookAtYaw(position, lookAtBlockPos), pitch, isBedSpawn, isAnchorSpawn, consumeAnchorCharge); // Paper - Fix SPIGOT-5989
    }

    private static float calculateLookAtYaw(final Vec3 position, final BlockPos lookAtBlockPos) {
        Vec3 lookDirection = Vec3.atBottomCenterOf(lookAtBlockPos).subtract(position).normalize();
        return (float)Mth.wrapDegrees(Mth.atan2(lookDirection.z, lookDirection.x) * 180.0F / (float)Math.PI - 90.0);
    }
}
