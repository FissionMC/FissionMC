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
    private EventProbe events; // created by the first /fissiontest ev* command (events-test.sh): counts every event, see EventProbe
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
            if (args.length >= 1 && args[0].startsWith("ev")) {
                if (events == null) { events = new EventProbe(this); events.register(); }
                switch (args[0]) {
                    case "evrun" -> events.run();
                    case "evreport" -> events.report();
                    default -> { return false; }
                }
                getLogger().info("EVDONE " + String.join(" ", args));
                return true;
            }
            // Vanilla command permission path (Paper "Vanilla command permission fixes"): evaluates the dispatcher requirements for a non-op sender
            if (args.length == 1 && args[0].equals("cmdperm")) {
                int bad = 0;
                java.util.List<String> out = new java.util.ArrayList<>();
                org.bukkit.command.CommandSender who = getServer().createCommandSender(c -> { });
                Object dispatcher = Class.forName("net.minecraft.server.MinecraftServer").getMethod("getCommands").invoke(Class.forName("org.bukkit.craftbukkit.CraftServer").getMethod("getServer").invoke(getServer()));
                dispatcher = dispatcher.getClass().getMethod("getDispatcher").invoke(dispatcher);
                Object root = dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);
                Class<?> cn = Class.forName("com.mojang.brigadier.tree.CommandNode");
                Object stack = Class.forName("org.bukkit.craftbukkit.command.VanillaCommandWrapper").getMethod("getListener", org.bukkit.command.CommandSender.class).invoke(null, who);
                java.lang.reflect.Method getChild = cn.getMethod("getChild", String.class), canUse = cn.getMethod("canUse", Object.class);
                java.util.function.BiFunction<String, org.bukkit.command.CommandSender, String> probe = (name, snd) -> {
                    try {
                        Object node = getChild.invoke(root, name);
                        if (node == null) return "missing";
                        Object st = Class.forName("org.bukkit.craftbukkit.command.VanillaCommandWrapper").getMethod("getListener", org.bukkit.command.CommandSender.class).invoke(null, snd);
                        Object none = Class.forName("net.minecraft.server.permissions.PermissionSet").getField("NO_PERMISSIONS").get(null);
                        st = st.getClass().getMethod("withPermission", Class.forName("net.minecraft.server.permissions.PermissionSet")).invoke(st, none); // like a non-op player: the vanilla level grants nothing
                        return String.valueOf(canUse.invoke(node, st));
                    } catch (Throwable t) { return "error " + t; }
                };
                org.bukkit.permissions.PermissionAttachment att = who.addAttachment(this); // the createCommandSender sender is op-like: deny explicitly
                for (String denied : new String[] {"gamemode", "give", "op"}) att.setPermission("minecraft.command." + denied, false);
                String[][] expect = {
                    {"gamemode", "false"}, {"minecraft:gamemode", "false"}, {"give", "false"}, {"minecraft:give", "false"}, {"op", "false"}, {"me", "true"}, {"minecraft:me", "true"}, {"msg", "true"}};
                for (String[] e : expect) { String r = probe.apply(e[0], who); boolean ok = r.equals(e[1]); if (!ok) bad++; out.add(e[0] + "=" + r + (ok ? "" : "(expected " + e[1] + ")")); }
                att.setPermission("minecraft.command.gamemode", true);
                att.setPermission("minecraft.command.me", false);
                String[][] expect2 = {{"gamemode", "true"}, {"minecraft:gamemode", "true"}, {"give", "false"}, {"me", "false"}, {"minecraft:me", "false"}};
                for (String[] e : expect2) { String r = probe.apply(e[0], who); boolean ok = r.equals(e[1]); if (!ok) bad++; out.add("attached " + e[0] + "=" + r + (ok ? "" : "(expected " + e[1] + ")")); }
                getLogger().info("CMDPERM " + (bad == 0 ? "OK" : "FAIL") + " " + String.join(" ", out));
                sender.sendMessage("cmdperm bad=" + bad);
                return true;
            }
            // forces every mixin target class to load and transform: an injection that does not apply (defaultRequire 1) throws here instead of at some player's first use
            if (args.length == 1 && args[0].equals("audit")) {
                try {
                    Class<?> env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
                    Object cur = env.getMethod("getCurrentEnvironment").invoke(null);
                    env.getMethod("audit").invoke(cur);
                    getLogger().info("AUDIT done");
                } catch (Throwable t) { getLogger().warning("AUDIT failed: " + t + (t.getCause() != null ? " / " + t.getCause() : "")); }
                sender.sendMessage("audit finished");
                return true;
            }
            // bulk edit simulations of what WorldEdit does (design/plugin-compat.md): <mode> = api (Block.setBlockData) | nms (LevelChunk.setBlockState through reflection, like WorldEdit's NMS adapters)
            if (args.length == 7 && (args[0].equals("bulkset") || args[0].equals("bulkcount"))) {
                org.bukkit.World w = getServer().getWorld(args[1]);
                int x = Integer.parseInt(args[2]), y = Integer.parseInt(args[3]), z = Integer.parseInt(args[4]), n = Integer.parseInt(args[5]);
                String[] mm = args[6].split(":"); // <api|nms>:<MATERIAL>
                org.bukkit.Material mat = org.bukkit.Material.valueOf(mm[1]);
                if (args[0].equals("bulkcount")) {
                    int c = 0;
                    for (int i = 0; i < n; i++) if (w.getBlockAt(x + i, y, z).getType() == mat) c++;
                    getLogger().info("BULK count=" + c + " of " + n + " " + mat + " at " + x + "," + y + "," + z);
                    sender.sendMessage("count=" + c);
                    return true;
                }
                org.bukkit.block.data.BlockData data = mat.createBlockData();
                if (mm[0].equals("api")) {
                    for (int i = 0; i < n; i++) w.getBlockAt(x + i, y, z).setBlockData(data, false);
                } else {
                    Object level = Class.forName("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle").invoke(w);
                    Class<?> bp = Class.forName("net.minecraft.core.BlockPos"), bs = Class.forName("net.minecraft.world.level.block.state.BlockState");
                    Object state = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData").getMethod("getState").invoke(data);
                    java.lang.reflect.Method getChunkAt = Class.forName("net.minecraft.world.level.Level").getMethod("getChunkAt", bp);
                    java.lang.reflect.Method set = Class.forName("net.minecraft.world.level.chunk.LevelChunk").getMethod("setBlockState", bp, bs, int.class);
                    for (int i = 0; i < n; i++) {
                        Object pos = bp.getConstructor(int.class, int.class, int.class).newInstance(x + i, y, z);
                        set.invoke(getChunkAt.invoke(level, pos), pos, state, 2);
                    }
                }
                getLogger().info("BULK set " + n + " " + mat + " via " + mm[0] + " at " + x + "," + y + "," + z);
                sender.sendMessage("set " + n);
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
