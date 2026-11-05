package net.fissionmc.net

import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.Welcome
import java.util.concurrent.CompletableFuture

/** Identity of the node on the other end, known once the handshake finished. */
data class PeerInfo(val nodeId: String, val role: net.fissionmc.protocol.NodeRole, val sessionId: Long, val mcVersion: String?)

/** A handshaken, framed, bidirectional message link to one peer. Identical for Local and Tcp transports. */
interface NodeLink {
    /** Completes when the handshake is done; fails if it is rejected or the link drops first. */
    val ready: CompletableFuture<PeerInfo>

    /** Completes when the link is closed, with the reason. */
    val closed: CompletableFuture<String>

    /** Round-trip time of the last heartbeat in nanos, or -1 before the first one. */
    val latencyNanos: Long

    val isOpen: Boolean

    /** False while the outbound buffer is full; bulk senders should wait. */
    val isWritable: Boolean

    /** Called on the link's event loop for every non-control message. Set before [ready] completes. */
    var handler: (NodeMessage) -> Unit

    fun send(msg: NodeMessage)
    fun close(reason: String = "closed")
}
