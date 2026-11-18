package net.fissionmc.protocol

/**
 * Backend readiness, wire id 251. A backend joins the node link early (it pulls its world over it before the server even starts), but it cannot log a relayed player in
 * until its server loop runs. The backend therefore tells the master with [NodeReady] when it can: the first server tick of this link. Until then the master treats the
 * node as booting (no first entry, no region switch, no `/fission send` is started towards it); a switch started earlier simply waited for the 20 s switch timeout.
 * [bootMs] = milliseconds from the start of the backend process to this message (for the master's log line).
 */
data class NodeReady(val bootMs: Long) : NodeMessage

fun MessageRegistry.registerReadyMessages(): MessageRegistry = this
    .register<NodeReady>(251, { m, b -> b.writeLong(m.bootMs) }, { b -> NodeReady(b.readLong().coerceAtLeast(0)) })
