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

    private org.bukkit.entity.Entity tagged(String tag) {
        for (org.bukkit.World w : getServer().getWorlds()) for (org.bukkit.entity.Entity e : w.getEntities()) if (e.getScoreboardTags().contains(tag)) return e;
        return null;
    }

    /**
     * /fissiontest rider <player> [pig|villager] : an entity rides the player (vanilla's /ride refuses players as vehicles; plugins can).
     * /fissiontest shoulder <player> : a parrot sits on the player's left shoulder.
     * /fissiontest leash <player> <tag> : the entity with that scoreboard tag is leashed to the player.
     * /fissiontest pdc <tag> <key> <value> : a PersistentDataContainer string on the entity with that tag (Paper's BukkitValues).
     */
    @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        try {
            if (args.length >= 2 && args[0].equals("rider")) {
                org.bukkit.entity.Player p = getServer().getPlayerExact(args[1]);
                if (p == null) { sender.sendMessage("no such player"); return true; }
                boolean villager = args.length > 2 && args[2].equals("villager");
                org.bukkit.entity.Entity e = p.getWorld().spawnEntity(p.getLocation(), villager ? org.bukkit.entity.EntityType.VILLAGER : org.bukkit.entity.EntityType.PIG);
                if (villager) {
                    org.bukkit.entity.Villager v = (org.bukkit.entity.Villager) e;
                    v.setProfession(org.bukkit.entity.Villager.Profession.LIBRARIAN); v.setVillagerLevel(2); v.setCustomName("Vil"); v.getRecipes();
                    e.addScoreboardTag("vill");
                } else e.addScoreboardTag("piggy");
                sender.sendMessage("rider added: " + p.addPassenger(e));
                return true;
            }
            if (args.length == 2 && args[0].equals("shoulder")) {
                org.bukkit.entity.Player p = getServer().getPlayerExact(args[1]);
                org.bukkit.entity.Parrot parrot = (org.bukkit.entity.Parrot) p.getWorld().spawnEntity(p.getLocation(), org.bukkit.entity.EntityType.PARROT);
                parrot.setVariant(org.bukkit.entity.Parrot.Variant.GREEN); parrot.setCustomName("Polly");
                p.setShoulderEntityLeft(parrot);
                sender.sendMessage("shoulder parrot set");
                return true;
            }
            if (args.length == 3 && args[0].equals("leash")) {
                org.bukkit.entity.Player p = getServer().getPlayerExact(args[1]);
                org.bukkit.entity.Entity e = tagged(args[2]);
                sender.sendMessage("leashed: " + ((org.bukkit.entity.LivingEntity) e).setLeashHolder(p));
                return true;
            }
            if (args.length == 4 && args[0].equals("pdc")) {
                org.bukkit.entity.Entity e = tagged(args[1]);
                e.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("fissiontest", args[2]), org.bukkit.persistence.PersistentDataType.STRING, args[3]);
                sender.sendMessage("pdc set");
                return true;
            }
        } catch (Throwable t) { sender.sendMessage("fissiontest failed: " + t); getLogger().warning("fissiontest: " + t); }
        return false;
    }

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
