package net.fissionmc.net

import net.fissionmc.protocol.NodeRole
import java.io.Closeable
import java.util.concurrent.CompletableFuture

/** Who we are when we shake hands. */
data class LocalNode(val nodeId: String, val role: NodeRole, val mcVersion: String)

interface Transport : Closeable {
    /** Accept links on [address]. [onLink] runs for each new link right after creation, before the handshake finishes. */
    fun bind(address: NodeAddress, onLink: (NodeLink) -> Unit): Closeable

    /** Open a link to [address]. The returned future completes when the TCP/local connection is up; use [NodeLink.ready] for the handshake. */
    fun connect(address: NodeAddress): CompletableFuture<NodeLink>
}
