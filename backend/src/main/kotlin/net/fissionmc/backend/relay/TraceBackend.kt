package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.net.trace.Trace
import net.fissionmc.protocol.TraceDump

/** Flight recorder control from the master (wire 105): dump the ring to this node's own file and answer with the path, write a mark, switch tracing on or off. */
object TraceBackend {
    private fun reply(reqId: String, text: String = "", path: String = "", events: Int = 0) {
        NodeBootstrap.link?.takeIf { it.isOpen }?.send(TraceDump(TraceDump.REPLY, reqId, text = text, node = NodeBootstrap.nodeId, path = path, events = events, dropped = Trace.dropped))
    }

    fun onMessage(m: TraceDump) {
        when (m.op) {
            TraceDump.DUMP -> Trace.dump(m.seconds, m.reqId).whenComplete { r, e -> if (e != null) reply(m.reqId, "dump failed: $e") else reply(m.reqId, path = r.path, events = r.events) }
            TraceDump.MARK -> { Trace.mark(m.text, m.node.ifEmpty { null }) }
            TraceDump.START -> { Trace.start(); Trace.mark("trace started", m.node); reply(m.reqId, Trace.status()) }
            TraceDump.STOP -> { Trace.mark("trace stopped", m.node); val off = Trace.stop(); reply(m.reqId, if (off) "trace off" else "trace stays ON (forced by flag)") }
        }
    }
}
