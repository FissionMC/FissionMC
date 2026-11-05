package net.fissionmc.craft.compat;

import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Paper's {@code net.minecraft.server.level.ServerPlayer.RespawnResult} (sources/net/minecraft/server/level/ServerPlayer.java.patch).
 * FissionMC: Mixin cannot add nested classes to vanilla classes, so the record lives here.
 */
// Paper start
public record RespawnResult(TeleportTransition transition, boolean isBedSpawn, boolean isAnchorSpawn) {
}
// Paper end
