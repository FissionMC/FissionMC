package net.fissionmc.net.trace

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Appends event lines to `<dir>/<node>-<date>.jsonl` on its own daemon thread. [offer] never blocks: a full queue drops the line and counts it (a `trace.dropped` line
 * says how many, once the writer catches up). When the file would exceed [maxBytes] it is renamed to `<node>-<date>.<n>.jsonl` and a new one starts; only the newest
 * [keep] rotated files of a day are kept, so the disk use is bounded by (keep + 1) * maxBytes per day.
 */
class TraceWriter(private val dir: Path, private val node: String, private val maxBytes: Long, private val keep: Int = 4, queueSize: Int = 16_384,
                  private val today: () -> String = { LocalDate.now().toString() }) : Closeable {
    private val queue = ArrayBlockingQueue<String>(queueSize)
    val dropped = AtomicLong()
    val written = AtomicLong()
    @Volatile private var open = true
    private var reportedDrops = 0L
    private var out: java.io.OutputStream? = null
    private var base = ""
    private var size = 0L
    private val thread = Thread({ run() }, "trace-writer").also { it.isDaemon = true }

    init { Files.createDirectories(dir); thread.start() }

    /** True if queued; false (and counted) if the queue was full or the writer is closed. */
    fun offer(line: String): Boolean {
        if (!open || !queue.offer(line)) { dropped.incrementAndGet(); return false }
        return true
    }

    /** The file lines are being appended to right now. */
    fun currentFile(): Path = dir.resolve("$node-${today()}.jsonl")

    private fun run() {
        try {
            while (true) {
                val line = queue.poll(200, TimeUnit.MILLISECONDS)
                if (line == null) { out?.flush(); reportDrops(); if (!open && queue.isEmpty()) break; continue }
                write(line)
                if (queue.isEmpty()) { out?.flush(); reportDrops() }
            }
        } catch (_: InterruptedException) {
        } finally { try { out?.flush(); out?.close() } catch (_: Exception) {} }
    }

    private fun reportDrops() {
        val d = dropped.get()
        if (d > reportedDrops) {
            val line = "{\"w\":${System.currentTimeMillis()},\"ns\":${System.nanoTime()},\"node\":\"$node\",\"seq\":0,\"type\":\"trace.dropped\",\"count\":${d - reportedDrops},\"total\":$d}"
            reportedDrops = d; write(line)
        }
    }

    private fun write(line: String) {
        try {
            val b = (line + "\n").toByteArray(StandardCharsets.UTF_8)
            val day = "$node-${today()}"
            if (out == null || day != base) { switchTo(day) }
            if (size > 0 && size + b.size > maxBytes) rotate()
            out!!.write(b); size += b.size; written.incrementAndGet()
        } catch (e: Exception) { System.err.println("[trace] write failed: $e"); dropped.incrementAndGet() }
    }

    private fun switchTo(day: String) {
        try { out?.flush(); out?.close() } catch (_: Exception) {}
        base = day
        val f = dir.resolve("$base.jsonl")
        size = if (Files.exists(f)) Files.size(f) else 0L
        out = java.io.BufferedOutputStream(Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 64 * 1024)
    }

    private fun rotate() {
        try { out?.flush(); out?.close() } catch (_: Exception) {}
        out = null
        val f = dir.resolve("$base.jsonl")
        val prefix = "$base."
        fun index(p: Path): Int? = p.fileName.toString().let { n -> if (n.startsWith(prefix) && n.endsWith(".jsonl") && n.length > prefix.length + 6) n.substring(prefix.length, n.length - 6).toIntOrNull() else null }
        val existing = Files.list(dir).use { st -> st.toList() }.mapNotNull { p -> index(p)?.let { it to p } }.sortedBy { it.first }
        val next = (existing.lastOrNull()?.first ?: 0) + 1
        Files.move(f, dir.resolve("$base.$next.jsonl"))
        // keep the newest [keep] rotated files (the one just made included)
        val all = existing.map { it.second } + listOf(dir.resolve("$base.$next.jsonl"))
        for (old in all.dropLast(keep)) Files.deleteIfExists(old)
        out = java.io.BufferedOutputStream(Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 64 * 1024)
        size = 0
    }

    /** Writes what is queued, then stops the thread. */
    override fun close() {
        open = false
        thread.join(5_000)
    }
}
