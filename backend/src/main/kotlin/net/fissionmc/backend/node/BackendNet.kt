package net.fissionmc.backend.node

import net.fissionmc.api.FissionNet
import net.fissionmc.api.FissionNetAccess
import net.fissionmc.api.FissionNetException
import net.fissionmc.api.FissionNetListener
import net.fissionmc.net.LimitsConfig
import net.fissionmc.net.PluginQuota
import net.fissionmc.protocol.Limits
import net.fissionmc.protocol.NetPluginMessage
import net.fissionmc.protocol.NetPluginUndeliverable
import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.PLUGIN_CHANNEL_MAX
import net.fissionmc.protocol.PLUGIN_NAME_MAX
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * The backend's implementation of the plugin messaging API ([FissionNet]): sends NetPluginMessage over the master link (the master routes it), quota per plugin,
 * and delivers what arrives to the plugins' listeners on worker threads. Listeners of one channel always run on the same worker, so the order of the link is kept.
 */
class BackendNet private constructor(private val nodeId: () -> String, private val link: () -> net.fissionmc.net.NodeLink?, quota: PluginQuota) : FissionNet {
    private val log = Logger.getLogger("FissionMC")
    private val quota = quota
    private class Reg(val plugin: String, val listener: FissionNetListener)
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Reg>>()
    private val workers = Array(4) { i -> Executors.newSingleThreadExecutor { r -> Thread(r, "fission-net-$i").also { it.isDaemon = true } } }
    private fun worker(channel: String) = workers[(channel.hashCode() and Int.MAX_VALUE) % workers.size]

    override fun nodeId(): String = nodeId.invoke()

    override fun maxMessageSize(): Int = (link()?.peerLimits ?: Limits.own()).maxPluginMessage.coerceAtMost((link()?.peerLimits ?: Limits.own()).maxMessage - 4096)

    override fun send(plugin: Plugin, target: String, channel: String, data: ByteArray): CompletableFuture<Void> {
        val f = CompletableFuture<Void>()
        val name = plugin.name
        fun fail(why: String) = f.also { it.completeExceptionally(FissionNetException(why)) }
        if (!CHANNEL.matches(channel) || channel.length > PLUGIN_CHANNEL_MAX) return fail("bad channel '$channel': expected namespace:name (lower case letters, digits, _ - . /), at most $PLUGIN_CHANNEL_MAX characters")
        if (target.isEmpty() || target.length > 64) return fail("bad target '$target'")
        if (name.length > PLUGIN_NAME_MAX) return fail("plugin name too long")
        val l = link()
        if (l == null || !l.isOpen) return fail("no link to the master")
        val max = maxMessageSize()
        if (data.size > max) return fail("message of ${data.size} bytes on '$channel' is over limits.max-plugin-message (${max} bytes)")
        quota.acquire(name, data.size)?.let { return fail("plugin $name: $it") }
        l.sendTracked(NetPluginMessage(nodeId(), target, name, channel, data)) { err ->
            quota.release(name, data.size)
            if (err == null) f.complete(null) else f.completeExceptionally(FissionNetException(err.message ?: err.toString()))
        }
        return f
    }

    override fun listen(plugin: Plugin, channel: String, listener: FissionNetListener) { listeners.getOrPut(channel) { CopyOnWriteArrayList() } += Reg(plugin.name, listener) }
    override fun unlisten(plugin: Plugin, channel: String) { listeners[channel]?.removeIf { it.plugin == plugin.name } }
    override fun unlisten(plugin: Plugin) { listeners.values.forEach { l -> l.removeIf { it.plugin == plugin.name } } }

    /** From the master link (network thread). */
    fun onMessage(m: NodeMessage) {
        when (m) {
            is NetPluginMessage -> {
                val ls = listeners[m.channel]
                if (ls.isNullOrEmpty()) { log.fine("[fission] plugin message on '${m.channel}' from ${m.origin} (${m.data.size} bytes): no listener"); return }
                worker(m.channel).execute { ls.forEach { r -> try { r.listener.onMessage(m.origin, m.channel, m.data) } catch (e: Throwable) { log.warning("[fission] plugin ${r.plugin}: listener of '${m.channel}' failed: $e") } } }
            }
            is NetPluginUndeliverable -> {
                log.warning("[fission] plugin message on '${m.channel}' to ${m.target} not delivered: ${m.reason}")
                val ls = listeners[m.channel] ?: return
                worker(m.channel).execute { ls.forEach { r -> try { r.listener.onUndeliverable(m.target, m.channel, m.reason) } catch (e: Throwable) { log.warning("[fission] plugin ${r.plugin}: onUndeliverable failed: $e") } } }
            }
        }
    }

    companion object {
        private val CHANNEL = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        /** Installs the service for a backend that runs in a network (called by [NodeBootstrap]). */
        fun install() {
            val cfg = LimitsConfig.active
            val impl = BackendNet({ NodeBootstrap.nodeId }, { NodeBootstrap.link }, PluginQuota(cfg.pluginInflight, cfg.pluginRate))
            NodeBootstrap.addMessageHandler { m -> if (m is NetPluginMessage || m is NetPluginUndeliverable) impl.onMessage(m) }
            FissionNetAccess.install(impl)
        }
    }
}
