package net.fissionmc.craft.compat;

/**
 * Paper's {@code net.minecraft.server.Services.PaperServices} (sources/net/minecraft/server/Services.java.patch),
 * moved here because Mixin cannot add nested classes to vanilla classes.
 */
public record PaperServices(
    io.papermc.paper.configuration.PaperConfigurations configurations,
    io.papermc.paper.profile.PaperFilledProfileCache filledProfileCache
) {}
