package net.fissionmc.testplugin;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Event probe for the scripted standalone run (.dev/scripts/events-test.sh): counts every Bukkit/Paper event that fires (all event classes of the
 * API jar that own a handler list, see events.txt) and builds small server-side scenarios that must fire the events ported from Paper's patches.
 *   /fissiontest evrun              build the scenarios in the spawn chunk (force loaded) of the first world, check them a few seconds later:
 *                                   logs EVCHECK PASS|FAIL lines, an EVREPORT line with every event seen and "EVCHECK DONE fails=<n>"
 *   /fissiontest evreport           log every event seen so far with its count
 */
final class EventProbe implements Listener {
    private final JavaPlugin plugin;
    final java.util.Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.List<String> notes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    EventProbe(JavaPlugin plugin) { this.plugin = plugin; }

    void register() {
        int n = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(plugin.getResource("events.txt")))) {
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    Class<? extends Event> c = Class.forName(line.trim()).asSubclass(Event.class);
                    c.getDeclaredMethod("getHandlerList"); // only classes that own a list: subclasses are counted through their parent's list by their own name
                    plugin.getServer().getPluginManager().registerEvent(c, this, EventPriority.MONITOR, (l, e) -> seen(e), plugin, false);
                    n++;
                } catch (NoSuchMethodException | ClassNotFoundException | ClassCastException ignored) {
                }
            }
        } catch (Exception e) { plugin.getLogger().warning("event probe: " + e); }
        plugin.getLogger().info("event probe listening to " + n + " event classes");
    }

    private void seen(Event e) {
        counts.merge(e.getClass().getSimpleName(), 1, Integer::sum);
        try { tweak(e); } catch (Throwable t) { notes.add("tweak " + e.getClass().getSimpleName() + ": " + t); }
    }

    /** shortens the scenarios (and exercises the setters the mixins read back). */
    private void tweak(Event e) {
        if (e instanceof org.bukkit.event.block.BrewingStartEvent b) b.setBrewingTime(2);
        else if (e instanceof org.bukkit.event.inventory.FurnaceStartSmeltEvent f) f.setTotalCookTime(2);
        else if (e instanceof org.bukkit.event.block.CampfireStartEvent c) c.setTotalCookTime(2);
    }

    private Block base;

    void setup() throws Exception {
        World w = plugin.getServer().getWorlds().get(0);
        org.bukkit.Chunk chunk = w.getSpawnLocation().getChunk();
        chunk.load(); chunk.setForceLoaded(true);
        base = chunk.getBlock(2, Math.min(w.getMaxHeight() - 20, 200), 2);
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) for (int y = -1; y < 4; y++) base.getRelative(x, y, z).setType(y == -1 ? Material.STONE : Material.AIR, false);

        // brewing: fuel + start + brew
        Block brew = base.getRelative(1, 0, 1);
        brew.setType(Material.BREWING_STAND);
        org.bukkit.inventory.BrewerInventory bi = ((org.bukkit.block.BrewingStand) brew.getState(false)).getInventory();
        ItemStack water = new ItemStack(Material.POTION);
        org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) water.getItemMeta();
        pm.setBasePotionType(org.bukkit.potion.PotionType.WATER); water.setItemMeta(pm);
        for (int i = 0; i < 3; i++) bi.setItem(i, water.clone());
        bi.setIngredient(new ItemStack(Material.NETHER_WART));
        bi.setFuel(new ItemStack(Material.BLAZE_POWDER));

        // furnace: burn + start smelt + smelt
        Block furnace = base.getRelative(3, 0, 1);
        furnace.setType(Material.FURNACE);
        org.bukkit.inventory.FurnaceInventory fi = ((org.bukkit.block.Furnace) furnace.getState(false)).getInventory();
        fi.setSmelting(new ItemStack(Material.RAW_IRON, 2));
        fi.setFuel(new ItemStack(Material.COAL, 2));

        // campfire: placeFood (CampfireStartEvent) then BlockCookEvent
        Block camp = base.getRelative(5, 0, 1);
        camp.setType(Material.CAMPFIRE);
        Object level = Class.forName("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle").invoke(w);
        Object be = nmsBlockEntity(level, camp);
        Object nmsBeef = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class).invoke(null, new ItemStack(Material.BEEF));
        java.lang.reflect.Method place = null;
        for (java.lang.reflect.Method m : be.getClass().getMethods()) if (m.getName().equals("placeFood")) place = m;
        notes.add("campfire placeFood=" + place.invoke(be, level, null, nmsBeef));

        // composter fed by a hopper (CompostItemEvent) and emptied by one (no event, must still work)
        Block composter = base.getRelative(7, 0, 1);
        composter.setType(Material.COMPOSTER);
        Block hopper = composter.getRelative(0, 1, 0);
        hopper.setType(Material.HOPPER);
        ((org.bukkit.block.Hopper) hopper.getState(false)).getInventory().addItem(new ItemStack(Material.PUMPKIN_PIE, 3));
    }

    private static Object nmsBlockEntity(Object level, Block b) throws Exception {
        Class<?> bp = Class.forName("net.minecraft.core.BlockPos");
        Object pos = bp.getConstructor(int.class, int.class, int.class).newInstance(b.getX(), b.getY(), b.getZ());
        return Class.forName("net.minecraft.world.level.Level").getMethod("getBlockEntity", bp).invoke(level, pos);
    }

    private int fails;

    private void check(String name, String csv) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String n : csv.split(",")) if (!counts.containsKey(n)) missing.add(n);
        if (!missing.isEmpty()) fails++;
        plugin.getLogger().info(missing.isEmpty() ? "EVCHECK PASS " + name + ": " + csv : "EVCHECK FAIL " + name + ": missing " + String.join(",", missing));
    }

    private void later(int ticks, ThrowingRunnable r) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try { r.run(); } catch (Throwable t) { fails++; plugin.getLogger().warning("EVCHECK FAIL step threw: " + t + (t.getCause() != null ? " / " + t.getCause() : "")); }
        }, ticks);
    }

    interface ThrowingRunnable { void run() throws Exception; }

    /** the whole scripted run: scenarios, then the checks some ticks later; ends with "EVCHECK DONE fails=<n>". */
    void run() {
        fails = 0;
        later(1, this::setup);
        later(100, () -> {
            check("brewing stand", "BrewingStandFuelEvent,BrewingStartEvent,BrewEvent");
            check("furnace", "FurnaceBurnEvent,FurnaceStartSmeltEvent,FurnaceSmeltEvent");
            check("campfire", "CampfireStartEvent,BlockCookEvent");
            check("composter", "CompostItemEvent");
            base.getRelative(3, 0, 1).setType(Material.AIR); // the furnace pops the experience of the recipes it used
            check("furnace broken", "BlockExpEvent");
        });
        later(110, () -> {
            report();
            plugin.getLogger().info("EVCHECK DONE fails=" + fails);
        });
    }

    void report() {
        StringBuilder sb = new StringBuilder("EVREPORT");
        new java.util.TreeMap<>(counts).forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        plugin.getLogger().info(sb.toString());
        synchronized (notes) { for (String n : notes) plugin.getLogger().info("EVNOTE " + n); notes.clear(); }
    }
}
