package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.ItemGive
import net.fissionmc.protocol.ItemPickup
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import java.util.UUID
import java.util.logging.Logger

/** A player of another backend touches an item this backend owns: the owner removes the item and the player's backend gives it to the player. */
object ItemPickupBackend {
    private val log = Logger.getLogger("FissionMC")

    fun onPickup(m: ItemPickup, server: MinecraftServer?) {
        server?.execute {
            val id = UUID(m.entityHigh, m.entityLow)
            for (level in server.allLevels) {
                val item = level.getEntity(id) as? ItemEntity ?: continue
                if (item.isRemoved || item.hasPickUpDelay() || !RegionsBackend.ownsEntity(item)) return@execute
                val bytes = ItemTransferBackend.encodeStack(level, item.item) ?: return@execute
                item.discard()
                NodeBootstrap.link?.takeIf { it.isOpen }?.send(ItemGive(m.playerBackend, m.playerHigh, m.playerLow, bytes))
                return@execute
            }
        }
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
}
