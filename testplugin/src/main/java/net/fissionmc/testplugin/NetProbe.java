package net.fissionmc.testplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Network-side probe (.dev/scripts/net-test.sh, bot: master/src/test/.../NetBotMain.kt). Counts every Bukkit/Paper event (like EventProbe), ops the joining
 * bot and gives it items, and provides server-side scenario commands the bot triggers by chat command:
 *   /fissiontest netsetup    builds a stone platform under the player, a cow, an armor stand, a chest and a sign; answers "NETENT cow=<id> stand=<id> chest=x,y,z sign=x,y,z floor=x,y,z"
 *   /fissiontest netsign     opens the sign editor of the sign
 *   /fissiontest netpack     sends a resource pack (id 00000000-0000-0000-0000-000000000005)
 *   /fissiontest netreport   logs "NETCOUNT <event> <n>" for every event seen, then "NETREPORT DONE"
 * Registered when plugins/FissionTest/net-probe exists.
 */
final class NetProbe implements Listener {
    private final JavaPlugin plugin;
    private final java.util.Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();
    private Block sign;

    NetProbe(JavaPlugin plugin) { this.plugin = plugin; }

    void register() {
        int n = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(plugin.getResource("events.txt")))) {
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    Class<? extends Event> c = Class.forName(line.trim()).asSubclass(Event.class);
                    c.getDeclaredMethod("getHandlerList");
                    plugin.getServer().getPluginManager().registerEvent(c, this, EventPriority.MONITOR, (l, e) -> seen(e), plugin, false);
                    n++;
                } catch (NoSuchMethodException | ClassNotFoundException | ClassCastException ignored) {
                }
            }
        } catch (Exception e) { plugin.getLogger().warning("net probe: " + e); }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("net probe listening to " + n + " event classes");
    }

    private void seen(Event e) {
        for (Class<?> c = e.getClass(); c != null && c != Event.class && c != Object.class; c = c.getSuperclass()) counts.merge(c.getSimpleName(), 1, Integer::sum); // parents count too (PlayerInteractEntityEvent via PlayerInteractAtEntityEvent)
        if (e instanceof org.bukkit.event.player.PlayerTeleportEvent t) counts.merge("PlayerTeleportEvent:" + t.getCause(), 1, Integer::sum);
        if (e instanceof org.bukkit.event.inventory.InventoryClickEvent c) counts.merge("InventoryClickEvent:" + c.getClick(), 1, Integer::sum);
        if (e instanceof org.bukkit.event.player.PlayerInteractEvent i) { counts.merge("PlayerInteractEvent:" + i.getAction(), 1, Integer::sum); counts.merge("PlayerInteractEvent:" + i.getAction() + ":" + i.getPlayer().getGameMode() + ":item=" + i.useItemInHand() + ":block=" + i.useInteractedBlock() + ":cancelled=" + i.isCancelled(), 1, Integer::sum); }
        if (e instanceof org.bukkit.event.player.PlayerResourcePackStatusEvent s) counts.merge("PlayerResourcePackStatusEvent:" + s.getStatus(), 1, Integer::sum);
        if (e instanceof org.bukkit.event.player.PlayerQuitEvent) counts.merge("PlayerQuitEvent", 0, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setOp(true);
        p.setGameMode(org.bukkit.GameMode.SURVIVAL); // the standalone test server joins players in creative
        p.getInventory().setItem(0, new ItemStack(Material.STONE, 64));
        p.getInventory().setItem(1, new ItemStack(Material.DIAMOND_SWORD));
        p.getInventory().setItem(2, new ItemStack(Material.SNOWBALL, 16));
        p.getInventory().setItem(3, new ItemStack(Material.WRITABLE_BOOK));
        p.getInventory().setItem(4, new ItemStack(Material.APPLE, 5));
        p.getInventory().setItem(9, new ItemStack(Material.APPLE, 5));
        p.getInventory().setItem(10, new ItemStack(Material.APPLE, 5));
    }

    boolean command(org.bukkit.command.CommandSender sender, String[] args) {
        switch (args[0]) {
            case "netsetup" -> {
                Player p = (Player) sender;
                Location l = p.getLocation();
                int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
                org.bukkit.World w = p.getWorld();
                for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                    w.getBlockAt(bx + x, by - 1, bz + z).setType(Material.STONE, false);
                    for (int y = 0; y < 4; y++) w.getBlockAt(bx + x, by + y, bz + z).setType(Material.AIR, false);
                }
                Block chest = w.getBlockAt(bx + 3, by, bz + 3); chest.setType(Material.CHEST, false);
                ((org.bukkit.block.Chest) chest.getState()).getBlockInventory().setItem(0, new ItemStack(Material.DIRT, 3));
                sign = w.getBlockAt(bx - 3, by, bz - 3); sign.setType(Material.OAK_SIGN, false);
                org.bukkit.entity.Cow cow = w.spawn(new Location(w, bx + 3.5, by, bz + 0.5), org.bukkit.entity.Cow.class, c -> c.setAI(false));
                org.bukkit.entity.ArmorStand stand = w.spawn(new Location(w, bx + 0.5, by, bz + 3.5), org.bukkit.entity.ArmorStand.class);
                p.teleport(new Location(w, bx + 0.5, by, bz + 0.5, 0, 0));
                sender.sendMessage("NETENT cow=" + cow.getEntityId() + " stand=" + stand.getEntityId() + " chest=" + chest.getX() + "," + chest.getY() + "," + chest.getZ()
                    + " sign=" + sign.getX() + "," + sign.getY() + "," + sign.getZ() + " floor=" + bx + "," + (by - 1) + "," + bz);
            }
            case "netsign" -> ((Player) sender).openSign((org.bukkit.block.Sign) sign.getState(), org.bukkit.block.sign.Side.FRONT);
            case "netpack" -> ((Player) sender).setResourcePack(new java.util.UUID(0, 5), "http://127.0.0.1:9/none.zip", new byte[20], (net.kyori.adventure.text.Component) null, false);
            case "netreport" -> {
                new java.util.TreeMap<>(counts).forEach((k, v) -> plugin.getLogger().info("NETCOUNT " + k + " " + v));
                plugin.getLogger().info("NETREPORT DONE");
            }
            default -> { return false; }
        }
        return true;
    }
}
