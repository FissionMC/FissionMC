package net.fissionmc.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;

/**
 * Messaging between the backends of a FissionMC network (and to the master), for plugins. Obtain it with {@link #get()} or through Bukkit's
 * ServicesManager ({@code getServer().getServicesManager().load(FissionNet.class)}); both give {@code null} on a standalone server (no {@code node:} mode).
 *
 * <p>Messages are byte arrays on a named channel ({@code namespace:name}, lower case, at most 128 characters). They travel over the encrypted node link; big
 * ones are split and interleaved with other traffic, so a 20 MiB message does not stall chat. Size limit: {@code limits.max-plugin-message} of
 * {@code fission.yml} (default 8 MiB; the smaller of this node's, the master's and the receiver's value applies). Messages of one sender on one channel are
 * delivered in the order they were sent. Each plugin has a quota (messages per second, bytes in flight), see {@code limits.plugin-rate} and
 * {@code limits.plugin-inflight}. Delivery is at most once: nothing is stored for a node that is offline.
 *
 * <p>These messages never reach players: messages to real players keep Minecraft's own plugin message limits.
 */
public interface FissionNet {
    /** Target: the master process. */
    String MASTER = "master";
    /** Target: every other backend that is connected. */
    String ALL = "*";

    /** The installed service, or null when this server does not run in a network. */
    static FissionNet get() { return FissionNetAccess.instance(); }

    /** This backend's node id. */
    String nodeId();

    /** Largest message that can be sent now, in bytes (the link's limit; also bounded by the receiver's, which only the send result reveals). */
    int maxMessageSize();

    /**
     * Sends [data] on [channel] to [target] (a backend node id, {@link #ALL} or {@link #MASTER}). The future completes when the message has been written to the
     * node link (not when the receiver handled it) and fails with a {@link FissionNetException} when it is too big, over the plugin's quota, or the link is down.
     * A target that does not exist is reported to {@link FissionNetListener#onUndeliverable}.
     */
    CompletableFuture<Void> send(Plugin plugin, String target, String channel, byte[] data);

    default CompletableFuture<Void> sendToMaster(Plugin plugin, String channel, byte[] data) { return send(plugin, MASTER, channel, data); }
    default CompletableFuture<Void> broadcast(Plugin plugin, String channel, byte[] data) { return send(plugin, ALL, channel, data); }

    /**
     * Receives the messages that arrive on [channel] for this server. The listener runs on a network worker thread, not on the server thread: use the
     * Bukkit scheduler to touch the world. Several plugins may listen to the same channel.
     */
    void listen(Plugin plugin, String channel, FissionNetListener listener);

    /** Stops [plugin]'s listener on [channel]. */
    void unlisten(Plugin plugin, String channel);

    /** Stops every listener of [plugin] (call from onDisable). */
    void unlisten(Plugin plugin);
}
