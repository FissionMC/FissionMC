package net.fissionmc.testplugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class TestPlugin extends JavaPlugin implements Listener {
    @Override public void onLoad() { getLogger().info("onLoad"); }
    @Override public void onEnable() {
        getLogger().info("enabled on " + getServer().getName() + " " + getServer().getVersion());
        getServer().getPluginManager().registerEvents(this, this);
    }
    @Override public void onDisable() { getLogger().info("disabled"); }

    /**
     * Chat probe for the scripted multi-backend test: logs every chat event with the number of viewers (global chat has none), cancels
     * messages containing BLOCKME and upper-cases messages starting with "shout:" (modify path).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        getLogger().info("CHATTEST event from " + e.getPlayer().getName() + " viewers=" + e.viewers().size() + " cancelled=" + e.isCancelled() + ": " + text);
        if (text.contains("BLOCKME")) e.setCancelled(true);
        else if (text.startsWith("shout:")) e.message(Component.text(text.substring(6).trim().toUpperCase()));
    }
}
