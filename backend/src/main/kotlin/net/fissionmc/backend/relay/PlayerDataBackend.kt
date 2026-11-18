package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.server.MinecraftServer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Backend side of the master-owned player data.
 *
 * The master sends [PlayerData] before [PlayerAttach]; [provided] hands those bytes to the `PlayerDataStorage.load` mixin so the player is created
 * from the master's copy instead of this backend's local file. Every save of a relayed player (autosave, disconnect, shutdown, flush) is reported
 * back by [onSaved] with the bytes of the file vanilla just wrote.
 */
object PlayerDataBackend {
    private val log = Logger.getLogger("FissionMC")
    private val provided = ConcurrentHashMap<UUID, ByteArray>()
    private val byBinding = ConcurrentHashMap<Long, UUID>()
    /** uuid -> time until which saves are still reported (the disconnect save happens after the master's detach). */
    private val relayedUntil = ConcurrentHashMap<UUID, Long>()
    private const val LINGER_MS = 15_000L
    private val savePace = net.fissionmc.net.Pacer(SAVE_EVERY_MS)
    /** Periodic save of relayed players, in addition to vanilla's autosave (which is every 5 minutes): a crash loses at most this much. */
    private const val SAVE_EVERY_MS = 30_000L   // wall clock: 600 ticks at 20 tps, but a server at 1 tps must not wait 10 minutes

    /** Node link thread. */
    fun onMessage(m: NodeMessage, server: MinecraftServer?) {
        when (m) {
            is PlayerData -> {
                val u = UUID(m.uuidHigh, m.uuidLow); byBinding[m.playerId] = u; relayedUntil[u] = Long.MAX_VALUE
                if (m.present) provided[u] = m.nbt else provided.remove(u)
            }
            is PlayerAttach -> { val u = UUID(m.uuidHigh, m.uuidLow); byBinding[m.playerId] = u; relayedUntil[u] = Long.MAX_VALUE }
            is PlayerDetach -> byBinding.remove(m.playerId)?.let { u ->
                if (byBinding.values.none { it == u }) { provided.remove(u); relayedUntil[u] = System.currentTimeMillis() + LINGER_MS }
            }
            is PlayerDataFlush -> {
                val u = byBinding[m.playerId] ?: return
                server?.execute {
                    TravelBackend.collectAndSend(server, u, m.petRadius)   // pets and riders first, then the player file, stats and advancements
                    savePlayer(server, u)
                    NodeBootstrap.link?.takeIf { it.isOpen }?.send(PlayerFlushed(u.mostSignificantBits, u.leastSignificantBits))
                }
            }
        }
    }

    /** `PlayerDataStorage.load` mixin: the master's bytes for this player, or null (use the local file). */
    @JvmStatic fun provided(uuid: UUID): ByteArray? = provided[uuid]

    fun isRelayed(uuid: UUID): Boolean = (relayedUntil[uuid] ?: return false) > System.currentTimeMillis()

    /** `PlayerDataStorage.save` mixin (RETURN): [file] is the `<uuid>.dat` that was just written. */
    @JvmStatic fun onSaved(uuid: UUID, file: File) {
        if (!isRelayed(uuid)) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: run { unsent[uuid] = file; return }   // the link is down (the player was closed with it): sent by [onReconnect]
        unsent.remove(uuid)
        try {
            val bytes = file.readBytes()
            if (bytes.size > MAX_PLAYER_DATA) { log.warning("[fission] player data of $uuid is ${bytes.size} bytes: not sent to the master"); return }
            link.send(PlayerDataSave(uuid.mostSignificantBits, uuid.leastSignificantBits, bytes))
        } catch (t: Throwable) { log.warning("[fission] cannot report the player data of $uuid: $t") }
    }

    /** Player files saved while the master link was down. */
    private val unsent = ConcurrentHashMap<UUID, File>()

    /** A new master link: the saves the master missed go out first (it accepts them unless the player has meanwhile played on another backend). */
    fun onReconnect(link: net.fissionmc.net.NodeLink) {
        for ((u, f) in unsent.entries.toList()) {
            unsent.remove(u)
            try {
                val bytes = f.readBytes()
                if (bytes.size <= MAX_PLAYER_DATA) { link.send(PlayerDataSave(u.mostSignificantBits, u.leastSignificantBits, bytes)); log.info("[fission] sent the player data of $u that was saved while the master link was down") }
            } catch (t: Throwable) { log.warning("[fission] cannot send the saved player data of $u: $t") }
        }
    }

    /** The first message of a NEW master link: every binding of the old link is void (the master dropped them when the link broke). Their closing saves are still reported for a while. */
    fun linkReset() {
        val until = System.currentTimeMillis() + LINGER_MS
        byBinding.clear(); provided.clear()
        for (k in relayedUntil.keys) relayedUntil[k] = until
    }

    private val saveMethod by lazy {
        net.minecraft.server.players.PlayerList::class.java.getDeclaredMethod("save", net.minecraft.server.level.ServerPlayer::class.java).also { it.isAccessible = true }
    }

    /** Server thread: vanilla's own save (player file, stats, advancements); the storage mixin then reports it. */
    private fun savePlayer(server: MinecraftServer, uuid: UUID) {
        val p = server.playerList.getPlayer(uuid) ?: return
        try { saveMethod.invoke(server.playerList, p) } catch (t: Throwable) { log.warning("[fission] save of ${p.gameProfile.name()} failed: $t") }
    }

    /** Server thread, every tick. */
    fun tick(server: MinecraftServer) {
        if (!savePace.due()) return
        val now = System.currentTimeMillis()
        relayedUntil.entries.removeIf { it.value < now }
        for (u in byBinding.values.toSet()) savePlayer(server, u)
    }
}
