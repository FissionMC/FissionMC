package net.fissionmc.testplugin;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * /fissiontest plugincheck : the plugin-facing platform in one go (what real plugins do at enable time and in their first commands).
 * The checks that need ticks (schedulers) report later; after {@link #WAIT_TICKS} ticks every check prints
 * "PLUGINCHECK PASS|FAIL|INFO <name> <detail>" to the log and to the sender, then "PLUGINCHECK DONE pass=N fail=M info=K".
 * Brigadier / lifecycle commands (fissionbrig, fbrig, fissionbrigperm, fissionboom) are registered by {@link #registerBrigadier} from TestPlugin#onEnable.
 */
final class PluginCheck {
    static final int WAIT_TICKS = 60;

    // ---- state shared with the Brigadier handlers registered in onEnable ----
    static final AtomicInteger COMMANDS_EVENTS = new AtomicInteger();
    static volatile String commandsCause = "none";
    static volatile int brigValue = -1;
    static volatile String brigAliasUsed = "";
    static volatile boolean brigPermRan = false;
    static volatile boolean checkCommandRan = false;
    static final AtomicInteger SVC_REGISTERED = new AtomicInteger();
    static final AtomicInteger SVC_UNREGISTERED = new AtomicInteger();

    /** Called from onEnable: LifecycleEvents.COMMANDS (Paper's Brigadier API). */
    static void registerBrigadier(TestPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            COMMANDS_EVENTS.incrementAndGet();
            commandsCause = event.cause().name();
            Commands r = event.registrar();
            r.register(Commands.literal("fissionbrig")
                .then(Commands.argument("n", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                    brigValue = IntegerArgumentType.getInteger(ctx, "n");
                    ctx.getSource().getSender().sendMessage("fissionbrig " + brigValue);
                    return 1;
                })).build(), "fissiontest brigadier command", List.of("fbrig"));
            r.register(Commands.literal("fissionbrigperm")
                .requires(s -> s.getSender().hasPermission("fissiontest.brig"))
                .executes(ctx -> { brigPermRan = true; return 1; }).build());
            r.register(Commands.literal("fissionboom").executes(ctx -> { throw new IllegalStateException("boom (expected by plugincheck)"); }).build());
        });
    }

    /** Test listener for the services events. */
    static final class SvcListener implements Listener {
        @EventHandler public void on(org.bukkit.event.server.ServiceRegisterEvent e) { if (e.getProvider().getService() == FissionSvc.class) SVC_REGISTERED.incrementAndGet(); }
        @EventHandler public void on(org.bukkit.event.server.ServiceUnregisterEvent e) { if (e.getProvider().getService() == FissionSvc.class) SVC_UNREGISTERED.incrementAndGet(); }
    }

    interface FissionSvc { String id(); }

    /** A ConfigurationSerializable of our own. */
    public static final class Pair implements ConfigurationSerializable {
        final String a; final int b;
        Pair(String a, int b) { this.a = a; this.b = b; }
        @Override public Map<String, Object> serialize() { return Map.of("a", a, "b", b); }
        public static Pair deserialize(Map<String, Object> m) { return new Pair((String) m.get("a"), (Integer) m.get("b")); }
        @Override public boolean equals(Object o) { return o instanceof Pair p && p.a.equals(a) && p.b == b; }
        @Override public int hashCode() { return a.hashCode() * 31 + b; }
    }

    private interface Check { String run() throws Throwable; }

    private final TestPlugin plugin;
    private final CommandSender sender;
    private final Map<String, String> results = new ConcurrentHashMap<>();   // name -> "PASS detail" / "FAIL detail" / "INFO detail"
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());
    private final List<String> asyncNames = Collections.synchronizedList(new ArrayList<>());

    PluginCheck(TestPlugin plugin, CommandSender sender) { this.plugin = plugin; this.sender = sender; }

    private static void req(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    private void sync(String name, Check c) {
        order.add(name);
        try { String d = c.run(); results.put(name, "PASS " + (d == null ? "" : d)); }
        catch (Throwable t) { results.put(name, "FAIL " + t); }
    }

    private void info(String name, String detail) { order.add(name); results.put(name, "INFO " + detail); }

    /** Declares an async check; the caller completes it with pass()/fail(). */
    private void expectLater(String name) { order.add(name); asyncNames.add(name); }
    private void pass(String name, String detail) { results.putIfAbsent(name, "PASS " + detail); }
    private void fail(String name, String detail) { results.putIfAbsent(name, "FAIL " + detail); }

    void run() {
        platform();
        scheduler();
        folia();
        services();
        unsafe();
        commands();
        permissions();
        config();
        messaging();
        pluginManager();
        dataComponents();
        net();
        Bukkit.getScheduler().runTaskLater(plugin, this::report, WAIT_TICKS);
        sender.sendMessage("plugincheck started; the report follows in " + WAIT_TICKS + " ticks (see the log for PLUGINCHECK lines)");
    }

    // ------------------------------------------------------------------------------------------------ platform / versions

    private void platform() {
        sync("version.getVersion", () -> {
            String v = Bukkit.getVersion();
            int n = v.split("\\(MC: ", -1).length - 1;
            req(n == 1, "'(MC: ' occurs " + n + " times in '" + v + "'");
            req(v.contains(Bukkit.getMinecraftVersion()), "no minecraft version in " + v);
            return v;
        });
        sync("version.bukkitVersion", () -> {
            String v = Bukkit.getBukkitVersion();
            req(v.matches("\\d+\\.\\d+(\\.\\d+)?-R\\d+\\.\\d+(-SNAPSHOT)?"), "unexpected '" + v + "'");
            return v;
        });
        sync("version.minecraftVersion", () -> {
            req(Bukkit.getMinecraftVersion().equals(io.papermc.paper.ServerBuildInfo.buildInfo().minecraftVersionId()), "differs from ServerBuildInfo");
            return Bukkit.getMinecraftVersion();
        });
        sync("version.buildInfo", () -> {
            io.papermc.paper.ServerBuildInfo bi = io.papermc.paper.ServerBuildInfo.buildInfo();
            req(bi.isBrandCompatible(io.papermc.paper.ServerBuildInfo.BRAND_PAPER_ID), "not Paper compatible");
            return bi.brandName() + " " + bi.asString(io.papermc.paper.ServerBuildInfo.StringRepresentation.VERSION_FULL);
        });
        sync("platform.notFolia", () -> {
            try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); } catch (ClassNotFoundException e) { return "RegionizedServer absent (Paper, not Folia)"; }
            throw new AssertionError("RegionizedServer exists: plugins would treat this as Folia");
        });
        sync("platform.paperClasses", () -> {
            Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Class.forName("org.bukkit.craftbukkit.CraftServer");
            Class.forName("net.minecraft.server.MinecraftServer");
            req(Bukkit.getServer().getClass().getName().equals("org.bukkit.craftbukkit.CraftServer"), Bukkit.getServer().getClass().getName());
            return "CraftServer/MinecraftServer/GlobalConfiguration loadable by name";
        });
        sync("platform.pluginsFolder", () -> {
            File f = Bukkit.getPluginsFolder();
            req(f.isDirectory(), f + " is not a directory");
            req(plugin.getDataFolder().getParentFile().toPath().toAbsolutePath().normalize().equals(f.toPath().toAbsolutePath().normalize()), "data folder parent " + plugin.getDataFolder().getParentFile() + " != " + f);
            return f.getPath();
        });
        sync("platform.threads", () -> {
            World w = Bukkit.getWorlds().get(0);
            Location l = w.getSpawnLocation();
            req(Bukkit.isPrimaryThread(), "not the primary thread");
            req(Bukkit.isGlobalTickThread(), "isGlobalTickThread false on the main thread");
            req(Bukkit.isOwnedByCurrentRegion(l), "isOwnedByCurrentRegion(spawn) false");
            req(Bukkit.isOwnedByCurrentRegion(w, l.getBlockX() >> 4, l.getBlockZ() >> 4), "isOwnedByCurrentRegion(chunk) false");
            req(!Bukkit.isStopping(), "isStopping");
            return "tick=" + Bukkit.getCurrentTick();
        });
        sync("platform.tps", () -> {
            double[] tps = Bukkit.getTPS();
            req(tps.length >= 3 && tps[0] > 0, "getTPS " + java.util.Arrays.toString(tps));
            return "tps1m=" + tps[0] + " mspt=" + Bukkit.getAverageTickTime();
        });
        sync("platform.scoreboard", () -> { req(Bukkit.getScoreboardManager() != null, "no ScoreboardManager"); return null; });
        sync("platform.worldContainer", () -> { req(Bukkit.getWorldContainer().isDirectory(), "world container"); return Bukkit.getWorldContainer().getPath(); });
    }

    // ------------------------------------------------------------------------------------------------ Bukkit scheduler

    private void scheduler() {
        final BukkitScheduler s = Bukkit.getScheduler();
        final int startTick = Bukkit.getCurrentTick();

        expectLater("sched.runTask");
        s.runTask(plugin, () -> { if (Bukkit.isPrimaryThread()) pass("sched.runTask", "ran on " + Thread.currentThread().getName()); else fail("sched.runTask", "wrong thread " + Thread.currentThread().getName()); });

        expectLater("sched.runTaskLater");
        s.runTaskLater(plugin, () -> {
            int d = Bukkit.getCurrentTick() - startTick;
            if (d >= 2 && d <= 6) pass("sched.runTaskLater", "delay 3 ticks ran after " + d); else fail("sched.runTaskLater", "delay 3 ticks ran after " + d);
        }, 3);

        expectLater("sched.runTaskTimer");
        final AtomicInteger runs = new AtomicInteger();
        s.runTaskTimer(plugin, new BukkitRunnable() {
            @Override public void run() {
                if (runs.incrementAndGet() == 3) { cancel(); if (isCancelled()) pass("sched.runTaskTimer", "3 runs then cancelled"); else fail("sched.runTaskTimer", "not cancelled"); }
            }
        }, 1, 2);

        expectLater("sched.async");
        s.runTaskAsynchronously(plugin, () -> {
            if (!Bukkit.isPrimaryThread()) pass("sched.async", "ran on " + Thread.currentThread().getName()); else fail("sched.async", "ran on the primary thread");
        });

        expectLater("sched.callSyncMethod");
        s.runTaskAsynchronously(plugin, () -> {
            try {
                Boolean onMain = s.callSyncMethod(plugin, (Callable<Boolean>) Bukkit::isPrimaryThread).get(10, TimeUnit.SECONDS);
                if (onMain) pass("sched.callSyncMethod", "future completed on the primary thread"); else fail("sched.callSyncMethod", "callable ran off the primary thread");
            } catch (Throwable t) { fail("sched.callSyncMethod", t.toString()); }
        });

        expectLater("sched.async.offMainChecks");
        s.runTaskAsynchronously(plugin, () -> {
            boolean g = Bukkit.isGlobalTickThread();
            boolean o = Bukkit.isOwnedByCurrentRegion(Bukkit.getWorlds().get(0).getSpawnLocation());
            if (!g && !o && !Bukkit.isPrimaryThread()) pass("sched.async.offMainChecks", "isGlobalTickThread/isOwnedByCurrentRegion/isPrimaryThread are false off the tick thread");
            else fail("sched.async.offMainChecks", "global=" + g + " owned=" + o + " primary=" + Bukkit.isPrimaryThread());
        });

        sync("sched.queuedCancel", () -> {
            BukkitTask t = s.runTaskLater(plugin, () -> { }, 1000);
            req(s.isQueued(t.getTaskId()), "not queued");
            req(!t.isCancelled(), "cancelled early");
            t.cancel();
            req(t.isCancelled(), "not cancelled");
            req(!s.isQueued(t.getTaskId()), "still queued after cancel");
            req(t.getOwner() == plugin, "owner");
            return "id=" + t.getTaskId();
        });

        sync("sched.pendingTasks", () -> {
            BukkitTask t = s.runTaskLater(plugin, () -> { }, 1000);
            boolean seen = s.getPendingTasks().stream().anyMatch(p -> p.getTaskId() == t.getTaskId());
            t.cancel();
            req(seen, "task missing in getPendingTasks");
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ Folia-style schedulers (what plugins use when they detect "regions")

    private void folia() {
        final World w = Bukkit.getWorlds().get(0);
        final Location spawn = w.getSpawnLocation();

        expectLater("folia.global.run");
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> { if (Bukkit.isPrimaryThread()) pass("folia.global.run", "ran on the primary thread"); else fail("folia.global.run", "off thread"); });

        expectLater("folia.global.execute");
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> pass("folia.global.execute", "ok"));

        expectLater("folia.global.delayed");
        final int startTick = Bukkit.getCurrentTick();
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            int d = Bukkit.getCurrentTick() - startTick;
            if (d >= 2 && d <= 6) pass("folia.global.delayed", "after " + d + " ticks"); else fail("folia.global.delayed", "after " + d + " ticks");
        }, 3);

        expectLater("folia.global.timer");
        final AtomicInteger runs = new AtomicInteger();
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            if (runs.incrementAndGet() == 3) { t.cancel(); if (t.isCancelled()) pass("folia.global.timer", "3 runs then cancelled"); else fail("folia.global.timer", "not cancelled"); }
        }, 1, 2);

        expectLater("folia.region.run");
        Bukkit.getRegionScheduler().run(plugin, spawn, t -> pass("folia.region.run", "ran at spawn region"));
        expectLater("folia.region.execute");
        Bukkit.getRegionScheduler().execute(plugin, w, spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, () -> pass("folia.region.execute", "ok"));

        expectLater("folia.async.runNow");
        Bukkit.getAsyncScheduler().runNow(plugin, t -> { if (!Bukkit.isPrimaryThread()) pass("folia.async.runNow", Thread.currentThread().getName()); else fail("folia.async.runNow", "on the primary thread"); });
        expectLater("folia.async.delayed");
        Bukkit.getAsyncScheduler().runDelayed(plugin, t -> pass("folia.async.delayed", "ok"), 100, TimeUnit.MILLISECONDS);

        // EntityScheduler on a temporary marker entity (a player would do as well)
        expectLater("folia.entity.run");
        try {
            final Entity marker = w.spawnEntity(spawn, EntityType.MARKER);
            ScheduledTask st = marker.getScheduler().run(plugin, t -> {
                if (Bukkit.isPrimaryThread() && Bukkit.isOwnedByCurrentRegion(marker)) pass("folia.entity.run", "ran for entity " + marker.getUniqueId()); else fail("folia.entity.run", "wrong thread/region");
                marker.remove();
            }, () -> fail("folia.entity.run", "retired callback ran"));
            if (st == null) { fail("folia.entity.run", "scheduler returned null"); marker.remove(); }
            expectLater("folia.entity.delayed");
            marker.getScheduler().runDelayed(plugin, t -> pass("folia.entity.delayed", "ok"), () -> { }, 2);
        } catch (Throwable t) { fail("folia.entity.run", t.toString()); }
    }

    // ------------------------------------------------------------------------------------------------ ServicesManager

    private void services() {
        final ServicesManager sm = Bukkit.getServicesManager();
        final int reg0 = SVC_REGISTERED.get(), unreg0 = SVC_UNREGISTERED.get();
        final FissionSvc low = () -> "low";
        final FissionSvc high = () -> "high";
        sync("services.priority", () -> {
            sm.register(FissionSvc.class, low, plugin, ServicePriority.Low);
            sm.register(FissionSvc.class, high, plugin, ServicePriority.High);
            try {
                RegisteredServiceProvider<FissionSvc> reg = sm.getRegistration(FissionSvc.class);
                req(reg != null && reg.getProvider().id().equals("high"), "highest priority provider not first: " + (reg == null ? null : reg.getProvider().id()));
                req(sm.load(FissionSvc.class).id().equals("high"), "load()");
                req(sm.isProvidedFor(FissionSvc.class), "isProvidedFor");
                req(sm.getKnownServices().contains(FissionSvc.class), "getKnownServices");
                req(sm.getRegistrations(FissionSvc.class).size() == 2, "getRegistrations(class) size");
                req(sm.getRegistrations(plugin).stream().anyMatch(r -> r.getService() == FissionSvc.class), "getRegistrations(plugin)");
                sm.unregister(FissionSvc.class, high);
                req(sm.getRegistration(FissionSvc.class).getProvider().id().equals("low"), "fallback to low after unregister");
                return "ok";
            } finally { sm.unregisterAll(plugin); }
        });
        sync("services.unregisterAll", () -> { req(!sm.isProvidedFor(FissionSvc.class), "provider left after unregisterAll"); return null; });
        // the events are delivered synchronously: the listener was registered in onEnable
        sync("services.events", () -> {
            int r = SVC_REGISTERED.get() - reg0, u = SVC_UNREGISTERED.get() - unreg0;
            req(r == 2, "ServiceRegisterEvent x" + r + " (expected 2)");
            req(u >= 2, "ServiceUnregisterEvent x" + u + " (expected 2: one unregister + one unregisterAll)");
            return "register=" + r + " unregister=" + u;
        });
        info("services.known", sm.getKnownServices().toString() + " (Vault/Chat/Economy/Permission providers of other plugins show up here)");
    }

    // ------------------------------------------------------------------------------------------------ UnsafeValues

    @SuppressWarnings("deprecation")
    private void unsafe() {
        final org.bukkit.UnsafeValues u = Bukkit.getUnsafe();
        sync("unsafe.dataVersion", () -> { req(u.getDataVersion() > 4000, "dataVersion " + u.getDataVersion()); return "dataVersion=" + u.getDataVersion() + " protocol=" + u.getProtocolVersion(); });
        sync("unsafe.legacyMaterials", () -> {
            Material m = u.fromLegacy(new MaterialData(Material.LEGACY_WOOL, (byte) 14));
            req(m == Material.RED_WOOL, "fromLegacy(WOOL:14) = " + m);
            Material back = u.toLegacy(Material.RED_WOOL);
            req(back == Material.LEGACY_WOOL, "toLegacy(RED_WOOL) = " + back);
            req(u.fromLegacy(Material.LEGACY_WOOL, (byte) 5).getMaterial() == Material.LIME_WOOL, "fromLegacy(WOOL, 5)");
            req(Material.getMaterial("WOOL", true) == Material.LEGACY_WOOL, "Material.getMaterial(\"WOOL\", legacy)");
            req(u.getMaterial("STONE", u.getDataVersion()) == Material.STONE, "getMaterial(STONE, current)");
            return "WOOL:14 -> " + m;
        });
        sync("unsafe.itemJson", () -> {
            ItemStack s = new ItemStack(Material.DIAMOND_SWORD, 1);
            ItemMeta meta = s.getItemMeta();
            meta.displayName(Component.text("Json Sword"));
            s.setItemMeta(meta);
            ItemStack back = u.deserializeItemFromJson(u.serializeItemAsJson(s));
            req(s.equals(back), "json round trip: " + back);
            return null;
        });
        sync("unsafe.itemBytes", () -> {
            ItemStack s = new ItemStack(Material.EMERALD, 7);
            req(s.equals(ItemStack.deserializeBytes(s.serializeAsBytes())), "serializeAsBytes round trip");
            return null;
        });
        sync("unsafe.modifyItemStack", () -> {
            ItemStack s = new ItemStack(Material.STONE);
            ItemStack r = u.modifyItemStack(s, "[max_stack_size=16]");
            return "max stack size now " + r.getMaxStackSize();
        });
        sync("unsafe.registry", () -> {
            req(u.get(RegistryKey.ENCHANTMENT, NamespacedKey.minecraft("sharpness")) == Enchantment.SHARPNESS, "get(ENCHANTMENT, sharpness)");
            return null;
        });
        sync("unsafe.apiVersions", () -> {
            req(u.isSupportedApiVersion("26.3"), "26.3 unsupported");
            req(u.isSupportedApiVersion("1.13"), "1.13 unsupported");
            req(!u.isSupportedApiVersion("99.9"), "99.9 supported");
            req(!org.bukkit.UnsafeValues.isLegacyPlugin(plugin), "this plugin is legacy?");
            return null;
        });
        sync("unsafe.mainLevelName", () -> {
            String n = u.getMainLevelName();
            req(n != null && !n.isEmpty(), "main level name '" + n + "'");
            return n;
        });
        sync("unsafe.nextEntityId", () -> {
            World w = Bukkit.getWorlds().get(0);
            int a = u.nextEntityId(w), b = u.nextEntityId(w);
            req(a != b, "ids not distinct: " + a + " " + b);
            return a + "," + b;
        });
    }

    // ------------------------------------------------------------------------------------------------ commands

    private void commands() {
        final org.bukkit.command.CommandMap map = Bukkit.getCommandMap();
        sync("cmd.pluginYml", () -> {
            org.bukkit.command.PluginCommand c = plugin.getCommand("fissioncheck");
            req(c != null, "getCommand(fissioncheck) null (plugin.yml command)");
            req(c.getExecutor() == plugin, "executor is not the plugin");
            req("fissiontest.check".equals(c.getPermission()), "permission " + c.getPermission());
            req(map.getCommand("fchk") == c, "alias fchk does not resolve to the command");
            req(map.getKnownCommands().containsKey("fissiontest:fissioncheck"), "no namespaced label");
            req(map.getKnownCommands().containsKey("fissioncheck"), "no plain label");
            return "aliases=" + c.getAliases();
        });
        sync("cmd.dynamicDispatchAndTab", () -> {
            final List<String> got = new ArrayList<>();
            Command dyn = new Command("fissiondyn", "dyn", "/fissiondyn", List.of("fdyn")) {
                @Override public boolean execute(CommandSender s, String label, String[] args) { got.add(label + ":" + String.join(",", args)); return true; }
                @Override public List<String> tabComplete(CommandSender s, String alias, String[] args) { return List.of("alpha", "beta"); }
            };
            map.register("fissiontest", dyn);
            try {
                CommandSender console = Bukkit.getConsoleSender();
                req(Bukkit.dispatchCommand(console, "fissiondyn a b"), "dispatch returned false");
                req(Bukkit.dispatchCommand(console, "fdyn c"), "alias dispatch returned false");
                req(Bukkit.dispatchCommand(console, "fissiontest:fissiondyn d"), "namespaced dispatch returned false");
                req(got.equals(List.of("fissiondyn:a,b", "fdyn:c", "fissiondyn:d")), "got " + got);
                List<String> tab = map.tabComplete(console, "fissiondyn ");
                req(tab != null && tab.equals(List.of("alpha", "beta")), "tab " + tab);
                return got.toString();
            } finally {
                dyn.unregister(map);
                map.getKnownCommands().values().removeIf(c -> c == dyn);
            }
        });
        sync("cmd.unknown", () -> { req(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fission-no-such-command"), "unknown command reported success"); return null; });
        sync("cmd.noPermission", () -> {
            checkCommandRan = false;
            CommandSender nobody = Bukkit.createCommandSender(c -> { });
            PermissionAttachment att = nobody.addAttachment(plugin, "fissiontest.check", false);
            try {
                boolean ok = Bukkit.dispatchCommand(nobody, "fissioncheck");
                req(!checkCommandRan, "command ran although the sender lacks fissiontest.check (dispatch=" + ok + ")");
            } finally { nobody.removeAttachment(att); }
            return null;
        });
        sync("cmd.brigadier.events", () -> {
            req(COMMANDS_EVENTS.get() >= 1, "LifecycleEvents.COMMANDS never fired for this plugin");
            return "fired x" + COMMANDS_EVENTS.get() + " last cause " + commandsCause;
        });
        sync("cmd.brigadier.dispatch", () -> {
            CommandSender console = Bukkit.getConsoleSender();
            brigValue = -1;
            req(Bukkit.dispatchCommand(console, "fissionbrig 7"), "fissionbrig 7 returned false");
            req(brigValue == 7, "executed with " + brigValue);
            req(Bukkit.dispatchCommand(console, "fbrig 8") && brigValue == 8, "alias fbrig -> " + brigValue);
            req(Bukkit.dispatchCommand(console, "fissiontest:fissionbrig 9") && brigValue == 9, "namespaced -> " + brigValue);
            return null;
        });
        sync("cmd.brigadier.permission", () -> {
            brigPermRan = false;
            CommandSender nobody = Bukkit.createCommandSender(c -> { });
            PermissionAttachment att = nobody.addAttachment(plugin, "fissiontest.brig", false);
            try { Bukkit.dispatchCommand(nobody, "fissionbrigperm"); req(!brigPermRan, "requires() ignored"); } finally { nobody.removeAttachment(att); }
            return null;
        });
        sync("cmd.brigadier.exceptionPropagates", () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fissionboom");
            } catch (CommandException e) {
                return "CommandException: " + e.getCause();
            }
            throw new AssertionError("an exception thrown by a Brigadier command was swallowed (Paper rethrows it to the dispatchCommand caller as CommandException)");
        });
        sync("cmd.vanillaPresent", () -> {
            req(map.getCommand("minecraft:give") != null || map.getCommand("give") != null, "vanilla /give not in the command map");
            return null;
        });
        info("cmd.hint", "also try by hand: /fissioncheck, /fchk, /fbrig 5 (tab complete shows 0..100), /fissionbrigperm as a non-op, /fissiontest:fissionbrig 3, /minecraft:reload and then /fissionbrig 1 again (must still exist)");
    }

    // ------------------------------------------------------------------------------------------------ permissions

    private void permissions() {
        final PluginManager pm = Bukkit.getPluginManager();
        sync("perm.plugin.yml", () -> {
            Permission check = pm.getPermission("fissiontest.check");
            req(check != null && check.getDefault() == PermissionDefault.OP, "fissiontest.check " + check);
            Permission admin = pm.getPermission("fissiontest.admin");
            req(admin != null && Boolean.TRUE.equals(admin.getChildren().get("fissiontest.check")), "fissiontest.admin children " + (admin == null ? null : admin.getChildren()));
            Permission free = pm.getPermission("fissiontest.free");
            req(free != null && free.getDefault() == PermissionDefault.TRUE, "fissiontest.free " + free);
            return null;
        });
        sync("perm.dynamic", () -> {
            Permission p = new Permission("fissiontest.dynamic", "dyn", PermissionDefault.FALSE);
            pm.addPermission(p);
            try {
                req(pm.getPermission("fissiontest.dynamic") == p, "not registered");
                req(pm.getDefaultPermissions(false).contains(p) == false, "FALSE default listed for non-ops");
                try { pm.addPermission(p); throw new AssertionError("duplicate permission accepted"); } catch (IllegalArgumentException expected) { }
            } finally { pm.removePermission(p); }
            req(pm.getPermission("fissiontest.dynamic") == null, "still registered");
            return null;
        });
        sync("perm.attachment", () -> {
            CommandSender who = Bukkit.createCommandSender(c -> { });
            PermissionAttachment att = who.addAttachment(plugin, "fissiontest.attached", false);
            try {
                req(who.isPermissionSet("fissiontest.attached") && !who.hasPermission("fissiontest.attached"), "explicit false ignored");
                att.setPermission("fissiontest.attached", true);
                req(who.hasPermission("fissiontest.attached"), "attachment grant ignored");
                att.unsetPermission("fissiontest.attached");
                req(!who.isPermissionSet("fissiontest.attached"), "unset ignored");
            } finally { who.removeAttachment(att); }
            // children: an attachment granting the parent grants the child
            PermissionAttachment parent = who.addAttachment(plugin, "fissiontest.admin", true);
            try { req(who.hasPermission("fissiontest.check"), "child permission of fissiontest.admin not granted"); } finally { who.removeAttachment(parent); }
            return null;
        });
        sync("perm.console", () -> { req(Bukkit.getConsoleSender().hasPermission("fissiontest.anything") && Bukkit.getConsoleSender().isOp(), "console not op/all permissions"); return null; });
        info("perm.permissionsYml", "put {fissiontest.fromyml: {default: true}} into permissions.yml (server root) before boot and check pm.getPermission(\"fissiontest.fromyml\"): " + (pm.getPermission("fissiontest.fromyml") != null ? "present" : "absent"));
    }

    // ------------------------------------------------------------------------------------------------ configuration serialization

    private void config() {
        ConfigurationSerialization.registerClass(Pair.class);
        sync("config.roundTrip", () -> {
            YamlConfiguration y = new YamlConfiguration();
            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 1);
            ItemMeta meta = sword.getItemMeta();
            meta.displayName(Component.text("Serialized"));
            meta.lore(List.of(Component.text("lore 1")));
            meta.addEnchant(Enchantment.SHARPNESS, 3, true);
            sword.setItemMeta(meta);
            Location loc = new Location(Bukkit.getWorlds().get(0), 1.5, 64, -2.5, 90f, 10f);
            y.set("sword", sword);
            y.set("loc", loc);
            y.set("vec", new Vector(1, 2, 3));
            y.set("pair", new Pair("x", 5));
            y.set("bb", new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1));
            y.set("color", org.bukkit.Color.fromRGB(10, 20, 30));
            String text = y.saveToString();
            YamlConfiguration back = new YamlConfiguration();
            back.loadFromString(text);
            req(sword.equals(back.getItemStack("sword")), "ItemStack: " + back.get("sword"));
            req(loc.equals(back.getLocation("loc")), "Location: " + back.get("loc"));
            req(new Vector(1, 2, 3).equals(back.getVector("vec")), "Vector");
            req(new Pair("x", 5).equals(back.get("pair")), "custom ConfigurationSerializable: " + back.get("pair"));
            req(new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1).equals(back.get("bb")), "BoundingBox");
            req(org.bukkit.Color.fromRGB(10, 20, 30).equals(back.getColor("color")), "Color");
            return text.length() + " chars";
        });
        sync("config.file", () -> {
            File f = new File(plugin.getDataFolder(), "plugincheck.yml");
            plugin.getDataFolder().mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("a.b", 5);
            y.set("list", List.of("x", "y"));
            y.save(f);
            YamlConfiguration l = YamlConfiguration.loadConfiguration(f);
            req(l.getInt("a.b") == 5 && l.getStringList("list").size() == 2, "file round trip");
            f.delete();
            return null;
        });
        sync("config.pluginResource", () -> { req(plugin.getResource("plugin.yml") != null, "getResource(plugin.yml) null"); return null; });
    }

    // ------------------------------------------------------------------------------------------------ plugin messaging

    private void messaging() {
        final Messenger m = Bukkit.getMessenger();
        sync("messaging.channels", () -> {
            m.registerOutgoingPluginChannel(plugin, "fissiontest:chk");
            m.registerIncomingPluginChannel(plugin, "fissiontest:chk", (channel, player, message) -> { });
            m.registerOutgoingPluginChannel(plugin, "BungeeCord");
            try {
                req(m.isOutgoingChannelRegistered(plugin, "fissiontest:chk"), "outgoing");
                req(m.isIncomingChannelRegistered(plugin, "fissiontest:chk"), "incoming");
                req(m.isOutgoingChannelRegistered(plugin, "BungeeCord"), "BungeeCord outgoing");
            } finally { m.unregisterOutgoingPluginChannel(plugin); m.unregisterIncomingPluginChannel(plugin); }
            return null;
        });
        info("messaging.hint", "client side (needs a player): /fissiontest plugincheck as a player also sends a message on BungeeCord and fissiontest:chk");
        if (sender instanceof Player p) {
            sync("messaging.send", () -> {
                m.registerOutgoingPluginChannel(plugin, "fissiontest:chk");
                try { p.sendPluginMessage(plugin, "fissiontest:chk", new byte[] {1, 2, 3}); } finally { m.unregisterOutgoingPluginChannel(plugin); }
                return "sent 3 bytes to " + p.getName();
            });
        }
    }

    // ------------------------------------------------------------------------------------------------ plugin manager

    private void pluginManager() {
        final PluginManager pm = Bukkit.getPluginManager();
        sync("pm.self", () -> {
            req(pm.getPlugin("FissionTest") == plugin && pm.isPluginEnabled(plugin), "self lookup");
            req("26.3".equals(plugin.getPluginMeta().getAPIVersion()), "api-version " + plugin.getPluginMeta().getAPIVersion());
            req(plugin.getClass().getClassLoader().getClass().getName().contains("PluginClassLoader"), plugin.getClass().getClassLoader().getClass().getName());
            return plugin.getClass().getClassLoader().getClass().getName();
        });
        sync("pm.builtinPacketEvents", () -> { req(pm.getPlugin("packetevents") != null && pm.isPluginEnabled("packetevents"), "built-in packetevents missing"); return null; });
        StringBuilder sb = new StringBuilder();
        for (org.bukkit.plugin.Plugin p : pm.getPlugins()) sb.append(p.getName()).append(p.isEnabled() ? "" : "(disabled)").append(' ');
        info("pm.loaded", sb.toString().trim());
        sync("pm.registerEventsAndCall", () -> {
            final AtomicInteger n = new AtomicInteger();
            Listener l = new Listener() { @EventHandler public void on(org.bukkit.event.server.PluginEnableEvent e) { n.incrementAndGet(); } };
            pm.registerEvents(l, plugin);
            try {
                pm.callEvent(new org.bukkit.event.server.PluginEnableEvent(plugin));
                req(n.get() == 1, "handler called " + n.get() + " times");
            } finally { org.bukkit.event.HandlerList.unregisterAll(l); }
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ data components

    private void dataComponents() {
        sync("components.roundTrip", () -> {
            ItemStack s = new ItemStack(Material.DIAMOND);
            s.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
            req(Integer.valueOf(16).equals(s.getData(DataComponentTypes.MAX_STACK_SIZE)), "MAX_STACK_SIZE");
            s.setData(DataComponentTypes.ITEM_NAME, Component.text("Components"));
            req(Component.text("Components").equals(s.getData(DataComponentTypes.ITEM_NAME)), "ITEM_NAME");
            req(s.getDataTypes().contains(DataComponentTypes.MAX_STACK_SIZE), "getDataTypes");
            s.unsetData(DataComponentTypes.MAX_STACK_SIZE);
            req(s.getMaxStackSize() == 64, "unsetData: max stack size " + s.getMaxStackSize());
            return "types=" + s.getDataTypes().size();
        });
    }

    // ------------------------------------------------------------------------------------------------ the network (several backends)

    private void net() {
        info("net.onlinePlayers", "getOnlinePlayers() on this backend = " + Bukkit.getOnlinePlayers().size() + " (players of other backends are not in it; they appear as ghosts only)");
        sync("net.worlds", () -> { req(!Bukkit.getWorlds().isEmpty(), "no worlds"); return Bukkit.getWorlds().toString(); });
    }

    // ------------------------------------------------------------------------------------------------ report

    private void report() {
        for (String n : asyncNames) results.putIfAbsent(n, "FAIL never ran within " + WAIT_TICKS + " ticks");
        int pass = 0, fail = 0, inf = 0;
        List<String> lines = new ArrayList<>();
        for (String n : order) {
            String r = results.getOrDefault(n, "FAIL no result");
            if (r.startsWith("PASS")) pass++; else if (r.startsWith("FAIL")) fail++; else inf++;
            lines.add(r.substring(0, 4) + " " + n + (r.length() > 5 ? " " + r.substring(5) : ""));
        }
        for (String l : lines) {
            plugin.getLogger().info("PLUGINCHECK " + l);
            sender.sendMessage(l);
        }
        String done = "PLUGINCHECK DONE pass=" + pass + " fail=" + fail + " info=" + inf;
        plugin.getLogger().info(done);
        sender.sendMessage(done);
    }
}
