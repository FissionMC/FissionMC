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
 * Node settings, the `node:` section of `fission.yml`:
 * ```
 * node:
 *   mode: backend            # standalone | backend | combined | master (master = master-only process, see :master:run)
 *   id: backend-a            # optional: without it the backend generates `backend-xxxxxx` once and saves it (NodeIds)
 *   master: "host:port"      # backend: where the master listens
 *   listen: "host:port"      # master/combined: TCP listener for backends (optional in combined)
 *   key: fission-node.key    # this node's Ed25519 key file (created if missing)
 *   master-public: <base64>  # backend: the master's public key
 *   authorized: fission-authorized.keys   # master: "nodeId base64pubkey" per line
 *   security: true           # default true for TCP
 *   world-root: .            # master: directory holding the worlds it serves
 * ```
 * (every key documented in `fission.default.yml` / `fission.backend.default.yml`)
 */
data class NodeConfig(
    val mode: NodeMode,
    val masterProcess: Boolean,
    /** `node.id`; EMPTY when the file has none (a backend then generates and saves one, see [NodeIds]). */
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
        /** Parses the file and reads only the `node:` section (no unknown-key check: the caller owns the whole tree; see [from]). */
        fun load(file: Path): NodeConfig = from(FissionConfig.load(file), file.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath())

        /** Reads the `node:` section of [root]; [dir] is what the relative paths (key files, world root) are relative to. */
        fun from(root: ConfigSection, dir: Path): NodeConfig {
            val n = root.section("node")
            fun addr(k: String) = n.optString(k)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val i = it.lastIndexOf(':')
                if (i <= 0) throw ConfigException("node.$k: expected host:port, got '$it'")
                NodeAddress.Tcp(it.substring(0, i), it.substring(i + 1).toIntOrNull() ?: throw ConfigException("node.$k: the port of '$it' is not a number"))
            }
            fun text(k: String) = n.optString(k)?.trim()?.takeIf { it.isNotEmpty() }
            val modeText = (text("mode") ?: "standalone").lowercase()
            val master = modeText == "master"
            val mode = if (master) NodeMode.STANDALONE else NodeMode.entries.firstOrNull { it.name.equals(modeText, true) }
                ?: throw ConfigException("node.mode: expected standalone, backend, combined or master, got '$modeText'")
            return NodeConfig(
                mode, master, text("id") ?: "", addr("master"), addr("listen"),
                dir.resolve(text("key") ?: "fission-node.key"), text("master-public"),
                dir.resolve(text("authorized") ?: "fission-authorized.keys"),
                n.bool("security", true),
                dir.resolve(text("world-root") ?: "."),
            ).also { it.validate() }
        }
    }

    private fun validate() {
        if (mode == NodeMode.BACKEND && master == null) throw ConfigException("node.master: mode backend needs host:port")
        if (mode == NodeMode.BACKEND && security && masterPublic == null) throw ConfigException("node.master-public: mode backend needs the master's public key (or node.security: false)")
        if (masterProcess && listen == null) throw ConfigException("node.listen: mode master needs host:port")
    }
}
