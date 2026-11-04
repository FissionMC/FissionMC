package net.fissionmc.craft.compat;

/**
 * Paper's {@code net.minecraft.world.level.portal.TeleportTransition.PassengerTeleportationMode}
 * (sources/net/minecraft/world/level/portal/TeleportTransition.java.patch), moved here because Mixin cannot add nested classes.
 */
public enum PassengerTeleportationMode {
    RELATIVE,
    POSITION_RIDER
}
