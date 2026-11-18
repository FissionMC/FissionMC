package net.fissionmc.master

import net.fissionmc.net.LimitsConfig
import net.fissionmc.net.NodeLink
import net.fissionmc.net.PluginQuota
import net.fissionmc.protocol.NetPluginMessage
import net.fissionmc.protocol.NetPluginUndeliverable
import net.fissionmc.protocol.NodeMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes plugin messages (see NetPluginMessage) between backends and to the master. The origin is always the node id of the link a message came in on. Targets: a
 * backend node id, `*` (all backends but the sender) or `master` (listeners registered here). An unknown or offline target, an over-quota sender or a target link
 * that cannot take the message is answered with NetPluginUndeliverable. Quotas: per (origin node, plugin).
 */
class PluginBus(private val registry: NodeRegistry, private val log: (String) -> Unit = { println("[master] $it") }) {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(String, ByteArray) -> Unit>>()
    private val quota = PluginQuota(LimitsConfig.active.pluginInflight, LimitsConfig.active.pluginRate)
    /** All plugins of one node together (a plugin name is only the sender's claim): four times a plugin's allowance. */
    private val nodeQuota = PluginQuota(LimitsConfig.active.pluginInflight * 4, LimitsConfig.active.pluginRate * 4)

    /** [listener] gets (origin node id, data) of every message sent to the master on [channel]; called on the network thread of the sender's link: hand work off. */
    fun listen(channel: String, listener: (String, ByteArray) -> Unit) { listeners.getOrPut(channel) { CopyOnWriteArrayList() } += listener }

    init {
        // diagnostics: what arrived (size and SHA-256), for the scripted run and for checking a big transfer by hand
        listen(SELFTEST_CHANNEL) { from, data -> log("selftest message from $from: ${data.size} bytes sha256=${java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data))}") }
    }

    /** True when [msg] was a plugin message (consumed). [from] is the node id of the link it arrived on. */
    fun handle(link: NodeLink, from: String, msg: NodeMessage): Boolean {
        if (msg is NetPluginUndeliverable) return true
        if (msg !is NetPluginMessage) return false
        if (from.isEmpty()) return true
        val sender = "$from/${msg.plugin}"
        quota.acquire(sender, msg.data.size)?.let { why -> refuse(link, msg, "quota of ${msg.plugin} on $from: $why"); return true }
        nodeQuota.acquire(from, msg.data.size)?.let { why -> quota.release(sender, msg.data.size); refuse(link, msg, "quota of node $from: $why"); return true }
        val done = { quota.release(sender, msg.data.size); nodeQuota.release(from, msg.data.size) }
        val out = NetPluginMessage(from, msg.target, msg.plugin, msg.channel, msg.data)
        when (msg.target) {
            "master" -> { deliverLocal(from, msg); done() }
            "*" -> {
                val to = registry.all.filter { it.info.nodeId != from && it.link.isOpen }
                if (to.isEmpty()) done() else {
                    val left = java.util.concurrent.atomic.AtomicInteger(to.size)
                    to.forEach { n -> n.link.sendTracked(out) { err -> if (err != null) refuse(link, msg, "${n.info.nodeId}: ${err.message}", n.info.nodeId); if (left.decrementAndGet() == 0) done() } }
                }
            }
            else -> {
                val n = registry[msg.target]
                if (n == null || !n.link.isOpen) { refuse(link, msg, "no backend '${msg.target}' is connected"); done() }
                else n.link.sendTracked(out) { err -> if (err != null) refuse(link, msg, "${msg.target}: ${err.message}"); done() }
            }
        }
        return true
    }

    private fun deliverLocal(from: String, msg: NetPluginMessage) {
        val ls = listeners[msg.channel]
        if (ls == null || ls.isEmpty()) { log("plugin message from $from on '${msg.channel}' (${msg.data.size} bytes) dropped: nothing on the master listens to that channel"); return }
        ls.forEach { runCatching { it(from, msg.data) }.onFailure { e -> log("plugin message listener for '${msg.channel}' failed: $e") } }
    }

    private fun refuse(link: NodeLink, msg: NetPluginMessage, why: String, target: String = msg.target) {
        log("plugin message ${msg.plugin} -> ${msg.target} '${msg.channel}' (${msg.data.size} bytes) not delivered: $why")
        if (link.isOpen) link.send(NetPluginUndeliverable(target, msg.channel, why.take(250)))
    }

    companion object { const val SELFTEST_CHANNEL = "fission:selftest" }
}
