package net.fissionmc.backend.node

import net.fissionmc.net.TickPace
import net.fissionmc.protocol.NodeTickRate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * The wall-clock side of the backend: ONE daemon thread (`fission-link-sched`) for everything the other nodes expect at fixed REAL time and that needs no world access.
 * The game thread ticks at whatever rate the operator chose (`/tick rate 1` = one tick per second) and can stall for seconds (GC, a lag spike, `/tick sprint`), so a heartbeat counted in
 * ticks arrives 20 x too late at 1 tps and the master drops what it was meant to keep alive.
 *
 * Rule: periodic duties towards peers run HERE; a duty that needs the world (entities, chunks, players) takes its snapshot on the game thread (every tick, cheap, paced by [net.fissionmc.net.Pacer])
 * and hands the last snapshot over through a volatile / concurrent field, and a task registered here sends it. Work that must run on the game thread (handover scans, spawns, chunk serving, flush
 * snapshots, block events) stays there and takes a bounded batch per tick ([net.fissionmc.net.TickPace.budgetMs]).
 *
 * Tasks must not block and must be short; an exception is logged and the task keeps running.
 */
object LinkScheduler {
    private val log = Logger.getLogger("FissionMC")
    private var exec: ScheduledExecutorService? = null

    @Volatile private var lastTickNanos = System.nanoTime()
    @Volatile private var configuredMs = 50
    @Volatile private var sentMs = -1
    @Volatile private var sentAt = 0L

    /** Starts the thread (once). */
    @Synchronized @JvmStatic fun start() {
        if (exec != null) return
        val e = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fission-link-sched").also { it.isDaemon = true } }
        exec = e
        every("tick rate report", 1000, ::reportTickRate)
    }

    /** Runs [task] every [periodMs] of wall-clock time on the scheduler thread. */
    @Synchronized @JvmStatic fun every(name: String, periodMs: Long, task: () -> Unit) {
        val e = exec ?: run { start(); exec!! }
        e.scheduleWithFixedDelay({ try { task() } catch (t: Throwable) { log.warning("[fission] scheduled duty '$name' failed: $t") } }, periodMs, periodMs, TimeUnit.MILLISECONDS)
    }

    /** Game thread, once per tick. */
    @JvmStatic fun gameTick(server: net.minecraft.server.MinecraftServer) {
        lastTickNanos = System.nanoTime()
        val rate = server.tickRateManager().tickrate()
        configuredMs = if (rate > 0f) (1000f / rate).toInt().coerceIn(1, 3_600_000) else 50
    }

    /** What one tick costs the game thread right now: the configured interval, or the time since the last tick when a tick is taking longer (lag, GC). Any thread. */
    @JvmStatic fun msPerTick(): Int = TickPace.effectiveMs(configuredMs, System.nanoTime() - lastTickNanos)

    /** Tells the master, so it gives requests that this game thread must answer more time (changed value at once, otherwise a refresh every 5 s: a restarted master learns it). */
    private fun reportTickRate() {
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: run { sentMs = -1; return }
        val ms = msPerTick(); val now = System.nanoTime()
        // 50 -> 55 is noise; report when the rate class changes by 25 % or on the refresh
        val changed = sentMs < 0 || ms > sentMs * 5 / 4 || ms < sentMs * 4 / 5
        if (!changed && now - sentAt < 5_000_000_000L) return
        sentMs = ms; sentAt = now
        link.send(NodeTickRate(ms))
    }
}
