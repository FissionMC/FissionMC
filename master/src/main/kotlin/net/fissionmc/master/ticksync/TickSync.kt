package net.fissionmc.master.ticksync

import net.fissionmc.master.NodeRegistry
import net.fissionmc.master.RegisteredNode
import net.fissionmc.net.ConfigSection
import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.TickAction
import net.fissionmc.protocol.TickSyncApply
import net.fissionmc.protocol.TickSyncChange
import net.fissionmc.protocol.TickSyncHello
import net.fissionmc.protocol.TickSyncState
import java.util.concurrent.atomic.AtomicLong

/** The `tick:` section of fission.yml. */
class TickSyncConfig(val sync: Boolean = true) {
    companion object { fun from(root: ConfigSection): TickSyncConfig = TickSyncConfig(root.section("tick").bool("sync", true)) }
}

/**
 * The canonical tick state of the network: tick rate and frozen flag (what a joining backend must be given), plus the validation of every `/tick` action a backend reports.
 * Step and sprint are transient: validated and forwarded, never stored. Pure logic, the [TickSyncHub] drives it (one lock).
 */
class TickSyncModel(rate: Float = DEFAULT_RATE, frozen: Boolean = false) {
    sealed interface Outcome {
        /** Accepted: forward [action]/[value] to the other backends. */
        data class Apply(val action: Int, val value: Float) : Outcome
        data class Reject(val reason: String) : Outcome
    }

    var rate: Float = rate; private set
    var frozen: Boolean = frozen; private set
    /** True once the state was set by a backend (hello) or a command; before that the master has no opinion and adopts the first backend's. */
    var initialized = false; private set

    /** A backend's own state at attach: adopted only while the master has none. Returns true if adopted. */
    fun adopt(rate: Float, frozen: Boolean): Boolean {
        if (initialized || !validRate(rate)) return false
        this.rate = rate; this.frozen = frozen; initialized = true; return true
    }

    fun handle(action: Int, value: Float): Outcome = when (action) {
        TickAction.RATE -> if (!validRate(value)) Outcome.Reject("tick rate must be $MIN_RATE..$MAX_RATE, got $value") else { rate = value; initialized = true; Outcome.Apply(action, value) }
        TickAction.FREEZE -> { frozen = true; initialized = true; Outcome.Apply(action, 0f) }
        TickAction.UNFREEZE -> { frozen = false; initialized = true; Outcome.Apply(action, 0f) }
        TickAction.STEP -> when {
            !frozen -> Outcome.Reject("the network is not frozen")
            !validCount(value, MAX_STEP) -> Outcome.Reject("step must be 1..$MAX_STEP ticks, got $value")
            else -> Outcome.Apply(action, value)
        }
        TickAction.SPRINT -> if (!validCount(value, MAX_SPRINT)) Outcome.Reject("sprint must be 1..$MAX_SPRINT ticks, got $value") else Outcome.Apply(action, value)
        TickAction.STEP_STOP, TickAction.SPRINT_STOP -> Outcome.Apply(action, 0f)
        else -> Outcome.Reject("unknown tick action $action")
    }

    fun summary(): String = "tick ${"%.1f".format(rate)} tps${if (frozen) ", frozen" else ""}"

    companion object {
        const val DEFAULT_RATE = 20f
        const val MIN_RATE = 1f
        const val MAX_RATE = 10_000f
        const val MAX_STEP = 1_000_000
        const val MAX_SPRINT = 10_000_000
        fun validRate(v: Float) = !v.isNaN() && !v.isInfinite() && v in MIN_RATE..MAX_RATE
        fun validCount(v: Float, max: Int) = !v.isNaN() && !v.isInfinite() && v >= 1f && v <= max && v == Math.floor(v.toDouble()).toFloat()
    }
}

/**
 * Master side of the tick sync: answers a backend's hello with the state, validates the `/tick` changes the backends report in arrival order, stores them and forwards them to the
 * OTHER backends (the origin already ran the command). A rejected change is answered with the canonical state so the origin goes back. With `tick.sync: false` the hub only
 * tells the backends so (they keep their own tick state).
 */
class TickSyncHub(private val registry: NodeRegistry, val model: TickSyncModel, val enabled: Boolean, periodSeconds: Long = 5, private val log: (String) -> Unit = {}) : java.io.Closeable {
    val applied = AtomicLong(); val rejected = AtomicLong()
    /** Two backends changing the state at the same moment each miss the other's change (a change is not sent back to its origin): the periodic state broadcast makes them agree on the master's order. */
    private val exec = if (enabled && periodSeconds > 0) java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fission-tick-sync").also { it.isDaemon = true } }
        .also { it.scheduleWithFixedDelay({ runCatching { broadcastState() } }, periodSeconds, periodSeconds, java.util.concurrent.TimeUnit.SECONDS) } else null

    fun broadcastState() { if (enabled) registry.broadcast(synchronized(model) { state() }) }
    override fun close() { exec?.shutdownNow() }

    private fun state() = TickSyncState(enabled, model.rate, model.frozen)

    fun onMessage(node: RegisteredNode, msg: NodeMessage) {
        val id = node.info.nodeId
        if (!enabled) { if (msg is TickSyncHello && node.link.isOpen) node.link.send(state()); return }
        when (msg) {
            is TickSyncHello -> {
                val reply: TickSyncState
                synchronized(model) { if (model.adopt(msg.rate, msg.frozen)) log("[tick-sync] $id seeded the tick state (${model.summary()})"); reply = state() }
                if (node.link.isOpen) node.link.send(reply)
            }
            is TickSyncChange -> {
                val out = synchronized(model) { model.handle(msg.action, msg.value) }
                when (out) {
                    is TickSyncModel.Outcome.Apply -> {
                        applied.incrementAndGet(); log("[tick-sync] $id: action ${msg.action} ${msg.value} (${summary()})")
                        registry.broadcast(TickSyncApply(out.action, out.value, id), id)
                    }
                    is TickSyncModel.Outcome.Reject -> {
                        rejected.incrementAndGet(); log("[tick-sync] $id: rejected: ${out.reason}")
                        val reply = synchronized(model) { state() }
                        if (node.link.isOpen) node.link.send(reply)
                    }
                }
            }
            else -> {}
        }
    }

    fun summary(): String = if (!enabled) "tick sync off" else synchronized(model) { model.summary() }
}
