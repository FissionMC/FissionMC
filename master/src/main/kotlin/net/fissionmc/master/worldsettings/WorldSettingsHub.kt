package net.fissionmc.master.worldsettings

import net.fissionmc.master.NodeRegistry
import net.fissionmc.master.RegisteredNode
import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.WorldSettingApply
import net.fissionmc.protocol.WorldSettingChange
import net.fissionmc.protocol.WorldSettingsHello
import net.fissionmc.protocol.WorldSettingsState
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The master side of the world settings sync: holds the canonical [WorldSettingsModel] (persisted next to the player data), ticks it at 20 tps,
 * answers a backend's hello with the state, applies the changes backends report IN ARRIVAL ORDER (the master's order wins) and forwards each to the other backends, and
 * broadcasts the full state every `sync-interval-ticks`.
 */
class WorldSettingsHub(private val registry: NodeRegistry, val model: WorldSettingsModel, private val fileOrNull: Path?, private val log: (String) -> Unit = {}) : Closeable {
    private val file: Path? = fileOrNull
    val changesApplied = AtomicLong(); val rejected = AtomicLong(); val seeded = AtomicLong(); val broadcasts = AtomicLong()
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fission-world-settings").also { it.isDaemon = true } }
    private var lastSave = System.nanoTime()

    init {
        fileOrNull?.let { f -> if (Files.isRegularFile(f)) runCatching { synchronized(model) { model.load(Files.readString(f)) }; log("[world-settings] loaded ${model.snapshot().size} settings from $f (${model.summary()})") }.onFailure { log("[world-settings] could not read $f: $it") } }
        exec.scheduleAtFixedRate({ runCatching { tick() }.onFailure { log("[world-settings] tick failed: $it") } }, 50, 50, TimeUnit.MILLISECONDS)
        log("[world-settings] categories: ${model.cfg.categories.joinToString(",").ifEmpty { "none (disabled)" }}, state broadcast every ${model.cfg.syncIntervalTicks} ticks")
    }

    private fun tick() {
        val state: WorldSettingsState?
        synchronized(model) {
            model.tick()
            state = if (model.ticks % model.cfg.syncIntervalTicks == 0L) stateMessage() else null
            if (model.dirty && file != null && System.nanoTime() - lastSave > 5_000_000_000L) { lastSave = System.nanoTime(); runCatching { model.save(file!!) }.onFailure { log("[world-settings] save failed: $it") } }
        }
        if (state != null) { registry.broadcast(state); broadcasts.incrementAndGet() }
    }

    private fun stateMessage() = WorldSettingsState(model.ticks, model.cfg.categories.joinToString(","), model.snapshot())

    /** Backend messages (called from the link's thread). */
    fun onMessage(node: RegisteredNode, msg: NodeMessage) {
        val id = node.info.nodeId
        when (msg) {
            is WorldSettingsHello -> {
                val reply: WorldSettingsState
                synchronized(model) {
                    val adopted = model.seed(msg.state); seeded.addAndGet(adopted.size.toLong())
                    if (adopted.isNotEmpty()) log("[world-settings] $id seeded ${adopted.size} settings (${adopted.take(5).joinToString()}${if (adopted.size > 5) ", ..." else ""})")
                    reply = stateMessage()
                }
                if (node.link.isOpen) node.link.send(reply)
            }
            is WorldSettingChange -> {
                val forward: WorldSettingApply?
                synchronized(model) {
                    forward = if (model.set(msg.key, msg.value)) WorldSettingApply(msg.key, model.get(msg.key)!!, id) else null
                }
                if (forward == null) { rejected.incrementAndGet(); return }
                changesApplied.incrementAndGet()
                log("[world-settings] $id changed ${msg.key} -> ${msg.value.take(80)}")
                registry.broadcast(forward, id)
            }
            else -> {}
        }
    }

    /** One line for logs/status. */
    fun summary(): String = synchronized(model) { model.summary() }

    fun saveNow() { val f = file ?: return; synchronized(model) { runCatching { model.save(f) } } }
    override fun close() { exec.shutdownNow(); saveNow() }
}
