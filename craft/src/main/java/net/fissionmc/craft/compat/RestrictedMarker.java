package net.fissionmc.craft.compat;

/**
 * Paper: sources/net/minecraft/commands/Commands.java.patch adds this as the nested interface
 * {@code net.minecraft.commands.Commands.RestrictedMarker}; Mixin cannot add nested classes, so it lives here.
 */
public interface RestrictedMarker { boolean restricted(); } // Paper - restricted api
