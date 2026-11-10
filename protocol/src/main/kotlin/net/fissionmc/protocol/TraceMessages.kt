package net.fissionmc.protocol

/**
 * Flight recorder control (wire id 105), both directions. [op] = [DUMP] / [MARK] / [START] / [STOP] (master -> backend) or [REPLY] (backend -> master).
 * [reqId] ties the replies to the `/fission trace dump` that asked. DUMP: write the ring's last [seconds] to the node's own file; the backend answers REPLY with its
 * [node] id, the file [path] (empty and [text] = the error when it failed), the [events] written and the lines [dropped] so far. MARK: write a labelled line ([text]).
 * In requests [node] is who asked (the admin's name). START / STOP: switch tracing on or off on the backend (answered with a REPLY whose [text] says the state).
 */
class TraceDump(val op: Int, val reqId: String, val seconds: Int = 0, val text: String = "", val node: String = "", val path: String = "", val events: Int = 0, val dropped: Long = 0) : NodeMessage {
    companion object { const val DUMP = 0; const val MARK = 1; const val START = 2; const val STOP = 3; const val REPLY = 4 }
}

fun MessageRegistry.registerTraceMessages(): MessageRegistry = this
    .register<TraceDump>(105, { m, b ->
        b.writeVarInt(m.op).writeString(m.reqId, 64).writeVarInt(m.seconds).writeString(m.text, 1024).writeString(m.node, 64).writeString(m.path, 1024).writeVarInt(m.events).writeLong(m.dropped)
    }, { b ->
        val op = b.readVarInt(); require(op in 0..4) { "bad trace op $op" }
        TraceDump(op, b.readString(64), b.readVarInt(), b.readString(1024), b.readString(64), b.readString(1024), b.readVarInt(), b.readLong())
    })
