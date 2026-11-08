package net.fissionmc.backend.node

import net.fissionmc.net.NodeLink
import net.fissionmc.net.plugins.ConflictPolicy
import net.fissionmc.net.plugins.PluginPuller
import net.fissionmc.net.plugins.PluginSyncResult
import net.fissionmc.protocol.NodeMessage
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** Runs the boot-time plugin pull and keeps its outcome for `/fission status`. */
object PluginReplicationStatus {
    private val log = Logger.getLogger("FissionMC")
    @Volatile private var last: PluginSyncResult? = null
    private const val TIMEOUT_S = 600L

    /** Never throws: a failed pull is logged and the server boots with the plugins already on disk. */
    fun run(link: NodeLink, dir: Path, policy: ConflictPolicy, fallback: (NodeMessage) -> Unit) {
        val puller = PluginPuller(link, dir, policy) { log.info("[plugins] $it") }
        link.handler = { msg -> if (!puller.handle(msg)) fallback(msg) }
        try {
            val r = puller.pull().get(TIMEOUT_S, TimeUnit.SECONDS)
            last = r
            if (r.enabled) log.info("[plugins] replication from the master: ${r.installed.size} installed, ${r.unchanged.size} unchanged, ${r.deleted.size} removed, ${r.skipped.size} skipped, ${r.shadowed.size} shadowed, ${r.failed.size} failed")
        } catch (e: Exception) {
            log.warning("[plugins] replication from the master failed, booting with the plugins already on disk: ${e.cause?.message ?: e.message}")
        }
    }

    /** Lines for `/fission status`. */
    fun lines(): List<String> {
        val r = last ?: return emptyList()
        if (!r.enabled) return listOf("plugins from master: replication off on the master")
        val out = ArrayList<String>()
        out += "plugins from master: ${r.replicated.size}" + if (r.replicated.isEmpty()) "" else " (${r.replicated.joinToString(", ")})"
        if (r.skipped.isNotEmpty()) out += "plugins not taken from master: " + r.skipped.joinToString("; ") { "${it.first} (${it.second})" }
        if (r.shadowed.isNotEmpty()) out += "backend-local plugins shadowed by the master: ${r.shadowed.joinToString(", ")}"
        if (r.failed.isNotEmpty()) out += "plugins that failed to replicate: ${r.failed.joinToString(", ")}"
        return out
    }
}
