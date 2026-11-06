package net.fissionmc.master.client

import net.fissionmc.master.PlayerRelay
import net.fissionmc.protocol.GlobalChat
import net.fissionmc.protocol.GlobalChatObserved
import net.fissionmc.protocol.GlobalChatResult
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Global chat: the sender's backend runs the chat events (nothing delivered there), the master formats the outcome and sends it to every
 * client that is playing on a backend as an UNSIGNED system chat line.
 */
class GlobalChatHub(private val server: ClientServer, private val relay: PlayerRelay, private val log: (String) -> Unit = {}) {
    private class Pending(val session: ClientSession, val bindingId: Long, val text: String, val node: String)
    private val seq = AtomicLong()
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val settings get() = server.settings.chat
    val pendingCount: Int get() = pending.size

    /** Called on the sender's loop right BEFORE its chat packet is forwarded to the backend. */
    fun onGlobalMessage(session: ClientSession, bindingId: Long, rawText: String) {
        val node = relay.nodeIdOf(bindingId) ?: return
        val text = ChatFormatter.sanitize(rawText, settings.maxLength)
        if (text.isEmpty()) return
        val s = seq.incrementAndGet()
        pending[s] = Pending(session, bindingId, text, node)
        relay.sendTo(bindingId, GlobalChat(bindingId, s, text))
        session.schedule(settings.timeoutMs) {
            if (pending.remove(s) != null) log("[chat] no chat event result from $node for ${session.playerName}'s message within ${settings.timeoutMs} ms: dropped (muted, chat hidden or rejected by the backend)")
        }
    }

    /** The sender's backend ran the events. */
    fun onResult(r: GlobalChatResult) {
        val p = pending.remove(r.seq) ?: return
        if (p.bindingId != r.playerId) return
        if (r.cancelled) { log("[chat] ${p.session.playerName}: cancelled by a plugin on ${p.node}, not broadcast"); return }
        val message: Component = if (r.modified && r.messageJson.isNotEmpty()) GsonComponentSerializer.gson().deserialize(r.messageJson)
            else ChatFormatter.messageComponent(p.text, settings.allowColors)
        val line = ChatFormatter.format(settings, p.session.playerName, r.displayName.ifEmpty { p.session.playerName }, p.node, message)
        val plain = ChatFormatter.plain(line)
        log("[chat] [global] $plain")
        server.allSessions().forEach { it.tell(line) }
        val uuid = p.session.uuid ?: return
        relay.broadcastExcept(p.node, GlobalChatObserved(uuid.mostSignificantBits, uuid.leastSignificantBits, p.session.playerName, p.node,
            ChatFormatter.plain(message), GsonComponentSerializer.gson().serialize(message)))
    }
}
