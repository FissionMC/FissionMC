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
        if (e instanceof org.bukkit.event.entity.EntityTransformEvent t) counts.merge("EntityTransformEvent:" + t.getTransformReason(), 1, Integer::sum);
        else if (e instanceof org.bukkit.event.entity.CreatureSpawnEvent c) counts.merge("CreatureSpawnEvent:" + c.getSpawnReason(), 1, Integer::sum);
        else if (e instanceof org.bukkit.event.entity.EntityRemoveEvent r) counts.merge("EntityRemoveEvent:" + r.getEntityType() + ":" + r.getCause(), 1, Integer::sum);
        else if (e instanceof org.bukkit.event.entity.EntityDeathEvent d) counts.merge("EntityDeathEvent:" + d.getEntityType(), 1, Integer::sum);
        try { tweak(e); } catch (Throwable t) { notes.add("tweak " + e.getClass().getSimpleName() + ": " + t); }
    }

    /** shortens the scenarios (and exercises the setters the mixins read back). */
    private void tweak(Event e) {
        if (e instanceof org.bukkit.event.block.BrewingStartEvent b) b.setBrewingTime(2);
        else if (e instanceof org.bukkit.event.inventory.FurnaceStartSmeltEvent f) f.setTotalCookTime(2);
        else if (e instanceof org.bukkit.event.block.CampfireStartEvent c) c.setTotalCookTime(2);
        // portals: never really travel (the other dimensions would be generated); the ender portal is refused at the enter event (its destination builds the platform before any event)
        else if (e instanceof com.destroystokyo.paper.event.entity.TurtleGoHomeEvent th) th.setCancelled(true); // lets the egg-laying goal finish (and exercises the cancel path)
        else if (e instanceof org.bukkit.event.entity.EntityPortalEvent pe) pe.setCancelled(true);
        else if (e instanceof org.bukkit.event.entity.EntityPortalEnterEvent pe && pe.getPortalType() == org.bukkit.PortalType.ENDER) pe.setCancelled(true);
    }

    private Block base;
    private org.bukkit.entity.Turtle turtleRef;

    void setup() throws Exception {
        World w = plugin.getServer().getWorlds().get(0);
        org.bukkit.Chunk chunk = w.getSpawnLocation().getChunk();
        chunk.load(); chunk.addPluginChunkTicket(plugin); // not setForceLoaded: a persisted forced chunk hangs the next standalone boot at "Preparing spawn area" (found 2026-11-11, not a Paper-port issue)
        for (org.bukkit.entity.Entity e : chunk.getEntities()) if (!(e instanceof org.bukkit.entity.Player)) e.remove(); // leftovers of an earlier run
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

    private <T extends org.bukkit.entity.Entity> T spawn(Class<T> type, int dx, int dz) {
        T e = base.getWorld().spawn(base.getRelative(dx, 0, dz).getLocation().add(0.5, 0, 0.5), type);
        if (e instanceof org.bukkit.entity.LivingEntity l) { l.setRemoveWhenFarAway(false); }
        return e;
    }

    private static Object handle(org.bukkit.entity.Entity e) throws Exception {
        return e.getClass().getMethod("getHandle").invoke(e);
    }

    /** mobs: conversions (EntityTransformEvent reasons), slime split, zaps, sheep, piglin anger. */
    void setupMobs() throws Exception {
        World w = base.getWorld();
        w.setDifficulty(org.bukkit.Difficulty.NORMAL);
        org.bukkit.entity.Slime slime = spawn(org.bukkit.entity.Slime.class, 1, 4);
        slime.setSize(4); slime.setHealth(0);
        org.bukkit.entity.Tadpole tadpole = spawn(org.bukkit.entity.Tadpole.class, 3, 4);
        tadpole.setAge(24000);
        org.bukkit.entity.Hoglin hoglin = spawn(org.bukkit.entity.Hoglin.class, 5, 4);
        hoglin.setImmuneToZombification(false); hoglin.setConversionTime(299);
        org.bukkit.entity.Zombie zombie = spawn(org.bukkit.entity.Zombie.class, 7, 4);
        zombie.setConversionTime(2);
        org.bukkit.entity.Pig pig = spawn(org.bukkit.entity.Pig.class, 1, 8);
        w.strikeLightning(pig.getLocation());
        org.bukkit.entity.Villager villager = spawn(org.bukkit.entity.Villager.class, 9, 8);
        w.strikeLightning(villager.getLocation());
        org.bukkit.entity.Sheep sheep = spawn(org.bukkit.entity.Sheep.class, 5, 8);
        sheep.setSheared(true);
        Object nmsSheep = handle(sheep);
        nmsSheep.getClass().getMethod("ate").invoke(nmsSheep);
        notes.add("sheep sheared after ate()=" + sheep.isSheared());
        org.bukkit.entity.PigZombie piglin = spawn(org.bukkit.entity.PigZombie.class, 9, 4);
        Object nmsPiglin = handle(piglin);
        nmsPiglin.getClass().getMethod("startPersistentAngerTimer").invoke(nmsPiglin);
        spawn(org.bukkit.entity.Slime.class, 3, 10).setSize(1); // wanders: slime pathfinder events
    }

    /** witch potion events, turtle goals, portal blocks and a fire-lit portal frame, a dispenser feeding armor, whitelist and resource reload. */
    void setupMore() throws Exception {
        World w = base.getWorld();
        // witch: low health makes it drink a healing potion (WitchReadyPotionEvent, WitchConsumePotionEvent); performRangedAttack throws (WitchThrowPotionEvent)
        org.bukkit.entity.Witch drinker = spawn(org.bukkit.entity.Witch.class, 1, 6);
        drinker.setHealth(1);
        org.bukkit.entity.Witch thrower = spawn(org.bukkit.entity.Witch.class, 3, 6);
        org.bukkit.entity.Pig target = spawn(org.bukkit.entity.Pig.class, 5, 6);
        Object nmsThrower = handle(thrower);
        for (java.lang.reflect.Method m : nmsThrower.getClass().getMethods())
            if (m.getName().equals("performRangedAttack") && m.getParameterCount() == 2) { m.invoke(nmsThrower, handle(target), 1.0F); break; }
        // turtle with an egg next to sand: GoHome, StartDigging, LayEgg
        base.getRelative(11, -1, 0).setType(Material.SAND);
        org.bukkit.entity.Turtle turtle = spawn(org.bukkit.entity.Turtle.class, 11, 0);
        turtleRef = turtle;
        Object nmsTurtle = handle(turtle);
        nmsTurtle.getClass().getMethod("setHasEgg", boolean.class).invoke(nmsTurtle, true);
        try {
            java.lang.reflect.Field f = nmsTurtle.getClass().getDeclaredField("homePos"); f.setAccessible(true);
            Object pos = Class.forName("net.minecraft.core.BlockPos").getConstructor(int.class, int.class, int.class).newInstance(base.getX() + 11, base.getY(), base.getZ() + 0);
            f.set(nmsTurtle, pos);
        } catch (Throwable t) { notes.add("turtle homePos: " + t); }
        // portal blocks with an entity inside: EntityInsideBlockEvent, EntityPortalEnterEvent (+ EntityPortalReadyEvent, EntityPortalEvent for the nether portal)
        base.getRelative(1, 0, 11).setType(Material.NETHER_PORTAL, false);
        spawn(org.bukkit.entity.Pig.class, 1, 11);
        base.getRelative(3, 0, 11).setType(Material.END_PORTAL, false);
        spawn(org.bukkit.entity.Pig.class, 3, 11);
        // obsidian frame lit with fire: PortalCreateEvent (FIRE)
        for (int x = 5; x <= 8; x++) for (int y = 0; y <= 4; y++) if (x == 5 || x == 8 || y == 0 || y == 4) base.getRelative(x, y, 11).setType(Material.OBSIDIAN, false);
        base.getRelative(6, 1, 11).setType(Material.FIRE);
        // dispenser facing east into an armor stand: BlockDispenseArmorEvent
        Block disp = base.getRelative(9, 0, 2);
        disp.setType(Material.DISPENSER, false);
        org.bukkit.block.data.Directional dd = (org.bukkit.block.data.Directional) disp.getBlockData(); dd.setFacing(org.bukkit.block.BlockFace.EAST); disp.setBlockData(dd, false);
        org.bukkit.block.Dispenser ds = (org.bukkit.block.Dispenser) disp.getState(false);
        ds.getInventory().addItem(new ItemStack(Material.IRON_HELMET));
        spawn(org.bukkit.entity.ArmorStand.class, 10, 2);
        notes.add("dispenser dispense()=" + ds.dispense());
        // whitelist events: toggle on/off, add/remove an offline profile
        org.bukkit.Server srv = plugin.getServer();
        srv.setWhitelist(true); srv.setWhitelist(false);
        org.bukkit.OfflinePlayer op = srv.getOfflinePlayer(java.util.UUID.nameUUIDFromBytes("OfflinePlayer:FissionBot".getBytes()));
        op.setWhitelisted(true); op.setWhitelisted(false);
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
        later(2, this::setupMobs);
        later(3, this::setupMore);
        later(150, () -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:reload"));
        later(100, () -> {
            check("brewing stand", "BrewingStandFuelEvent,BrewingStartEvent,BrewEvent");
            check("furnace", "FurnaceBurnEvent,FurnaceStartSmeltEvent,FurnaceSmeltEvent");
            check("campfire", "CampfireStartEvent,BlockCookEvent");
            check("composter", "CompostItemEvent");
            base.getRelative(3, 0, 1).setType(Material.AIR); // the furnace pops the experience of the recipes it used
            check("furnace broken", "BlockExpEvent");
            check("slime split", "SlimeSplitEvent,EntityTransformEvent:SPLIT,CreatureSpawnEvent:SLIME_SPLIT");
            check("slime goals", "SlimeWanderEvent,SlimeChangeDirectionEvent");
            check("tadpole", "EntityTransformEvent:METAMORPHOSIS,CreatureSpawnEvent:METAMORPHOSIS");
            check("hoglin", "EntityTransformEvent:PIGLIN_ZOMBIFIED,CreatureSpawnEvent:PIGLIN_ZOMBIFIED");
            // zombie drowning (DROWNED reasons) is not checked: setConversionTime alone does not convert a zombie outside water
            check("pig lightning", "PigZapEvent,CreatureSpawnEvent:LIGHTNING");
            check("villager lightning", "EntityZapEvent,EntityTransformEvent:LIGHTNING");
            check("sheep", "SheepRegrowWoolEvent");
            check("zombified piglin", "PigZombieAngerEvent");
        });
        later(100, () -> {
            check("hopper search", "HopperInventorySearchEvent");
            check("witch potions", "WitchReadyPotionEvent,WitchConsumePotionEvent,WitchThrowPotionEvent");
            check("portal blocks", "EntityInsideBlockEvent,EntityPortalEnterEvent,EntityPortalReadyEvent,EntityPortalEvent");
            check("portal create (fire)", "PortalCreateEvent");
            check("dispenser armor", "BlockDispenseArmorEvent");
            check("whitelist", "WhitelistToggleEvent,WhitelistStateUpdateEvent");
        });
        later(500, () -> {
            check("resources reload", "ServerResourcesReloadedEvent");
            if (turtleRef != null) try { Object t = handle(turtleRef); java.lang.reflect.Field f = t.getClass().getDeclaredField("layEggCounter"); f.setAccessible(true); notes.add("turtle layEggCounter=" + f.get(t) + " layingEgg=" + t.getClass().getMethod("isLayingEgg").invoke(t)); } catch (Throwable x) { notes.add("turtle reflect " + x); }
            if (turtleRef != null) notes.add("turtle valid=" + turtleRef.isValid() + " loc=" + turtleRef.getLocation().toVector() + " hasEgg=" + turtleRef.hasEgg() + " ai=" + turtleRef.hasAI() + " home=" + turtleRef.getHome());
            check("turtle go home", "TurtleGoHomeEvent");
            notes.add("turtle digging/laying (timing dependent, not asserted): StartDigging=" + counts.getOrDefault("TurtleStartDiggingEvent", 0) + " LayEgg=" + counts.getOrDefault("TurtleLayEggEvent", 0));
        });
        later(1110, () -> {
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
