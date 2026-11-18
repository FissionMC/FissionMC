package net.fissionmc.net

import net.fissionmc.protocol.Fragment

/** The peer broke the fragmentation rules (out-of-order or duplicate piece, wrong sizes, unknown stream): the link is closed with this as the reason. */
class FragmentViolation(message: String) : RuntimeException(message)

/** A message was not sent because it exceeds a limit (its own or the peer's). The link stays up. */
class LinkLimitException(message: String) : RuntimeException(message)

/** A pool of bytes shared by every link of a node (reassembly memory): a reservation either fits or is refused. */
class ByteBudget(val limit: Long) {
    private val used = java.util.concurrent.atomic.AtomicLong()
    fun tryReserve(n: Long): Boolean { while (true) { val u = used.get(); if (u + n > limit) return false; if (used.compareAndSet(u, u + n)) return true } }
    fun release(n: Long) { used.addAndGet(-n) }
    val inUse get() = used.get()
}

/** Rules of the receiving side of fragmentation. All limits are what THIS node allows; the clock is injectable for tests. */
class Reassembler(
    private val maxMessage: () -> Int,
    private val budget: ByteBudget,
    private val maxStreams: Int,
    private val timeoutNanos: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    sealed interface Result {
        /** More pieces are needed (or the piece belonged to a refused stream and was dropped). */
        data object Pending : Result
        class Done(val bytes: ByteArray) : Result
        /** This node refuses the message (budget, too many streams): tell the sender with a FragmentAbort(byReceiver); the link stays. */
        class Refused(val streamId: Long, val reason: String) : Result
    }

    private class Stream(val id: Long, val total: Int, val started: Long) { val parts = ArrayList<ByteArray>(); var received = 0; var next = 0 }
    private val streams = HashMap<Long, Stream>()
    /** Ids of streams refused, expired or aborted whose remaining pieces are still on the wire: dropped silently. */
    private val ignored = object : LinkedHashMap<Long, Unit>() { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Unit>) = size > 1024 }
    /** Bytes reserved by the streams in progress (their announced totals). */
    var reserved = 0L; private set
    val active get() = streams.size

    fun accept(f: Fragment): Result {
        if (f.streamId in ignored) return Result.Pending
        val first = f.flags and Fragment.FIRST != 0; val last = f.flags and Fragment.LAST != 0
        var s = streams[f.streamId]
        if (first) {
            if (s != null) throw FragmentViolation("fragment stream ${f.streamId} started twice")
            if (f.index != 0) throw FragmentViolation("fragment stream ${f.streamId}: first piece has index ${f.index}")
            val max = maxMessage()
            if (f.total <= 0 || f.total > max) throw FragmentViolation("fragmented message of ${f.total} bytes exceeds limits.max-message ($max)")
            if (streams.size >= maxStreams) return refuse(f.streamId, "too many messages being reassembled (limits.max-reassemblies $maxStreams)")
            if (!budget.tryReserve(f.total.toLong())) return refuse(f.streamId, "reassembly memory budget exhausted (${budget.inUse} of ${budget.limit} bytes in use, ${f.total} more asked; limits.reassembly-memory)")
            s = Stream(f.streamId, f.total, clock()); streams[f.streamId] = s; reserved += f.total   // the budget was reserved above
        } else if (s == null) throw FragmentViolation("fragment of unknown stream ${f.streamId} (piece ${f.index})")
        if (f.index != s.next) throw FragmentViolation("fragment stream ${f.streamId}: piece ${f.index} where ${s.next} was expected (duplicate or out of order)")
        if (f.data.isEmpty() && !last) throw FragmentViolation("fragment stream ${f.streamId}: empty piece ${f.index}")
        if (s.received.toLong() + f.data.size > s.total) { drop(s); throw FragmentViolation("fragment stream ${f.streamId}: more bytes than the announced ${s.total}") }
        s.parts += f.data; s.received += f.data.size; s.next++
        if (!last) return Result.Pending
        drop(s)
        if (s.received != s.total) throw FragmentViolation("fragment stream ${f.streamId}: ended after ${s.received} of ${s.total} bytes")
        val out = ByteArray(s.total); var o = 0
        for (p in s.parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return Result.Done(out)
    }

    private fun refuse(id: Long, why: String): Result { ignored[id] = Unit; return Result.Refused(id, why) }
    private fun drop(s: Stream) { if (streams.remove(s.id) != null) { reserved -= s.total; budget.release(s.total.toLong()) } }

    /** The link is gone: everything collected is freed (the budget is shared by all links). */
    fun close() { streams.values.toList().forEach { drop(it) } }

    /** The sender gave up on [id]: free what was collected. Later pieces of it (none should come) are ignored. */
    fun abort(id: Long) { streams[id]?.let { drop(it) }; ignored[id] = Unit }

    /** Drops the streams older than the timeout; returns their ids (the caller tells the senders). */
    fun expire(): List<Long> {
        val now = clock(); val out = ArrayList<Long>()
        for (s in streams.values.toList()) if (now - s.started > timeoutNanos) { drop(s); ignored[s.id] = Unit; out += s.id }
        return out
    }
}

/**
 * Sending side: messages too big for one frame wait here as streams and leave piece by piece, round robin, so a big transfer never holds up the small
 * messages (those are written directly, between two pieces). Streams with the same order key (see OrderedMessage) go strictly one after the other, so
 * their order is kept. [P] is whatever the caller wants back when a stream ends (the write promise).
 */
class FragmentScheduler<P>(chunkSize: Int) {
    /** Bytes per piece; the writer lowers it to what the peer's frame limit takes once the peer's limits are known. */
    @Volatile var chunkSize: Int = chunkSize
    class Stream<P>(val id: Long, val key: String?, val bytes: ByteArray, val token: P) { var offset = 0; var index = 0 }
    class Piece<P>(val stream: Stream<P>, val fragment: Fragment, val last: Boolean)

    private val active = ArrayDeque<Stream<P>>()
    private val waiting = HashMap<String, ArrayDeque<Stream<P>>>()
    private val keys = HashMap<String, Int>()      // key -> streams active or waiting
    private var nextId = 1L
    /** Bytes not yet cut into pieces. */
    var pendingBytes = 0L; private set
    val isEmpty get() = active.isEmpty()
    fun waitingKeys() = waiting.size
    val size get() = active.size + waiting.values.sumOf { it.size }

    /** Is a stream with this order key still in progress (then an ordered message must join the queue too, however small)? */
    fun busy(key: String) = (keys[key] ?: 0) > 0

    fun enqueue(bytes: ByteArray, key: String?, token: P): Long {
        val s = Stream(nextId++, key, bytes, token); pendingBytes += bytes.size
        if (key == null) active += s
        else { val n = keys[key] ?: 0; keys[key] = n + 1; if (n == 0) active += s else waiting.getOrPut(key) { ArrayDeque() } += s }
        return s.id
    }

    /** The next piece (one of each active stream in turn), or null when nothing is queued. */
    fun next(): Piece<P>? {
        val s = active.removeFirstOrNull() ?: return null
        val n = minOf(chunkSize, s.bytes.size - s.offset)
        val last = s.offset + n >= s.bytes.size
        val first = s.index == 0
        val f = Fragment(s.id, s.index, (if (first) Fragment.FIRST else 0) or (if (last) Fragment.LAST else 0), if (first) s.bytes.size else 0, s.bytes.copyOfRange(s.offset, s.offset + n))
        s.offset += n; s.index++; pendingBytes -= n
        if (last) finish(s) else active += s
        return Piece(s, f, last)
    }

    /** Stops stream [id] (the peer refused it). Returns its token, or null when it was already done. */
    fun abort(id: Long): P? {
        val s = active.firstOrNull { it.id == id } ?: waiting.values.firstNotNullOfOrNull { q -> q.firstOrNull { it.id == id } } ?: return null
        pendingBytes -= s.bytes.size - s.offset
        val wasActive = active.remove(s)
        if (!wasActive) waiting[s.key]?.remove(s)
        s.key?.let { k ->
            val left = (keys[k] ?: 1) - 1
            if (left <= 0) { keys.remove(k); waiting.remove(k) } else { keys[k] = left; if (wasActive) waiting[k]?.removeFirstOrNull()?.let { active += it } }
        }
        return s.token
    }

    /** Everything still queued (link closed): the tokens, to fail them. */
    fun clear(): List<P> { val t = (active + waiting.values.flatten()).map { it.token }; active.clear(); waiting.clear(); keys.clear(); pendingBytes = 0; return t }

    private fun finish(s: Stream<P>) {
        val k = s.key ?: return
        val left = (keys[k] ?: 1) - 1
        if (left <= 0) { keys.remove(k); waiting.remove(k) } else { keys[k] = left; waiting[k]?.removeFirstOrNull()?.let { active += it } }
    }
}
