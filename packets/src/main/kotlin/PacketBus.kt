package net.fissionmc.packets

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent

/**
 * The in-core packet API: PacketEvents' own event system (priorities, cancel, modify, per-user state via `event.user`)
 * fed by the core pipelines. Plugins and core register the same [PacketListenerAbstract] subclasses they would with PacketEvents.
 */
object PacketBus {
    private val registered = java.util.concurrent.atomic.AtomicInteger()
    /** True when someone besides PacketEvents' internal listener registered: pipelines skip all decoding when false. */
    val hasListeners: Boolean get() = registered.get() > 0

    fun register(l: PacketListenerCommon): PacketListenerCommon { registered.incrementAndGet(); return PacketEvents.getAPI().eventManager.registerListener(l) }
    fun unregister(l: PacketListenerCommon) { registered.updateAndGet { maxOf(0, it - 1) }; PacketEvents.getAPI().eventManager.unregisterListener(l) }

    /** Runs the listener chain for a packet received from a client; false if a listener cancelled it. */
    fun fireReceive(e: PacketReceiveEvent): Boolean { PacketEvents.getAPI().eventManager.callEvent(e); return !e.isCancelled }
    /** Runs the listener chain for a packet about to be sent to a client; false if cancelled. */
    fun fireSend(e: PacketSendEvent): Boolean { PacketEvents.getAPI().eventManager.callEvent(e); return !e.isCancelled }
}
