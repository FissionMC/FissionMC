package net.fissionmc.craft.builtin;

import net.fissionmc.packets.FissionPacketEventsAPI;
import net.fissionmc.packets.Side;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Behaviour of the built-in virtual plugin named {@code packetevents}: plugins that {@code depend: [packetevents]} resolve against it
 * and {@code PacketEvents.getAPI()} works without installing the real plugin. The API is core-fed (see FissionPacketEventsAPI).
 * The plugin's main class itself is generated into the descriptor jar (see {@link BuiltinPlugins}) because a JavaPlugin must be
 * defined by its own PluginClassLoader; it only calls these static methods, which live on the server class path.
 */
public final class PacketEventsPlugin {
    private PacketEventsPlugin() {}
    public static void load(JavaPlugin plugin) { FissionPacketEventsAPI.Companion.install(Side.BACKEND); }
    public static void enable(JavaPlugin plugin) { plugin.getLogger().info("Built-in PacketEvents API (FissionMC) is active"); }
}
