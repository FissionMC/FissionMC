package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.server.MinecraftServer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Backend side of the network-wide tick state (`/tick rate|freeze|unfreeze|step|sprint`), see [TickSyncHello]. A /tick command that succeeded here is reported by
 * `TickCommandSyncMixin`; the master's changes and states are applied on the server thread through the server's TickRateManager (never by running the command again), with [applying] set so
 * the mixin does not report them back. Until the master has answered the hello (or when it says `tick.sync: false`) nothing is reported and nothing applied: the backend behaves as before.
 */
object TickSyncBackend {
    private val log = Logger.getLogger("FissionMC")
    private val inbox = ConcurrentLinkedQueue<NodeMessage>()
    @Volatile private var server: MinecraftServer? = null
    @Volatile private var active = false
    private var helloSent = false
    @Volatile private var applying = false
    /** A master state sent before the master saw our own change would revert it: states are ignored for a while after we reported. */
    @Volatile private var holdUntil = 0L
    private const val HOLD_NANOS = 3_000_000_000L
    val reported = AtomicLong(); val applied = AtomicLong()

    fun onMessage(m: NodeMessage) { inbox.add(m); server?.let { s -> s.execute { drain(s) } } }
    fun onReconnect() { inbox.clear(); helloSent = false; active = false }

    fun summary(): String = if (!active) "tick sync: off" else "tick sync: on, reported $reported, applied $applied"

    /** From `TickCommandSyncMixin` (server thread): a tick command succeeded here. */
    @JvmStatic fun report(action: Int, value: Float) {
        if (applying || !active) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        link.send(TickSyncChange(action, value)); reported.incrementAndGet(); holdUntil = System.nanoTime() + HOLD_NANOS
        if (net.fissionmc.net.FissionDebug.on) log.info("[fission] tick change reported: action $action $value")
    }

    /** Every tick, before the server ticks. */
    fun tick(s: MinecraftServer) {
        server = s
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        if (!helloSent) { helloSent = true; val t = s.tickRateManager(); link.send(TickSyncHello(t.tickrate(), t.isFrozen && !t.isSprinting)) }
        drain(s)
    }

    private fun drain(s: MinecraftServer) {
        while (true) {
            when (val m = inbox.poll() ?: break) {
                is TickSyncState -> {
                    val was = active; active = m.enabled
                    if (!was && active) log.info("[fission] tick sync with the master: on (${"%.1f".format(m.rate)} tps${if (m.frozen) ", frozen" else ""})")
                    if (active && System.nanoTime() >= holdUntil) applyState(s, m)
                }
                is TickSyncApply -> if (active) applyAction(s, m.action, m.value)
            }
        }
    }

    private fun applyState(s: MinecraftServer, m: TickSyncState) {
        val t = s.tickRateManager()
        applying = true
        try {
            if (t.tickrate() != m.rate && TickSyncLimits.validRate(m.rate)) { t.setTickRate(m.rate); count("rate ${m.rate}") }
            // a sprint unfreezes the game on its own and restores the flag at its end: the frozen flag is not compared meanwhile
            if (!t.isSprinting && t.isFrozen != m.frozen) { t.setFrozen(m.frozen); count(if (m.frozen) "freeze" else "unfreeze") }
        } catch (e: Throwable) { log.warning("[fission] tick state could not be applied: $e") } finally { applying = false }
    }

    private fun applyAction(s: MinecraftServer, action: Int, value: Float) {
        val t = s.tickRateManager()
        applying = true
        try {
            when (action) {
                TickAction.RATE -> if (TickSyncLimits.validRate(value)) t.setTickRate(value)
                TickAction.FREEZE -> { if (t.isSprinting) t.stopSprinting(); if (t.isSteppingForward) t.stopStepping(); t.setFrozen(true) }
                TickAction.UNFREEZE -> t.setFrozen(false)
                TickAction.STEP -> t.stepGameIfPaused(value.toInt())
                TickAction.STEP_STOP -> t.stopStepping()
                TickAction.SPRINT -> t.requestGameToSprint(value.toInt())
                TickAction.SPRINT_STOP -> t.stopSprinting()
                else -> return
            }
            count("action $action $value")
        } catch (e: Throwable) { log.warning("[fission] tick action $action could not be applied: $e") } finally { applying = false }
    }

    private fun count(what: String) { applied.incrementAndGet(); if (net.fissionmc.net.FissionDebug.on) log.info("[fission] tick sync applied: $what") }
}

/** Same bounds as the master (vanilla: 1..10000 ticks per second). */
internal object TickSyncLimits { fun validRate(v: Float) = !v.isNaN() && !v.isInfinite() && v in 1f..10_000f }
