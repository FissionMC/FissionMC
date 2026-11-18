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

    companion object {
        /** Sequence numbers skipped at a restart: more than any backend can have applied beyond the last saved position. */
        const val RESTART_MARGIN = 1_000_000L

        /**
         * A hub whose epoch and log position survive a restart of the master ([file] = `epoch lastSeq`, saved every 2 s and at shutdown). Without this every restart
         * started a new epoch and the backends could only log "restart this backend to resync"; now they continue with the same numbering.
         */
        fun persistent(file: java.nio.file.Path, timer: java.util.concurrent.ScheduledExecutorService, log: (String) -> Unit = {}): ReplicationHub {
            val saved = try { java.nio.file.Files.readString(file).trim().split(' ').map { it.toLong() }.takeIf { it.size == 2 && it[0] > 0 && it[1] >= 0 } } catch (_: Exception) { null }
            val hub = if (saved == null) ReplicationHub() else ReplicationHub(EventLog().also { it.restore(saved[1] + RESTART_MARGIN) }, saved[0])
            if (saved != null) log("[master] event log continues: epoch ${saved[0]}, seq ${hub.log.lastSeq}")
            var written = -1L
            val save = Runnable {
                val seq = hub.log.lastSeq
                if (seq != written) try {
                    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
                    java.nio.file.Files.writeString(tmp, "${hub.epoch} $seq"); java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING); written = seq
                } catch (e: Exception) { log("[master] cannot write $file: $e") }
            }
            save.run()
            timer.scheduleWithFixedDelay(save, 2, 2, java.util.concurrent.TimeUnit.SECONDS)
            Runtime.getRuntime().addShutdownHook(Thread(save))
            return hub
        }
    }
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
        net.fissionmc.net.trace.Trace.emit("repl.event") { long("seq", e.seq); str("origin", origin); traceEvent(this, event) }
        for ((id, l) in live) if (l.isOpen) l.send(e) else logger.fine("skip closed link to $id")
        e
    }

    private fun resume(node: RegisteredNode, r: ResumeFrom) = synchronized(lock) {
        val link = node.link
        var from = r.lastSeq
        if (r.epoch != epoch) { link.send(LogReset(epoch)); from = 0 }
        else if (r.lastSeq > log.lastSeq) { link.send(LogReset(epoch)); from = 0 }
        when (val s = log.since(from)) {
            is EventLog.Since.Gap -> {
                link.send(LogTruncated(s.oldestSeq)); logger.warning("${node.info.nodeId} is behind the retained log (oldest ${s.oldestSeq})")
                // the backend continues from oldest-1: the events the log still holds follow at once (it used to get them only when the next live event showed a gap and asked again: "gap: got seq=N+1, expected N")
                (log.since(s.oldestSeq - 1) as? EventLog.Since.Events)?.events?.forEach(link::send)
            }
            is EventLog.Since.Events -> s.events.forEach(link::send)
        }
        live[node.info.nodeId] = link
        if (from < log.lastSeq) logger.info("${node.info.nodeId} resumed from seq $from (log at ${log.lastSeq})")
    }
}

/** The fields of a replicated event for the flight recorder (shared with the backend). */
fun traceEvent(e: net.fissionmc.net.trace.TraceEvent, ev: net.fissionmc.protocol.ReplicatedEvent) {
    when (ev) {
        is net.fissionmc.protocol.BlockChange -> { e.str("kind", "block").str("dim", ev.dimension).int("x", ev.x).int("y", ev.y).int("z", ev.z).int("state", ev.stateId).str("cause", ev.cause) }
        is net.fissionmc.protocol.EntityRemoved -> { e.str("kind", "entityRemoved").str("dim", ev.dimension).str("entity", java.util.UUID(ev.uuidHigh, ev.uuidLow).toString()).str("cause", ev.cause) }
    }
}
