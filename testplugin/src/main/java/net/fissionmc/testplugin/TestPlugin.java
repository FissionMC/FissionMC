package net.fissionmc.testplugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class TestPlugin extends JavaPlugin {
    @Override public void onLoad() { getLogger().info("onLoad"); }
    @Override public void onEnable() { getLogger().info("enabled on " + getServer().getName() + " " + getServer().getVersion()); }
    @Override public void onDisable() { getLogger().info("disabled"); }
}
