package net.fissionmc.master

import net.fissionmc.protocol.MasterStats
import net.fissionmc.protocol.NodeStat
import java.io.Closeable
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Sends [MasterStats] to every backend every [intervalSeconds] (timer, no sleeps). */
class MasterStatsPublisher(
    private val registry: NodeRegistry, private val relay: PlayerRelay, private val clients: () -> Int, intervalSeconds: Long = 2,
) : Closeable {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fission-master-stats").also { it.isDaemon = true } }
    private var lastNanos = System.nanoTime()
    private var lastIn = 0L; private var lastOut = 0L; private var lastBIn = 0L; private var lastBOut = 0L
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    init { exec.scheduleAtFixedRate({ runCatching { publish() } }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS) }

    fun snapshot(): MasterStats {
        val now = System.nanoTime(); val dt = ((now - lastNanos) / 1e9).coerceAtLeast(0.001); lastNanos = now
        val fi = relay.framesIn.get(); val fo = relay.framesOut.get(); val bi = relay.bytesIn.get(); val bo = relay.bytesOut.get()
        val rt = Runtime.getRuntime(); val players = relay.playersByNode()
        val s = MasterStats(
            System.currentTimeMillis(), clients(), registry.all.size, relay.attached,
            ((fi - lastIn) / dt).toLong(), ((fo - lastOut) / dt).toLong(), ((bi - lastBIn) / dt).toLong(), ((bo - lastBOut) / dt).toLong(),
            ((rt.totalMemory() - rt.freeMemory()) shr 20).toInt(), (rt.maxMemory() shr 20).toInt(),
            ((os?.processCpuLoad ?: 0.0).coerceAtLeast(0.0) * 100).toInt(),
            registry.all.sortedBy { it.info.nodeId }.map { NodeStat(it.info.nodeId, players[it.info.nodeId] ?: 0, it.link.latencyNanos / 1000, it.link.isWritable) },
        )
        lastIn = fi; lastOut = fo; lastBIn = bi; lastBOut = bo
        return s
    }

    private fun publish() { if (registry.all.isEmpty()) { snapshot(); return }; registry.broadcast(snapshot()) }

    override fun close() { exec.shutdownNow() }
}
