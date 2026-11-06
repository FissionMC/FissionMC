package net.fissionmc.backend.relay

import net.fissionmc.protocol.AnnouncementMode
import net.fissionmc.protocol.NodeMessage
import java.util.UUID

/**
 * Join/leave announcements of relayed players: the MASTER announces them network wide (once per login, not per backend switch), so the backend must not
 * broadcast its own vanilla/Paper lines for them. `PlayerListMixin` asks [suppress] where it would broadcast; the Join/Quit events still fire for plugins
 * (their message is simply not broadcast here). With `messages.local.enabled=true` at the master ([AnnouncementMode.local]) nothing is suppressed.
 */
object LocalAnnouncements {
    @Volatile private var local = false

    fun onMessage(m: NodeMessage) { if (m is AnnouncementMode) local = m.local }

    /** True when the vanilla join/quit message of [uuid] must not be broadcast on this backend. */
    @JvmStatic fun suppress(uuid: UUID): Boolean = !local && PlayerDataBackend.isRelayed(uuid)
}
