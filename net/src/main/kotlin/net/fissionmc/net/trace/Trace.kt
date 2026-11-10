package net.fissionmc.net.trace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The result of [Trace.dump]: the file written, how many events it holds, how many events were dropped so far by this node's writer. */
data class TraceDumpResult(val path: String, val events: Int, val dropped: Long)

/**
 * The flight recorder of this node (one per JVM). When tracing is off the whole cost of a call site is ONE volatile boolean read:
 * ```
 * Trace.emit("switch.start") { player(name, uuid); str("to", node) }   // inline: the lambda body runs (and allocates) only when [on]
 * ```
 * When on, an event is one JSON line `{"w":wall ms,"ns":monotonic nanos,"node":..,"seq":..,"type":..,payload}`; it goes into the in-memory ring ([TraceRing], the last
 * `trace.ring-seconds`) and is queued for the file writer ([TraceWriter]). Neither blocks: the queue is bounded and a full queue drops the line and counts it.
 * Switched on by `trace.enabled` in fission.yml (live), `/fission trace start`, `-Dfission.trace` or FISSION_TRACE=1 (the flag cannot be stopped).
 */
object Trace {
    /** The only thing a call site reads while tracing is off. */
    @JvmField @Volatile var on: Boolean = false

    private val forced: Boolean = (System.getProperty("fission.trace") ?: System.getenv("FISSION_TRACE"))?.let { it.isEmpty() || it.equals("true", true) || it == "1" } ?: false

    @Volatile var node: String = "?"
        private set
    private var dir: Path = Path.of("trace")
    private var cfg = TraceConfig()
    private var override: Boolean? = null            // /fission trace start|stop
    @Volatile private var writer: TraceWriter? = null
    private val ring = TraceRing(300L * 1_000_000_000L)
    private val seq = AtomicLong()
    private val dumpExec = Executors.newSingleThreadExecutor { r -> Thread(r, "trace-dump").also { it.isDaemon = true } }

    /** Who this node is and where its files go. Call once at start (before [apply]). */
    @Synchronized fun init(nodeId: String, directory: Path) { node = nodeId; dir = directory }

    /** Applies the `trace:` section (start and every live config reload). A changed `enabled` value also clears a `/fission trace start|stop` override. */
    @Synchronized fun apply(c: TraceConfig) {
        if (c.enabled != cfg.enabled) override = null
        val sizeChanged = c.maxFileMb != cfg.maxFileMb
        cfg = c
        ring.windowNanos = c.ringSeconds * 1_000_000_000L
        if (sizeChanged && on) { writer?.close(); writer = null }
        refresh()
    }

    /** `/fission trace start`: true when tracing is on afterwards. */
    @Synchronized fun start(): Boolean { override = true; refresh(); return on }

    /** `/fission trace stop`: false when it stays on because -Dfission.trace / FISSION_TRACE forces it. */
    @Synchronized fun stop(): Boolean { override = false; refresh(); return !on }

    val isForced: Boolean get() = forced

    private fun refresh() {
        val want = forced || (override ?: cfg.enabled)
        if (want && writer == null) {
            writer = try { TraceWriter(dir, node, cfg.maxFileMb * 1024L * 1024L) } catch (e: Exception) { System.err.println("[trace] cannot write to $dir: $e"); null }
        }
        if (!want && writer != null) { writer?.close(); writer = null }
        val was = on
        on = want
        if (want && !was) emit("trace.start") { str("dir", dir.toAbsolutePath().toString()); int("ringSeconds", cfg.ringSeconds); int("maxFileMb", cfg.maxFileMb); bool("forced", forced) }
        if (!want && was) { val e = TraceEvent("trace.stop"); commitAlways(e) }
    }

    /** One line of status for `/fission trace` and logs. */
    fun status(): String = "trace ${if (on) "ON" else "off"}${if (forced) " (forced by flag)" else ""} on $node: ${ring.size} events in the ring (${cfg.ringSeconds} s), ${writer?.written?.get() ?: 0} written, ${writer?.dropped?.get() ?: 0} dropped, dir ${dir.toAbsolutePath()}"

    val dropped: Long get() = writer?.dropped?.get() ?: 0L

    fun begin(type: String): TraceEvent = TraceEvent(type)

    /** Stamps and queues [e]; no-op while tracing is off. */
    fun commit(e: TraceEvent) { if (on) commitAlways(e) }

    private fun commitAlways(e: TraceEvent) {
        val nanos = System.nanoTime()
        val sb = StringBuilder(e.sb.length + 96)
        sb.append("{\"w\":").append(System.currentTimeMillis()).append(",\"ns\":").append(nanos).append(",\"node\":")
        TraceEvent.quote(sb, node)
        sb.append(",\"seq\":").append(seq.incrementAndGet()).append(",\"type\":"); TraceEvent.quote(sb, e.type)
        sb.append(e.sb).append('}')
        val line = sb.toString()
        ring.add(nanos, line)
        writer?.offer(line)
    }

    /**
     * Records an event when tracing is on; [block] fills the payload. Inline on purpose: while off, nothing is evaluated or allocated.
     * Never throws into the caller.
     */
    inline fun emit(type: String, block: TraceEvent.() -> Unit) {
        if (!on) return
        val e = begin(type)
        try { e.block() } catch (t: Throwable) { e.str("traceError", t.toString()) }
        commit(e)
    }

    /** `/fission trace mark <text>`: a labelled line, the anchor for `trace-merge.py --around`. */
    fun mark(text: String, by: String? = null) { emit("mark") { str("text", text); if (by != null) str("by", by) } }

    /** Writes the ring's last [seconds] (all of it when <= 0) to `<dir>/<node>-dump-<time>-<id>.jsonl` off the calling thread. */
    fun dump(seconds: Int, id: String): CompletableFuture<TraceDumpResult> {
        val f = CompletableFuture<TraceDumpResult>()
        dumpExec.execute {
            try { f.complete(dumpNow(seconds, id)) } catch (t: Throwable) { f.completeExceptionally(t) }
        }
        return f
    }

    fun dumpNow(seconds: Int, id: String): TraceDumpResult {
        val now = System.nanoTime()
        val lines = ring.snapshot(now, if (seconds <= 0) Long.MAX_VALUE / 2_000_000_000L else seconds.toLong())
        Files.createDirectories(dir)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifEmpty { "x" }
        val file = dir.resolve("$node-dump-$stamp-$safeId.jsonl").toAbsolutePath()
        val head = "{\"w\":${System.currentTimeMillis()},\"ns\":$now,\"node\":\"$node\",\"seq\":0,\"type\":\"trace.dump\",\"id\":\"$safeId\",\"seconds\":$seconds,\"events\":${lines.size},\"dropped\":$dropped,\"on\":$on}"
        Files.newBufferedWriter(file, StandardCharsets.UTF_8).use { w -> w.write(head); w.write("\n"); for (l in lines) { w.write(l); w.write("\n") } }
        return TraceDumpResult(file.toString(), lines.size, dropped)
    }

    /** For tests: back to the initial state (off, no override, empty ring). */
    @Synchronized fun resetForTests() {
        writer?.close(); writer = null; on = false; override = null; cfg = TraceConfig(); ring.clear(); node = "?"; dir = Path.of("trace")
    }
}
