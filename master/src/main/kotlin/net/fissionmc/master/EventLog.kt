package net.fissionmc.master

import net.fissionmc.protocol.ReplicatedEvent
import net.fissionmc.protocol.SequencedEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The master's ordered event log: every applied event gets the next sequence number (1, 2, 3, ...).
 * Memory is bounded to [capacity] events; older history is dropped and [since] reports a gap instead.
 */
class EventLog(private val capacity: Int = 100_000) {
    init { require(capacity > 0) }

    sealed interface Since {
        class Events(val events: List<SequencedEvent>) : Since
        /** The caller is too far behind; the oldest event still held has this seq. */
        class Gap(val oldestSeq: Long) : Since
    }

    private val ring = ArrayDeque<SequencedEvent>()
    private val acks = ConcurrentHashMap<String, Long>()

    @get:Synchronized var lastSeq = 0L; private set

    @Synchronized fun append(origin: String, event: ReplicatedEvent): SequencedEvent {
        val e = SequencedEvent(++lastSeq, origin, event)
        ring.addLast(e)
        if (ring.size > capacity) ring.removeFirst()
        return e
    }

    /** All events with seq greater than [seq], in order; [Since.Gap] when some of them were already dropped. */
    @Synchronized fun since(seq: Long): Since {
        if (seq >= lastSeq) return Since.Events(emptyList())
        val oldest = lastSeq - ring.size + 1
        if (seq + 1 < oldest) return Since.Gap(oldest)
        val skip = (seq + 1 - oldest).toInt()
        return Since.Events(ring.drop(skip))
    }

    /** Records that [node] applied everything up to [seq]. Never moves backwards; values beyond [lastSeq] are ignored. */
    fun ack(node: String, seq: Long) {
        if (seq > lastSeq) return
        acks.merge(node, seq, ::maxOf)
    }

    fun acked(node: String): Long = acks[node] ?: 0L
    @Synchronized fun size(): Int = ring.size
}
