package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.util.logging.Logger

/**
 * Master side of replication: orders submitted events in the [log] and fans them out to every backend
 * (including the origin, which needs the echo to learn the master's order).
 *
 * A backend only receives live events after it sent [ResumeFrom]; the replay of the missed events and the switch to
 * live delivery happen under one lock, so a backend never sees a gap or a duplicate.
 * Wire it as `MasterServer(onMessage = hub::handle)` and `registry.addListener(hub.listener)`.
 */
class ReplicationHub(val log: EventLog = EventLog(), val epoch: Long = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)) {
    private val logger = Logger.getLogger("FissionMC.master")
    private val lock = Any()
    private val live = HashMap<String, NodeLink>()

    val listener = object : NodeRegistry.Listener {
        override fun left(node: RegisteredNode, reason: String) { synchronized(lock) { live.remove(node.info.nodeId, node.link) } }
    }

    fun liveNodes(): Set<String> = synchronized(lock) { live.keys.toSet() }

    /** Returns true when the message was a replication message. */
    fun handle(node: RegisteredNode, msg: NodeMessage): Boolean {
        when (msg) {
            is EventSubmit -> submit(node.info.nodeId, msg.event)
            is EventAck -> log.ack(node.info.nodeId, msg.seq)
            is ResumeFrom -> resume(node, msg)
            else -> return false
        }
        return true
    }

    fun submit(origin: String, event: ReplicatedEvent): SequencedEvent = synchronized(lock) {
        val e = log.append(origin, event)
        for ((id, l) in live) if (l.isOpen) l.send(e) else logger.fine("skip closed link to $id")
        e
    }

    private fun resume(node: RegisteredNode, r: ResumeFrom) = synchronized(lock) {
        val link = node.link
        var from = r.lastSeq
        if (r.epoch != epoch) { link.send(LogReset(epoch)); from = 0 }
        else if (r.lastSeq > log.lastSeq) { link.send(LogReset(epoch)); from = 0 }
        when (val s = log.since(from)) {
            is EventLog.Since.Gap -> { link.send(LogTruncated(s.oldestSeq)); logger.warning("${node.info.nodeId} is behind the retained log (oldest ${s.oldestSeq})") }
            is EventLog.Since.Events -> s.events.forEach(link::send)
        }
        live[node.info.nodeId] = link
        if (from < log.lastSeq) logger.info("${node.info.nodeId} resumed from seq $from (log at ${log.lastSeq})")
    }
}
