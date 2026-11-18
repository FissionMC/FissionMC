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
 *   direct-connections: false  # backend: true = players may also connect to the backend's own port (bypasses the master)
 *   allow-insecure: false    # master: allow security: false on a non-loopback listener
 *   connect-timeout: 300     # backend: seconds to wait for the master at start (0 = for ever)
 *   master-key: .fission-master-key  # backend: where the pinned master key is kept (written by pairing)
 *   pairing: true            # master: accept `node add <code>` pairings
 *   pairing-minutes: 15      # backend: lifetime of a printed pairing code
 *   pairing-max-pending: 8   # master: pairings that may wait at once
 *   max-unauthenticated: 8   # master: handshakes in progress per remote address
 *   connections-per-minute: 60  # master: new connections per remote address
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
    /** `node.direct-connections` (backend mode, default false): false = the backend's own Minecraft port is bound on 127.0.0.1 only, players can only come through the master. */
    val directConnections: Boolean = false,
    /** `node.allow-insecure` (default false): a master with `security: false` refuses to listen on anything but loopback unless this is true. */
    val allowInsecure: Boolean = false,
    /** `node.connect-timeout` seconds a starting backend waits for the master (default 300, 0 = for ever). */
    val connectTimeoutSeconds: Long = 300,
    /** `node.master-key` (backend): the pinned master key file, written by pairing (default `.fission-master-key` next to fission.yml). */
    val masterKeyFile: Path = keyFile.resolveSibling(".fission-master-key"),
    /** `node.pairing` (master, default true): `node add <code>` and the pairing handshake are accepted. */
    val pairing: Boolean = true,
    /** `node.pairing-minutes` (backend): how long a printed pairing code is valid. */
    val pairingMinutes: Long = 15,
    /** `node.pairing-max-pending` (master): pairings waiting for their first contact. */
    val pairingMaxPending: Int = 8,
    /** `node.max-unauthenticated` (master): connections per remote address that have not finished the handshake. */
    val maxUnauthenticatedPerIp: Int = 8,
    /** `node.connections-per-minute` (master): new connections per remote address. */
    val connectionsPerMinute: Int = 60,
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
                n.bool("direct-connections", false), n.bool("allow-insecure", false),
                n.long("connect-timeout", 300L).also { if (it < 0) throw ConfigException("node.connect-timeout: expected 0 or more seconds, got $it") },
                dir.resolve(text("master-key") ?: ".fission-master-key"), n.bool("pairing", true),
                n.long("pairing-minutes", 15L).also { if (it !in 1..60) throw ConfigException("node.pairing-minutes: expected 1-60, got $it") },
                n.int("pairing-max-pending", 8).also { if (it !in 1..64) throw ConfigException("node.pairing-max-pending: expected 1-64, got $it") },
                n.int("max-unauthenticated", 8).also { if (it !in 1..1000) throw ConfigException("node.max-unauthenticated: expected 1-1000, got $it") },
                n.int("connections-per-minute", 60).also { if (it !in 1..100000) throw ConfigException("node.connections-per-minute: expected 1-100000, got $it") },
            ).also { it.validate() }
        }
    }

    private fun validate() {
        if (mode == NodeMode.BACKEND && master == null) throw ConfigException("node.master: mode backend needs host:port")
        if (masterProcess && listen == null) throw ConfigException("node.listen: mode master needs host:port")
    }
}
