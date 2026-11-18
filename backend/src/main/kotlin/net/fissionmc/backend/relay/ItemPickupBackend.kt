package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.ItemGive
import net.fissionmc.protocol.ItemGiveAck
import net.fissionmc.protocol.ItemGiveTracked
import net.fissionmc.protocol.ItemPickup
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** A player of another backend touches an item this backend owns: the owner removes the item and the player's backend gives it to the player. */
object ItemPickupBackend {
    private val log = Logger.getLogger("FissionMC")

    /** An item taken from the world whose give the player's backend has not acknowledged yet; it is put back when the ack says no or does not come in [ACK_TIMEOUT_MS]. */
    private class Pending(val level: ServerLevel, val bytes: ByteArray, val x: Double, val y: Double, val z: Double, val deadline: Long)
    private val pending = ConcurrentHashMap<UUID, Pending>()
    /** Items put back after a timeout: entity id -> (new item entity, expiry). A late "accepted" ack removes the copy again so nothing is duplicated. */
    private class Restored(val level: ServerLevel, val item: UUID, val until: Long)
    private val restored = ConcurrentHashMap<UUID, Restored>()
    const val ACK_TIMEOUT_MS = 20_000L
    @JvmField val timedOut = java.util.concurrent.atomic.AtomicLong()
    @JvmField val refused = java.util.concurrent.atomic.AtomicLong()
    @JvmField val lateAcks = java.util.concurrent.atomic.AtomicLong()

    fun onPickup(m: ItemPickup, server: MinecraftServer?) {
        server?.execute {
            val id = UUID(m.entityHigh, m.entityLow)
            for (level in server.allLevels) {
                val item = level.getEntity(id) as? ItemEntity ?: continue
                if (item.isRemoved || item.hasPickUpDelay() || !RegionsBackend.ownsEntity(item)) return@execute
                if (pending.containsKey(id)) return@execute   // a give for this item is already on its way
                val bytes = ItemTransferBackend.encodeStack(level, item.item) ?: return@execute
                val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return@execute   // no link: the item stays where it is instead of vanishing
                pending[id] = Pending(level, bytes, item.x, item.y, item.z, System.currentTimeMillis() + ACK_TIMEOUT_MS)
                item.discard()
                link.send(ItemGiveTracked(m.playerBackend, RegionsBackend.youId(), m.entityHigh, m.entityLow, m.playerHigh, m.playerLow, bytes))
                return@execute
            }
        }
    }

    /** The player's backend (or the master, on a refusal) answered a give of ours. */
    fun onAck(m: ItemGiveAck, server: MinecraftServer?) {
        server?.execute {
            val id = UUID(m.entityHigh, m.entityLow)
            val p = pending.remove(id)
            if (p != null) {
                if (!m.accepted) { refused.incrementAndGet(); log.warning("[fission] a picked up item was refused by the player's backend: it is put back"); putBack(p) }
                return@execute
            }
            val r = restored.remove(id)
            if (r != null && m.accepted) {
                lateAcks.incrementAndGet()
                (r.level.getEntity(r.item) as? ItemEntity)?.takeIf { !it.isRemoved }?.discard()
                log.warning("[fission] a give was acknowledged after the item had been put back: the copy was removed again")
            }
        }
    }

    /** Server thread, every tick: puts back items whose give was not acknowledged in time. */
    fun tick() {
        if (pending.isEmpty() && restored.isEmpty()) return
        val now = System.currentTimeMillis()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now < e.value.deadline) continue
            it.remove(); timedOut.incrementAndGet()
            log.warning("[fission] no acknowledgement for a picked up item after ${ACK_TIMEOUT_MS / 1000} s: it is put back")
            putBack(e.value, e.key)
        }
        restored.values.removeIf { now > it.until }
    }

    private fun putBack(p: Pending, id: UUID? = null) {
        val stack = ItemTransferBackend.decodeStack(p.level, p.bytes)
        if (stack.isEmpty) return
        val e = ItemEntity(p.level, p.x, p.y, p.z, stack)
        e.setDefaultPickUpDelay()
        p.level.addFreshEntity(e)
        if (id != null) restored[id] = Restored(p.level, e.uuid, System.currentTimeMillis() + 120_000)
    }

    fun onGive(m: ItemGive, server: MinecraftServer?) {
        server?.execute {
            val player = server.playerList.getPlayer(UUID(m.playerHigh, m.playerLow))
            if (player == null) { log.warning("[fission] a picked up item arrived for a player that is not here"); return@execute }
            val stack = ItemTransferBackend.decodeStack(player.level() as net.minecraft.server.level.ServerLevel, m.stack)
            if (stack.isEmpty) return@execute
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, 1.0f)
            if (!player.inventory.add(stack) && !stack.isEmpty) player.level().addFreshEntity(ItemEntity(player.level(), player.x, player.y + 0.5, player.z, stack))
        }
    }

    /** A tracked give: the same as [onGive], then the owner is told whether the player really got the stack (through the master). */
    fun onGiveTracked(m: ItemGiveTracked, server: MinecraftServer?) {
        server?.execute {
            val ok = give(server, UUID(m.playerHigh, m.playerLow), m.stack)
            NodeBootstrap.link?.takeIf { it.isOpen }?.send(ItemGiveAck(m.origin, m.entityHigh, m.entityLow, ok))
        }
    }

    private fun give(server: MinecraftServer, id: UUID, bytes: ByteArray): Boolean {
        val player = server.playerList.getPlayer(id)
        if (player == null) { log.warning("[fission] a picked up item arrived for a player that is not here"); return false }
        val level = player.level() as ServerLevel
        val stack = ItemTransferBackend.decodeStack(level, bytes)
        if (stack.isEmpty) return false   // unreadable: refused, the owner keeps its copy
        level.playSound(null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, 1.0f)
        if (!player.inventory.add(stack) && !stack.isEmpty) level.addFreshEntity(ItemEntity(level, player.x, player.y + 0.5, player.z, stack))
        return true
    }
}
