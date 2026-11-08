package net.fissionmc.net

import net.fissionmc.protocol.NodeRole
import java.security.PublicKey

/** Builds the security objects a [NodeConfig] implies. */
object NodeSupport {
    /** Security for a master's TCP listener (null when security is off). Reloads the authorized-keys file on every handshake. */
    fun masterSecurity(cfg: NodeConfig): NodeSecurity? {
        if (!cfg.security) return null
        val pair = Keys.loadOrCreate(cfg.keyFile)
        val hint = { "the master reads ${cfg.authorizedFile} (" + (if (java.nio.file.Files.isRegularFile(cfg.authorizedFile)) "file found, " + (runCatching { Keys.loadAuthorized(cfg.authorizedFile).keys.sorted() }.getOrNull()?.let { "ids: ${it.joinToString().ifEmpty { "none" }}" } ?: "but it cannot be parsed") else "FILE NOT FOUND: create it or set authorized: under node: in fission.yml") + ")" }
        return NodeSecurity(pair.private, hint) { id ->
            runCatching { Keys.loadAuthorized(cfg.authorizedFile)[id] }.getOrNull()
        }
    }

    /** Security for a backend dialing the master over TCP (null when security is off). */
    fun backendSecurity(cfg: NodeConfig): NodeSecurity? {
        if (!cfg.security) return null
        val pair = Keys.loadOrCreate(cfg.keyFile)
        val master: PublicKey = Keys.decodePublic(cfg.masterPublic!!)
        return NodeSecurity(pair.private) { id -> if (id == "master") master else null }
    }

    const val MASTER_ID = "master"
    fun masterNode(mc: String) = LocalNode(MASTER_ID, NodeRole.MASTER, mc)
}
