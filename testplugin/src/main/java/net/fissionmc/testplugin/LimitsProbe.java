package net.fissionmc.testplugin;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fissionmc.api.FissionNet;
import net.fissionmc.api.FissionNetListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sample of the plugin messaging API (net.fissionmc.api.FissionNet) and probe for .dev/scripts/limits-test.sh. Registered when plugins/FissionTest/limits-probe exists.
 *   limitssend <target> <bytes> : sends <bytes> deterministic bytes on limits:data to <target> (a backend id, * or master; to the master on fission:selftest, which logs size and
 *                                 sha256 itself) while 20 small pings per second go to the same target on limits:ping. Logs "LIMITS sent ..." and "LIMITS written ...".
 * A receiver logs "LIMITS recv <source> <bytes> sha256=<hash> pings=<n> maxPingMs=<ms>": the pings that arrived before the big message completed show that small messages are not held up.
 */
final class LimitsProbe implements FissionNetListener {
    private final JavaPlugin plugin;
    private final ConcurrentLinkedQueue<Long> pingLatencies = new ConcurrentLinkedQueue<>();
    LimitsProbe(JavaPlugin plugin) { this.plugin = plugin; }

    void register() {
        FissionNet net = FissionNet.get();
        if (net == null) { plugin.getLogger().warning("LIMITS no FissionNet service (standalone?)"); return; }
        // the ServicesManager hands out the same service
        FissionNet viaServices = plugin.getServer().getServicesManager().load(FissionNet.class);
        plugin.getLogger().info("LIMITS ready on " + net.nodeId() + ", max message " + net.maxMessageSize() + ", services manager " + (viaServices == net ? "ok" : "MISSING"));
        net.listen(plugin, "limits:data", this);
        net.listen(plugin, "limits:ping", this);
    }

    void unregister() { FissionNet net = FissionNet.get(); if (net != null) net.unlisten(plugin); }

    @Override public void onMessage(String source, String channel, byte[] data) {
        if (channel.equals("limits:ping")) {
            pingLatencies.add(System.currentTimeMillis() - java.nio.ByteBuffer.wrap(data).getLong());
            return;
        }
        long max = pingLatencies.stream().mapToLong(Long::longValue).max().orElse(-1);
        plugin.getLogger().info("LIMITS recv " + source + " " + data.length + " sha256=" + sha(data) + " pings=" + pingLatencies.size() + " maxPingMs=" + max);
        pingLatencies.clear();
    }

    @Override public void onUndeliverable(String target, String channel, String reason) { plugin.getLogger().warning("LIMITS undeliverable " + target + " " + channel + ": " + reason); }

    static byte[] payload(int n) {
        byte[] b = new byte[n];
        new java.util.Random(n).nextBytes(b);
        return b;
    }

    private static String sha(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    boolean command(org.bukkit.command.CommandSender sender, String[] args) {
        FissionNet net = FissionNet.get();
        if (net == null || args.length < 3 || !args[0].equals("limitssend")) return false;
        String target = args[1]; int size = Integer.parseInt(args[2]);
        byte[] data = payload(size);
        String channel = target.equals(FissionNet.MASTER) ? "fission:selftest" : "limits:data";
        plugin.getLogger().info("LIMITS sent " + target + " " + size + " sha256=" + sha(data));
        // small messages on another channel while the big one is on its way: they must not wait for it
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread pinger = new Thread(() -> {
            while (!done.get()) {
                net.send(plugin, target, "limits:ping", java.nio.ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array());
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "limits-pinger");
        pinger.setDaemon(true);
        if (!target.equals(FissionNet.MASTER)) pinger.start();
        net.send(plugin, target, channel, data).whenComplete((v, err) -> {
            done.set(true);
            plugin.getLogger().info("LIMITS written " + target + " " + size + (err == null ? " ok" : " FAILED " + err.getMessage()));
        });
        return true;
    }
}
