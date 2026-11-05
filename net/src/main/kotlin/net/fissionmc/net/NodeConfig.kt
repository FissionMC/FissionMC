package net.fissionmc.net

import java.nio.file.Files
import java.nio.file.Path

enum class NodeMode {
    /** Plain single server; no node networking. Default, so existing setups are unchanged. */
    STANDALONE,
    /** Backend that connects to a remote master over TCP and pulls its world from it. */
    BACKEND,
    /** Backend that also hosts the master in the same JVM (in-JVM link; optional TCP listener for more backends). */
    COMBINED,
}

/**
 * Node settings from a `fission.properties` file:
 * ```
 * node.mode=standalone|backend|combined|master   # master = master-only process (see :master:run)
 * node.id=backend-1
 * node.master=host:port            # backend: where the master listens
 * node.listen=host:port            # master/combined: TCP listener for backends (optional in combined)
 * node.key=fission-node.key        # this node's Ed25519 key file (created if missing)
 * node.master-public=<base64>      # backend: the master's public key
 * node.authorized=fission-authorized.keys   # master: "nodeId base64pubkey" per line
 * node.security=on|off             # default on for TCP
 * node.world-root=.                # master: directory holding the worlds it serves
 * ```
 */
data class NodeConfig(
    val mode: NodeMode,
    val masterProcess: Boolean,
    val nodeId: String,
    val master: NodeAddress.Tcp?,
    val listen: NodeAddress.Tcp?,
    val keyFile: Path,
    val masterPublic: String?,
    val authorizedFile: Path,
    val security: Boolean,
    val worldRoot: Path,
) {
    companion object {
        fun load(file: Path): NodeConfig {
            val p = java.util.Properties()
            if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(p::load)
            fun get(k: String) = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }
            fun addr(k: String) = get(k)?.let {
                val i = it.lastIndexOf(':')
                require(i > 0) { "$k must be host:port, got '$it'" }
                NodeAddress.Tcp(it.substring(0, i), it.substring(i + 1).toInt())
            }
            val modeText = (get("node.mode") ?: "standalone").lowercase()
            val master = modeText == "master"
            val mode = if (master) NodeMode.STANDALONE else NodeMode.entries.firstOrNull { it.name.equals(modeText, true) }
                ?: error("node.mode must be standalone, backend, combined or master, got '$modeText'")
            val dir = file.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath()
            return NodeConfig(
                mode, master, get("node.id") ?: "backend-1", addr("node.master"), addr("node.listen"),
                dir.resolve(get("node.key") ?: "fission-node.key"), get("node.master-public"),
                dir.resolve(get("node.authorized") ?: "fission-authorized.keys"),
                (get("node.security") ?: "on").lowercase() != "off",
                dir.resolve(get("node.world-root") ?: "."),
            ).also { it.validate() }
        }
    }

    private fun validate() {
        if (mode == NodeMode.BACKEND) require(master != null) { "node.mode=backend needs node.master=host:port" }
        if (mode == NodeMode.BACKEND && security) require(masterPublic != null) { "node.mode=backend needs node.master-public (or node.security=off)" }
        if (masterProcess) require(listen != null) { "node.mode=master needs node.listen=host:port" }
    }
}
