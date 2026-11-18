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
        // marker file plugins/FissionTest/audit-on-enable (events-test.sh): a mixin that fails to apply is reported before the worlds load
        // (a failing class load inside a chunk worker would only show as a hung "Preparing spawn area")
        if (new java.io.File(getDataFolder(), "audit-on-enable").exists()) audit("EARLYAUDIT");
        if (new java.io.File(getDataFolder(), "net-probe").exists()) { net = new NetProbe(this); net.register(); } // net-test.sh
        if (new java.io.File(getDataFolder(), "limits-probe").exists()) { limits = new LimitsProbe(this); limits.register(); } // limits-test.sh
    }
    /** forces every mixin target class to load and transform; logs "<tag> done" or "<tag> failed: <cause chain>". */
    private void audit(String tag) {
        try {
            Class<?> env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
            Object cur = env.getMethod("getCurrentEnvironment").invoke(null);
            env.getMethod("audit").invoke(cur);
            getLogger().info(tag + " done");
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            for (Throwable c = t; c != null && sb.length() < 3000; c = c.getCause()) sb.append(" / ").append(c);
            getLogger().warning(tag + " failed:" + sb);
        }
    }
    private NetProbe net;
    private LimitsProbe limits;
    private EventProbe events; // created by the first /fissiontest ev* command (events-test.sh): counts every event, see EventProbe
    @Override public void onDisable() { if (limits != null) limits.unregister(); getLogger().info("disabled"); }

    private org.bukkit.entity.Entity tagged(String tag) {
        for (org.bukkit.World w : getServer().getWorlds()) for (org.bukkit.entity.Entity e : w.getEntities()) if (e.getScoreboardTags().contains(tag)) return e;
        return null;
    }

    /**
     * /fissiontest rider <player> [pig|villager] : an entity rides the player (vanilla's /ride refuses players as vehicles; plugins can).
     * /fissiontest shoulder <player> : a parrot sits on the player's left shoulder.
     * /fissiontest leash <player> <tag> : the entity with that scoreboard tag is leashed to the player.
     * /fissiontest trader <x> <y> <z> [targetX] and /fissiontest leashwatch <seconds> : leash-test.sh (wandering trader with two leashed trader llamas across the region border).
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
            // /fissiontest poison <player> : safety-test.sh. Two poisoned items (3 MB and a 40k-entry custom data tag: over the client's NBT quota) go into the player's inventory.
            // /fissiontest poisonsign <player> : a sign whose first line has 80000 children (a heap bomb for the client's NBT quota) in a fresh chunk far away, the player is teleported next to it.
            if (args.length == 2 && args[0].equals("poison")) {
                org.bukkit.entity.Player p = getServer().getPlayerExact(args[1]);
                org.bukkit.inventory.ItemStack big = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 3);
                org.bukkit.inventory.meta.ItemMeta m = big.getItemMeta();
                m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "blob"), org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, new byte[3_000_000]);
                big.setItemMeta(m);
                org.bukkit.inventory.ItemStack bomb = new org.bukkit.inventory.ItemStack(org.bukkit.Material.EMERALD, 2);
                m = bomb.getItemMeta();
                for (int i = 0; i < 40_000; i++) m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "k" + i), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                bomb.setItemMeta(m);
                p.getInventory().addItem(big, bomb);
                p.updateInventory();
                getLogger().info("FISSION-POISON-DONE items");
                sender.sendMessage("poisoned items given");
                return true;
            }
            if (args.length == 2 && args[0].equals("poisonsign")) {
                org.bukkit.entity.Player p = getServer().getPlayerExact(args[1]);
                org.bukkit.World w = p.getWorld();
                org.bukkit.block.Block b = w.getBlockAt(2000, 100, 2000);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) w.getBlockAt(2000 + dx, 99, 2000 + dz).setType(org.bukkit.Material.STONE);
                b.setType(org.bukkit.Material.OAK_SIGN);
                // the Bukkit sign state cannot be updated in this port (Level is null in the snapshot): the vanilla command writes the block entity
                StringBuilder sb = new StringBuilder("data modify block 2000 100 2000 front_text.messages[0] set value {text:\"poison\",extra:[");
                for (int i = 0; i < 80_000; i++) sb.append(i == 0 ? "" : ",").append("{text:\"a\"}");
                sb.append("]}");
                boolean ok = getServer().dispatchCommand(getServer().getConsoleSender(), sb.toString());
                getLogger().info("poison sign command " + ok);
                p.teleport(new org.bukkit.Location(w, 2001.5, 100, 2001.5));
                getLogger().info("FISSION-POISON-DONE sign");
                sender.sendMessage("poisoned sign placed, teleported");
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
            // /fissiontest trader <x> <y> <z> [targetX] : leash-test.sh. A wandering trader (tag ltrader) with two trader llamas (tags lllama1, lllama2) leashed to it; the chunks around are force-loaded and,
            // when targetX is given, the trader is pushed east each tick until it is past targetX (a deterministic walk over the region border; the llamas follow on their leads).
            // /fissiontest forceload <x> <z> <radius in chunks> : force-loads the chunks around that block (a backend without players loads nothing near the border otherwise, so a handed-over entity has no loaded chunk to live in)
            if (args.length >= 4 && args[0].equals("forceload")) {
                org.bukkit.World w = getServer().getWorlds().get(0);
                int bx = Integer.parseInt(args[1]) >> 4, bz = Integer.parseInt(args[2]) >> 4, r = Integer.parseInt(args[3]);
                for (int cx = -r; cx <= r; cx++) for (int cz = -r; cz <= r; cz++) w.setChunkForceLoaded(bx + cx, bz + cz, true);
                for (org.bukkit.entity.Entity x : w.getEntities()) if (x instanceof org.bukkit.entity.Raider) x.remove();
                sender.sendMessage("force-loaded " + (2 * r + 1) * (2 * r + 1) + " chunks around " + args[1] + " " + args[2]);
                return true;
            }
            if (args.length >= 4 && args[0].equals("trader")) {
                org.bukkit.World w = getServer().getWorlds().get(0);
                org.bukkit.Location at = new org.bukkit.Location(w, Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) w.setChunkForceLoaded(at.getBlockX() / 16 + cx, at.getBlockZ() / 16 + cz, true);
                // stand on the ground, whatever y the script asks for: from y=80 over a flat world the trader and llamas fell 140 blocks and the trader died of it ("fell from a high place") right after arriving on backend-b
                at.setY(w.getHighestBlockYAt(at) + 1);
                // raiders left in the saved worlds hunt traders and trader llamas (the llamas hunt illagers): clear them from the loaded area
                for (org.bukkit.entity.Entity x : w.getEntities()) if (x instanceof org.bukkit.entity.Raider) x.remove();
                org.bukkit.entity.WanderingTrader t = (org.bukkit.entity.WanderingTrader) w.spawnEntity(at, org.bukkit.entity.EntityType.WANDERING_TRADER);
                t.addScoreboardTag("ltrader"); t.setPersistent(true); t.setCustomName("LTrader");
                // a trader llama on a lead discards itself as soon as its trader's despawn delay is <= 0, and a trader spawned by hand has delay 0 (only the natural spawner sets 48000): the llamas vanished on their first tick
                t.setDespawnDelay(48000);
                t.setInvulnerable(true);
                for (int i = 1; i <= 2; i++) {
                    org.bukkit.entity.TraderLlama l = (org.bukkit.entity.TraderLlama) w.spawnEntity(at.clone().add(-1.5 * i, 0, i), org.bukkit.entity.EntityType.TRADER_LLAMA);
                    l.addScoreboardTag("lllama" + i); l.setInvulnerable(true); l.setLeashHolder(t);
                }
                if (args.length >= 5) {
                    final double tx = Double.parseDouble(args[4]);
                    getServer().getScheduler().runTaskTimer(this, task -> {
                        org.bukkit.entity.Entity cur = tagged("ltrader");
                        if (cur == null || cur.getLocation().getX() > tx) { task.cancel(); return; }
                        cur.setVelocity(new org.bukkit.util.Vector(0.18, cur.getVelocity().getY(), 0));
                    }, 20L, 1L);
                }
                sender.sendMessage("trader with two llamas spawned at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
                return true;
            }
            // /fissiontest walk <targetX> <seconds> : run on EVERY backend: each tick the entity tagged ltrader that this backend currently has is pushed east until it is past targetX, then its AI is switched off
            // (its own wandering would walk it back over the border; NoAI travels with the handover). Keeps going when the trader is not here (yet), so the walk continues on whichever backend has it.
            if (args.length == 3 && args[0].equals("walk")) {
                final double tx = Double.parseDouble(args[1]); final int[] ticksLeft = {Integer.parseInt(args[2]) * 20};
                getServer().getScheduler().runTaskTimer(this, task -> {
                    if (--ticksLeft[0] <= 0) { task.cancel(); return; }
                    org.bukkit.entity.Entity cur = tagged("ltrader");
                    if (cur == null) return;
                    if (cur.getLocation().getX() > tx) { if (cur instanceof org.bukkit.entity.Mob m && m.hasAI()) m.setAI(false); return; }
                    cur.setVelocity(new org.bukkit.util.Vector(0.18, cur.getVelocity().getY(), 0));
                }, 1L, 1L);
                sender.sendMessage("walking to x=" + tx);
                return true;
            }
            // /fissiontest leashwatch <seconds> : logs once a second "FISSION-LEASH <tag> x=.. leashed=.. holder=<tag|uuid|none>" for ltrader, lllama1, lllama2 (absent ones as "absent"), then FISSION-LEASH-DONE.
            if (args.length == 2 && args[0].equals("leashwatch")) {
                final int[] left = {Integer.parseInt(args[1])};
                getServer().getScheduler().runTaskTimer(this, task -> {
                    org.bukkit.entity.Entity trader = tagged("ltrader");
                    for (String tg : new String[] {"ltrader", "lllama1", "lllama2"}) {
                        org.bukkit.entity.Entity e = tagged(tg);
                        if (e == null) { getLogger().info("FISSION-LEASH " + tg + " absent"); continue; }
                        String holder = "none"; boolean leashed = false;
                        if (e instanceof org.bukkit.entity.LivingEntity le && le.isLeashed()) {
                            leashed = true; org.bukkit.entity.Entity h = le.getLeashHolder();
                            holder = h == null ? "pending" : (trader != null && h.getUniqueId().equals(trader.getUniqueId()) ? "ltrader" : h.getUniqueId().toString());
                        }
                        getLogger().info(String.format("FISSION-LEASH %s x=%.1f leashed=%s holder=%s", tg, e.getLocation().getX(), leashed, holder));
                    }
                    if (--left[0] <= 0) { task.cancel(); getLogger().info("FISSION-LEASH-DONE"); }
                }, 20L, 20L);
                sender.sendMessage("watching");
                return true;
            }
            if (args.length == 4 && args[0].equals("pdc")) {
                org.bukkit.entity.Entity e = tagged(args[1]);
                e.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("fissiontest", args[2]), org.bukkit.persistence.PersistentDataType.STRING, args[3]);
                sender.sendMessage("pdc set");
                return true;
            }
            if (args.length >= 1 && args[0].startsWith("limits") && limits != null) return limits.command(sender, args);
            if (args.length >= 1 && args[0].startsWith("net") && net != null) return net.command(sender, args);
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
                audit("AUDIT");
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
