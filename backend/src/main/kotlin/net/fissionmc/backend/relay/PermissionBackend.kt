package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Backend side of "master admin == operator level 4 on every backend" (admin section).
 *
 * The master sends [PlayerPermission] before [PlayerAttach] and whenever its admin set changes. While a player is granted, the `MinecraftServer.getProfilePermissions`
 * and `PlayerList.isOp` mixins answer for it as an owner (level 4), which is what command sources, Bukkit's `isOp`/`ServerOperator` and the command tree filter
 * use; the backend's ops.json is never written. The other direction: [report] tells the master the level vanilla computes whenever it sends the player its
 * permission level (join, /op, /deop).
 */
object PermissionBackend {
    private val log = Logger.getLogger("FissionMC")
    private val granted = ConcurrentHashMap<UUID, Int>()
    private val byBinding = ConcurrentHashMap<Long, UUID>()

    fun onMessage(m: NodeMessage, server: MinecraftServer?) {
        when (m) {
            is PlayerPermission -> {
                val u = UUID(m.uuidHigh, m.uuidLow); byBinding[m.playerId] = u
                val before = granted[u] ?: 0
                if (m.level >= 4) granted[u] = 4 else granted.remove(u)
                if (before != (granted[u] ?: 0)) server?.execute { server.playerList.getPlayer(u)?.let { server.playerList.sendPlayerPermissionLevel(it) } }   // new level + command tree
            }
            is PlayerDetach -> byBinding.remove(m.playerId)?.let { u -> if (byBinding.values.none { it == u }) granted.remove(u) }
        }
    }

    /** Mixins: the master made this player an owner. */
    @JvmStatic fun isGranted(uuid: UUID): Boolean = (granted[uuid] ?: 0) >= 4

    /** Mixin on the permission level packet: report what vanilla computes (after our override, so a granted admin reports 4). */
    @JvmStatic fun report(uuid: UUID, level: Int) {
        if (!PlayerDataBackend.isRelayed(uuid)) return
        NodeBootstrap.link?.takeIf { it.isOpen }?.send(PlayerPermissionReport(uuid.mostSignificantBits, uuid.leastSignificantBits, level))
    }
}
