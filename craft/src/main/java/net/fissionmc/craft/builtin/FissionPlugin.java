package net.fissionmc.craft.builtin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Behaviour of the built-in {@code fission} plugin (generated main class, see {@link BuiltinPlugins}): the {@code /fission status} command. */
public final class FissionPlugin {
    private FissionPlugin() {}

    public static void load(JavaPlugin plugin) {}

    public static void enable(JavaPlugin plugin) {
        // plugin messaging between backends: installed by the backend when it runs in a network; plugins find it with FissionNet.get() or through the ServicesManager
        net.fissionmc.api.FissionNet svc = net.fissionmc.api.FissionNet.get();
        if (svc != null) plugin.getServer().getServicesManager().register(net.fissionmc.api.FissionNet.class, svc, plugin, org.bukkit.plugin.ServicePriority.Normal);
        plugin.getCommand("fission").setExecutor(new CommandExecutor() {
            @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
                    for (String line : FissionStatus.debug.apply(java.util.Arrays.copyOfRange(args, 1, args.length))) sender.sendMessage(line);
                    return true;
                }
                if (args.length == 0 || !args[0].equalsIgnoreCase("status")) { sender.sendMessage("Usage: /fission status | debug entities|ghost-set|ghost-sync"); return true; }
                sender.sendMessage("FissionMC status");
                for (String line : FissionStatus.provider.get()) sender.sendMessage("  " + line);
                return true;
            }
        });
    }
}
