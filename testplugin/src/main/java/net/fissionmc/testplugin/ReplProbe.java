package net.fissionmc.testplugin;

import org.bukkit.plugin.java.JavaPlugin;

/** Main class of the tiny plugins ReplX / ReplY (jars built by `:testplugin:replJars`) used by the plugin replication run. */
public final class ReplProbe extends JavaPlugin {
    @Override public void onEnable() {
        getLogger().info("repl-probe enabled: " + getName());
    }
}
