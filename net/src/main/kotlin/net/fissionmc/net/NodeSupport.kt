package net.fissionmc.net

import net.fissionmc.net.pairing.*
import net.fissionmc.protocol.NodeRole
import java.security.PublicKey

/** Builds the security objects a [NodeConfig] implies. */
object NodeSupport {
    /**
     * The master's refusal to listen without link security on an address the world can reach (also used by the combined mode, which has a TCP listener too). Null = fine, else the message.
     * A master with `security: false` needs a loopback listener or `node.allow-insecure: true`.
     */
    fun insecureListenerProblem(cfg: NodeConfig): String? {
        val listen = cfg.listen ?: return null
        if (cfg.security || cfg.allowInsecure || isLoopback(listen.host)) return null
        return "node.security is false and node.listen is ${listen.host}:${listen.port}: anyone who reaches that port could connect as a backend (read player data, grant operator, push chunks). Turn security on, listen on 127.0.0.1, or set node.allow-insecure: true if this network is closed"
    }

    /** The backend's counterpart: it does not dial a master over a network without link security unless the operator said so (an attacker in the path could then be the master). */
    fun insecureMasterProblem(cfg: NodeConfig): String? {
        val m = cfg.master ?: return null
        if (cfg.security || cfg.allowInsecure || isLoopback(m.host)) return null
        return "node.security is false and node.master is ${m.host}:${m.port}: without link security anyone on the path could act as the master (and read or change everything this backend sends). Turn security on (pairing needs no manual keys), point node.master at 127.0.0.1, or set node.allow-insecure: true if this network is closed"
    }

    fun isLoopback(host: String) = host == "127.0.0.1" || host.equals("localhost", true) || host == "::1" || host == "[::1]"

    /** The pre-authentication limits of a master listener. */
    fun preAuthGuard(cfg: NodeConfig) = PreAuthGuard(maxHalfOpen = cfg.maxUnauthenticatedPerIp, maxPerMinute = cfg.connectionsPerMinute)

    /** The master's authorized keys (the file is read again whenever it changes). */
    fun authorizedKeys(cfg: NodeConfig, warn: (String) -> Unit = { println("[master] $it") }) = AuthorizedKeys(cfg.authorizedFile, warn)

    /**
     * Security for a master's TCP listener (null when security is off). Reloads the authorized-keys file on every handshake. [store] holds the pending pairings (null = a store is made from
     * the config); a previous key left by `node rotate-master` is loaded too.
     */
    fun masterSecurity(cfg: NodeConfig, store: PairingStore? = null, warn: (String) -> Unit = { println("[master] $it") }): NodeSecurity? {
        if (!cfg.security) return null
        val pair = Keys.loadOrCreate(cfg.keyFile, warn)
        val keys = store?.authorized ?: authorizedKeys(cfg, warn)
        // the hint goes to whoever reaches the master log, never to the peer (see LinkHandler.NOT_AUTHORIZED)
        val hint = { "the master reads ${cfg.authorizedFile} (" + (if (java.nio.file.Files.isRegularFile(cfg.authorizedFile)) "file found, " + (runCatching { keys.load().keys.sorted() }.getOrNull()?.let { "ids: ${it.joinToString().ifEmpty { "none" }}" } ?: "but it cannot be parsed") else "no such file yet: `node add <code>` creates it") + ")" }
        val sec = NodeSecurity(pair.private, hint, pair.public) { id -> keys[id] }
        sec.pairingStore = (store ?: PairingStore(keys, cfg.pairing, cfg.pairingMaxPending)).also { it.enabled = cfg.pairing; it.maxPending = cfg.pairingMaxPending }
        MasterKeyring.previousFile(cfg.keyFile).let { f -> if (java.nio.file.Files.isRegularFile(f)) { Secrets.enforceOwnerOnly(f, warn); runCatching { Keys.load(f) }.getOrNull()?.let { sec.previous = it } } }
        return sec
    }

    /**
     * Security for a backend dialing the master over TCP (null when security is off). The master's key comes from `node.master-public` (manual setup) or the pinned file; with neither,
     * the backend is in pairing mode: [announce] receives the pairing code (print it to the CONSOLE only), see [PairingClient].
     */
    fun backendSecurity(
        cfg: NodeConfig, announce: (code: String, expiresAtMillis: Long, fingerprint: String, renewed: Boolean) -> Unit = { _, _, _, _ -> },
        onPaired: (master: PublicKey, firstPin: Boolean) -> Unit = { _, _ -> }, warn: (String) -> Unit = {},
    ): NodeSecurity? {
        if (!cfg.security) return null
        val pair = Keys.loadOrCreate(cfg.keyFile, warn)
        val manual: PublicKey? = cfg.masterPublic?.let { Keys.decodePublic(it) }
        val pin = MasterPin(if (manual != null) null else cfg.masterKeyFile, manual)
        val sec = NodeSecurity(pair.private, publicKey = pair.public) { id -> if (id == MASTER_ID) pin.key else null }
        sec.pairingClient = PairingClient(cfg.nodeId, { sec.publicKey!! }, pin, cfg.pairingMinutes * 60_000L, announce = announce, onPaired = onPaired)
        return sec
    }

    const val MASTER_ID = "master"
    fun masterNode(mc: String) = LocalNode(MASTER_ID, NodeRole.MASTER, mc)
}
