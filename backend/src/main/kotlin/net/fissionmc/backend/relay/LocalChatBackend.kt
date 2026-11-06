package net.fissionmc.backend.relay

import io.papermc.paper.chat.ChatRenderer
import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.craft.chat.LocalChatHook
import net.fissionmc.master.chat.ChatTemplate
import net.fissionmc.protocol.ChatFormat
import net.kyori.adventure.text.Component
import java.util.logging.Logger

/**
 * Local chat format: the master pushes `chat.local.format` + syntax ([ChatFormat], wire 82); this backend renders local chat lines
 * itself with the same [ChatTemplate] the master uses for global chat, as the DEFAULT renderer of Paper's chat event (plugins can still change the message or set their
 * own renderer). An empty template restores Paper's default rendering.
 */
object LocalChatBackend {
    private val log = Logger.getLogger("FissionMC")
    @Volatile private var current: ChatFormat? = null

    fun onMessage(m: ChatFormat) {
        if (m == current) return
        current = m
        if (m.local.isEmpty()) { LocalChatHook.renderer = null; log.info("[fission] local chat: vanilla rendering"); return }
        LocalChatHook.renderer = ChatRenderer.viewerUnaware { source, displayName, message ->
            try { ChatTemplate.render(m.local, m.syntax, source.name, displayName, source.uniqueId, NodeBootstrap.nodeId, message) }
            catch (t: Throwable) { log.warning("[fission] local chat format failed (${t}), using the vanilla line"); Component.translatable("chat.type.text", displayName, message) }
        }
        log.info("[fission] local chat format (${m.syntax}): ${m.local}")
    }
}
