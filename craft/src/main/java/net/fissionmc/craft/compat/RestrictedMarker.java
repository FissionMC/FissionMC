package net.fissionmc.craft.compat;

/**
 * Paper's {@code net.minecraft.commands.Commands.RestrictedMarker} (sources/net/minecraft/commands/Commands.java.patch),
 * moved here because Mixin cannot add nested classes to vanilla classes.
 */
public interface RestrictedMarker { boolean restricted(); } // Paper - restricted api
