package net.fissionmc.master

import net.fissionmc.net.trace.Trace
import net.fissionmc.protocol.TraceDump
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `/fission trace ...` on the master: one command acts on the master AND every connected backend (wire message [TraceDump], id 105). A dump writes the master's ring to its own
 * file and asks every backend to do the same; each backend answers with the path of its file, and [report] gets one line per node as the answers arrive.
 */
class TraceHub(private val registry: NodeRegistry) {
    private class Request(val report: (String) -> Unit, val expected: Int, val at: Long = System.nanoTime()) { val replies = AtomicInteger() }
    private val requests = ConcurrentHashMap<String, Request>()
    private val counter = AtomicInteger()

    private fun live() = registry.all.filter { it.link.isOpen }

    private fun newId(): String = "${java.lang.Long.toString(System.currentTimeMillis() / 1000, 36)}${counter.incrementAndGet()}"

    fun start(by: String, report: (String) -> Unit) {
        val on = Trace.start()
        report("master: " + Trace.status())
        val id = newId(); requests[id] = Request(report, live().size)
        registry.broadcast(TraceDump(TraceDump.START, id, node = by.take(60)))
        if (on) Trace.mark("trace started", by)
    }

    fun stop(by: String, report: (String) -> Unit) {
        Trace.mark("trace stopped", by)
        val off = Trace.stop()
        report("master: " + (if (off) "trace off" else "trace stays ON (forced by -Dfission.trace / FISSION_TRACE)"))
        val id = newId(); requests[id] = Request(report, live().size)
        registry.broadcast(TraceDump(TraceDump.STOP, id, node = by.take(60)))
    }

    fun mark(text: String, by: String, report: (String) -> Unit) {
        Trace.mark(text, by)
        val n = live().size
        registry.broadcast(TraceDump(TraceDump.MARK, newId(), text = text.take(900), node = by.take(60)))
        report("mark '$text' written on the master" + (if (Trace.on) "" else " (tracing is off there: nothing recorded)") + " and sent to $n backends")
    }

    /** Dumps the master's ring (last [seconds], 0 = all of it) and asks every backend to do the same. */
    fun dump(seconds: Int, by: String, report: (String) -> Unit) {
        val id = newId()
        requests.entries.removeIf { System.nanoTime() - it.value.at > 300_000_000_000L }
        val targets = live()
        requests[id] = Request(report, targets.size)
        Trace.mark("dump requested", by)
        registry.broadcast(TraceDump(TraceDump.DUMP, id, seconds, node = by.take(60)))
        Trace.dump(seconds, id).whenComplete { r, e ->
            if (e != null) report("master: dump failed: $e") else report("master: ${r.path} (${r.events} events, ${r.dropped} dropped)" + if (!Trace.on) " - tracing is off, the ring only holds what was recorded earlier" else "")
            if (targets.isEmpty()) report("no backends connected; request $id done")
        }
    }

    /** A backend's answer. */
    fun onReply(node: RegisteredNode, m: TraceDump) {
        if (m.op != TraceDump.REPLY) return
        val r = requests[m.reqId] ?: return
        val line = if (m.path.isNotEmpty()) "${node.info.nodeId}: ${m.path} (${m.events} events, ${m.dropped} dropped)" else "${node.info.nodeId}: ${m.text}"
        r.report(line)
        if (r.replies.incrementAndGet() >= r.expected) requests.remove(m.reqId)
    }
}
