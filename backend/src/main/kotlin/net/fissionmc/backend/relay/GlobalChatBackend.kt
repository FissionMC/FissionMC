package net.fissionmc.backend.relay

import com.mojang.authlib.GameProfile
import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ClientInformation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.logging.Logger

/**
 * Backend side of global chat (B).
 *
 * The master sends [GlobalChat] right before it forwards the player's chat packet (unchanged, so signed chains stay valid). The packet runs
 * through vanilla/Paper as always; when it arrives in `ServerGamePacketListenerImpl.chat(...)` [intercept] recognises the marked text and runs the
 * real Paper chat event path with an EMPTY recipient/viewer set instead of delivering ([io.papermc.paper.adventure.GlobalChatProcessor]).
 * The outcome goes back as [GlobalChatResult]. Messages that never reach `chat(...)` (hidden chat, spam kick, invalid signature) leave a mark that
 * is dropped after [MARK_TTL_MS]; the master drops the message after its own timeout.
 *
 * [GlobalChatObserved] (another backend's global message) fires the same events for a never-spawned ghost player: monitor only.
 */
object GlobalChatBackend {
    private val log = Logger.getLogger("FissionMC")
    private class Mark(val seq: Long, val text: String, val at: Long = System.currentTimeMillis())
    private const val MARK_TTL_MS = 10_000L
    private val marks = ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Mark>>()
    private val uuidOf = ConcurrentHashMap<Long, UUID>()           // relay player id -> uuid
    private val ghosts = ConcurrentHashMap<UUID, ServerPlayer>()
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "fission-global-chat").also { it.isDaemon = true } }
    @Volatile private var server: MinecraftServer? = null

    /** Node link thread: remembers who a relay player id is, queues marks, hands observed messages to a worker. */
    fun onMessage(m: NodeMessage) {
        when (m) {
            is PlayerAttach -> uuidOf[m.playerId] = UUID(m.uuidHigh, m.uuidLow)
            is PlayerDetach -> uuidOf.remove(m.playerId)?.let { marks.remove(it) }
            is GlobalChat -> {
                val u = uuidOf[m.playerId] ?: return
                val q = marks.computeIfAbsent(u) { ConcurrentLinkedDeque() }
                q.addLast(Mark(m.seq, m.text))
                while (q.size > 16) q.pollFirst()
            }
            is GlobalChatObserved -> observed(m)
        }
    }

    fun setServer(s: MinecraftServer) { server = s }

    /**
     * Called first thing in `chat(...)` (async chat thread, or the server thread for plugin-made chat). True = the message was global and is
     * handled here (the caller must not deliver it).
     */
    @JvmStatic fun intercept(player: ServerPlayer, msg: String, original: PlayerChatMessage, async: Boolean): Boolean {
        val q = marks[player.uuid] ?: return false
        val now = System.currentTimeMillis()
        var found: Mark? = null
        for (mk in q) { if (now - mk.at > MARK_TTL_MS) { q.remove(mk); continue }; if (found == null && mk.text.trim() == msg.trim()) found = mk }
        val mark = found ?: return false
        // this mark and every older one are consumed (older ones never arrived at chat(...): the packet was rejected earlier)
        while (true) { val h = q.pollFirst() ?: break; if (h === mark) break }
        val srv = server ?: player.level().server
        val r = io.papermc.paper.adventure.GlobalChatProcessor.run(srv, player, original, async)
        val name = player.gameProfile.name()
        log.info("[fission] global chat event for $name: \"${msg.take(80)}\" cancelled=${r.cancelled()} modified=${r.modified()} (legacy listeners ${r.legacyListeners()}, modern listeners ${r.modernListeners()}), nothing delivered here")
        val display = PlainTextComponentSerializer.plainText().serialize(player.bukkitEntity.displayName())
        val id = uuidOf.entries.firstOrNull { it.value == player.uuid }?.key
        if (id != null) NodeBootstrap.link?.takeIf { it.isOpen }?.send(GlobalChatResult(id, mark.seq, r.cancelled(), r.modified(), display, if (r.modified()) GsonComponentSerializer.gson().serialize(r.message()) else ""))
        return true
    }

    private fun observed(m: GlobalChatObserved) {
        val srv = server ?: return
        val uuid = UUID(m.uuidHigh, m.uuidLow)
        srv.execute {   // entity creation on the server thread; the event path on a worker like real async chat
            val ghost = try { ghosts.computeIfAbsent(uuid) { ServerPlayer(srv, srv.overworld(), GameProfile(uuid, m.name), ClientInformation.createDefault()) } }
                catch (t: Throwable) { log.warning("[fission] observed global chat: cannot create the ghost player for ${m.name}: $t"); return@execute }
            pool.execute {
                try {
                    val r = io.papermc.paper.adventure.GlobalChatProcessor.run(srv, ghost, PlayerChatMessage.unsigned(uuid, m.text), true)
                    log.info("[fission] observed global chat from ${m.name}@${m.fromBackend}: \"${m.text.take(80)}\" (monitor only; legacy listeners ${r.legacyListeners()}, modern listeners ${r.modernListeners()})")
                } catch (t: Throwable) { log.warning("[fission] observed global chat failed: $t") }
            }
        }
    }
}
