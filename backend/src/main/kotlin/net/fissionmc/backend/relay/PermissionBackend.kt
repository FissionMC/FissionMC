package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.server.MinecraftServer
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Backend side of the master's permission groups (`permissions:` section of the master's fission.yml).
 *
 * The master sends [PlayerPermission] before [PlayerAttach] and whenever its permissions change. It carries an operator level (0-4, the highest among the
 * player's groups) and the group nodes. While a player is granted a level, `MinecraftServer.getProfilePermissions` answers at least that level for it (the higher
 * of the master's and the backend's own wins; this is what command sources and the command tree use) and, for level 4 only, `PlayerList.isOp` /
 * Bukkit's `isOp()` answer true; ops.json is never written. The nodes are set as real Bukkit permissions through one [PermissionAttachment] per player
 * (so `hasPermission` and permission plugins see them, level 0 groups included), applied when the player joins and replaced when the master sends a change.
 * The other direction: [report] tells the master the level vanilla computes whenever it sends the player its permission level (join, /op, /deop).
 */
object PermissionBackend {
    private val log = Logger.getLogger("FissionMC")
    private class Grant(val level: Int, val nodes: Set<String>)
    private val granted = ConcurrentHashMap<UUID, Grant>()
    private val attachments = ConcurrentHashMap<UUID, PermissionAttachment>()
    private val byBinding = ConcurrentHashMap<Long, UUID>()

    /** Attachments need an enabled owning plugin: this is a minimal stand-in that only answers what the permission code asks (name, isEnabled). */
    private val owner: Plugin by lazy {
        java.lang.reflect.Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, m, _ ->
            when (m.name) { "isEnabled" -> true; "getName" -> "FissionMC"; "toString" -> "FissionMC"; "hashCode" -> 1; "equals" -> false
                else -> when (m.returnType) { java.lang.Boolean.TYPE -> false; Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null } }
        } as Plugin
    }

    fun onMessage(m: NodeMessage, server: MinecraftServer?) {
        when (m) {
            is PlayerPermission -> {
                val u = UUID(m.uuidHigh, m.uuidLow); byBinding[m.playerId] = u
                val before = granted[u]?.level ?: 0
                val grant = Grant(m.level.coerceIn(0, 4), m.nodes.toSet())
                net.fissionmc.net.trace.Trace.emit("permission.grant") { player(null, u); int("level", grant.level); int("before", before); strs("nodes", grant.nodes) }
                if (grant.level == 0 && grant.nodes.isEmpty()) granted.remove(u) else granted[u] = grant
                server?.execute {
                    val p = server.playerList.getPlayer(u) ?: return@execute
                    applyNodes(u, p.bukkitEntity)
                    if (before != grant.level) server.playerList.sendPlayerPermissionLevel(p)   // new level + command tree
                }
            }
            is PlayerDetach -> byBinding.remove(m.playerId)?.let { u -> if (byBinding.values.none { it == u }) { granted.remove(u); attachments.remove(u) } }
        }
    }

    /** Mixins: the operator level the master grants this player (0 = none). */
    @JvmStatic fun level(uuid: UUID): Int = granted[uuid]?.level ?: 0

    /** Mixin on `isOp`: only full operators (level 4) are ops for plugins; lower levels act through the level based permission set and their nodes. */
    @JvmStatic fun isGranted(uuid: UUID): Boolean = level(uuid) >= 4

    /** Mixin in `placeNewPlayer` (before the join event) and on every change: makes the player's Bukkit permissions match the master's nodes. Server thread. */
    @JvmStatic fun applyNodes(uuid: UUID, player: Player?) {
        player ?: return
        val old = attachments.remove(uuid)
        val nodes = granted[uuid]?.nodes ?: emptySet()
        if (old != null) try { player.removeAttachment(old) } catch (_: IllegalArgumentException) {}
        if (nodes.isEmpty()) return
        try {
            val att = player.addAttachment(owner)
            for (n in nodes) att.setPermission(n, true)
            attachments[uuid] = att
        } catch (t: Throwable) { log.warning("[fission] cannot grant the master's permission nodes to ${player.name}: $t") }
    }

    /** Mixin on the permission level packet: report what vanilla computes (after our override, so a granted admin reports 4). */
    @JvmStatic fun report(uuid: UUID, level: Int) {
        if (!PlayerDataBackend.isRelayed(uuid)) return
        net.fissionmc.net.trace.Trace.emit("permission.report") { player(null, uuid); int("level", level) }
        NodeBootstrap.link?.takeIf { it.isOpen }?.send(PlayerPermissionReport(uuid.mostSignificantBits, uuid.leastSignificantBits, level))
    }
}
