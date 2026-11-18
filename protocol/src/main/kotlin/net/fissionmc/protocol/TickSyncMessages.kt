package net.fissionmc.protocol

/**
 * Network-wide tick state (`/tick rate|freeze|unfreeze|step|sprint`), wire ids 231-234. The master owns the state (rate, frozen); every backend runs at the same tick speed.
 * A command typed on a backend is executed there as usual and reported with [TickSyncChange]; the master validates it, stores it and sends [TickSyncApply] to all OTHER backends,
 * which apply it through their TickRateManager (never by re-running the command). Transient actions (step, sprint) are forwarded, not stored.
 */
object TickAction {
    const val RATE = 0          // value = ticks per second (1..10000)
    const val FREEZE = 1        // value ignored
    const val UNFREEZE = 2
    const val STEP = 3          // value = ticks to step (needs a frozen network)
    const val STEP_STOP = 4
    const val SPRINT = 5        // value = ticks to sprint
    const val SPRINT_STOP = 6
    const val MAX = 6
}

/** Backend -> master (231): the backend attached or reconnected and reports its own state. The master adopts it only when it has no state yet, then answers [TickSyncState]. */
data class TickSyncHello(val rate: Float, val frozen: Boolean) : NodeMessage

/** Backend -> master (232): a tick command succeeded on this backend. */
data class TickSyncChange(val action: Int, val value: Float) : NodeMessage

/** Master -> every backend except [origin] (233): one validated change, to be applied through the tick rate manager. */
data class TickSyncApply(val action: Int, val value: Float, val origin: String) : NodeMessage

/** Master -> backend (234): the canonical state, the answer to a hello and to a change the master rejected. [enabled] = false (`tick.sync: false`): the backend behaves as before. */
data class TickSyncState(val enabled: Boolean, val rate: Float, val frozen: Boolean) : NodeMessage

fun MessageRegistry.registerTickSyncMessages(): MessageRegistry = this
    .register<TickSyncHello>(231, { m, b -> b.writeFloat(m.rate).writeBoolean(m.frozen) }, { b -> TickSyncHello(b.readFloat(), b.readBoolean()) })
    .register<TickSyncChange>(232, { m, b -> b.writeVarInt(m.action).writeFloat(m.value) }, { b -> TickSyncChange(b.readVarInt(), b.readFloat()) })
    .register<TickSyncApply>(233, { m, b -> b.writeVarInt(m.action).writeFloat(m.value).writeString(m.origin, 64) }, { b -> TickSyncApply(b.readVarInt(), b.readFloat(), b.readString(64)) })
    .register<TickSyncState>(234, { m, b -> b.writeBoolean(m.enabled).writeFloat(m.rate).writeBoolean(m.frozen) }, { b -> TickSyncState(b.readBoolean(), b.readFloat(), b.readBoolean()) })
