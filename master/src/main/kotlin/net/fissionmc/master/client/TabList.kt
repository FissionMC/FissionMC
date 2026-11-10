package net.fissionmc.master.client

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import net.kyori.adventure.text.Component
import java.util.EnumSet
import java.util.UUID

/** What the master knows about one online player's tab list line (merged from the partial updates its backend sends). */
class TabEntry(val uuid: UUID, var name: String, var properties: List<TextureProperty> = emptyList()) {
    var listed = true; var latency = 0; var gameMode: GameMode = GameMode.SURVIVAL; var displayName: Component? = null; var listOrder = 0; var showHat = true

    fun toInfo() = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(UserProfile(uuid, name, properties.toMutableList()), listed, latency, gameMode, displayName, null, listOrder, showHat)

    companion object {
        /** Actions that carry the full entry (remote players are announced with all of them; INITIALIZE_CHAT is never sent: no chat session of a remote player is known to the client). */
        val FULL: EnumSet<Action> = EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED, Action.UPDATE_GAME_MODE, Action.UPDATE_LATENCY, Action.UPDATE_DISPLAY_NAME, Action.UPDATE_LIST_ORDER, Action.UPDATE_HAT)
    }
}

/** A client session as the tab list hub sees it. */
interface TabViewer {
    val tabUuid: UUID
    /** The backend the client is on right now (null: limbo / switching, the hub sends nothing then). */
    val tabNode: String?
    /** Writes a clientbound play frame to the client (called under the hub's lock; must not call back into the hub). */
    fun tabSend(frame: ByteArray)
}

/**
 * The network wide tab list. See `.dev/docs/tablist-ghosts-commands.md` section 1.
 *
 * Every relayed player's own entry (as its backend sends it) is merged into a table. A viewer on backend X is shown the entries of players on other backends as extra lines, and a
 * `PlayerInfoRemove` from X for a player who is still online elsewhere is filtered. [shown] mirrors what each client holds, so an unknown uuid is never updated or removed.
 */
class TabListHub(private val log: (String) -> Unit = {}) {
    private class Member(val viewer: TabViewer, val decode: (ByteArray) -> PacketSendEvent?) { val shown = HashSet<UUID>() }
    private val lock = Any()
    private val members = LinkedHashMap<UUID, Member>()
    private val table = HashMap<UUID, TabEntry>()
    private val notes = ArrayList<Pair<UUID, UUID>>()
    /** Called (outside the hub's lock) when a client got the line of a player it did not hold: (viewer, player). The ghost hub spawns waiting ghosts then. */
    @Volatile var onShown: ((UUID, UUID) -> Unit)? = null

    private fun mark(m: Member, player: UUID) { if (m.shown.add(player)) notes += m.viewer.tabUuid to player }
    private fun <T> locked(f: () -> T): T {
        val out: List<Pair<UUID, UUID>>
        val r = synchronized(lock) { try { f() } finally { out = ArrayList(notes); notes.clear() } }
        val cb = onShown; if (cb != null) out.forEach { (v, p) -> cb(v, p) }
        return r
    }
    /** Does the client of [viewer] hold the tab line of [player]? */
    fun holds(viewer: UUID, player: UUID): Boolean = synchronized(lock) { members[viewer]?.shown?.contains(player) == true }

    val onlineCount: Int get() = synchronized(lock) { table.size }
    fun entry(uuid: UUID): TabEntry? = synchronized(lock) { table[uuid] }
    fun shownTo(viewer: UUID): Set<UUID> = synchronized(lock) { members[viewer]?.shown?.toSet() ?: emptySet() }

    fun register(viewer: TabViewer, decode: (ByteArray) -> PacketSendEvent?) { locked { members[viewer.tabUuid] = Member(viewer, decode) } }

    /** The player left the network: its line goes away for everybody who holds it. */
    fun unregister(viewer: TabViewer) {
        locked {
            val u = viewer.tabUuid
            if (members[u]?.viewer !== viewer) return@locked
            members.remove(u); table.remove(u)
            net.fissionmc.net.trace.Trace.emit("tab.unregister") { str("uuid", u.toString()); int("holders", members.values.count { it.shown.contains(u) }) }
            val frame = Limbo.encode(WrapperPlayServerPlayerInfoRemove(listOf(u)))
            for (m in members.values) if (m.shown.remove(u)) m.viewer.tabSend(frame)
        }
    }

    /** The player is connected but on no backend any more (backend lost, limbo): its line goes away for the others, the viewer itself stays registered. */
    fun hidden(viewer: TabViewer) {
        locked {
            val u = viewer.tabUuid
            if (table.remove(u) == null) return@locked
            net.fissionmc.net.trace.Trace.emit("tab.hidden") { str("uuid", u.toString()) }
            val frame = Limbo.encode(WrapperPlayServerPlayerInfoRemove(listOf(u)))
            for (m in members.values) if (m.viewer !== viewer && m.shown.remove(u)) m.viewer.tabSend(frame)
        }
    }

    /**
     * A PlayerInfoUpdate frame the viewer's backend sent. Records the viewer's own entry and returns the frame (always forwarded: native entries are the backend's business).
     */
    fun update(viewer: TabViewer, frame: ByteArray): ByteArray = locked {
        val m = members[viewer.tabUuid] ?: return@locked frame
        try {
            val w = WrapperPlayServerPlayerInfoUpdate(m.decode(frame) ?: return@locked frame)
            if (Action.ADD_PLAYER in w.actions) w.entries.forEach { mark(m, it.profileId) }
            val own = w.entries.firstOrNull { it.profileId == viewer.tabUuid } ?: return@locked frame
            val existing = table[viewer.tabUuid]
            if (existing == null && Action.ADD_PLAYER !in w.actions) return@locked frame   // an update for a line the hub has not seen added (cannot rebuild a name)
            val e = existing ?: TabEntry(viewer.tabUuid, own.gameProfile.name ?: viewer.tabUuid.toString().take(16))
            var changed = existing == null
            for (a in w.actions) when (a) {
                Action.ADD_PLAYER -> { e.name = own.gameProfile.name ?: e.name; e.properties = own.gameProfile.textureProperties.toList(); changed = true }
                Action.UPDATE_LISTED -> { changed = changed || e.listed != own.isListed; e.listed = own.isListed }
                Action.UPDATE_LATENCY -> { changed = changed || e.latency != own.latency; e.latency = own.latency }
                Action.UPDATE_GAME_MODE -> { changed = changed || e.gameMode != own.gameMode; e.gameMode = own.gameMode }
                Action.UPDATE_DISPLAY_NAME -> { changed = changed || e.displayName != own.displayName; e.displayName = own.displayName }
                Action.UPDATE_LIST_ORDER -> { changed = changed || e.listOrder != own.listOrder; e.listOrder = own.listOrder }
                Action.UPDATE_HAT -> { changed = changed || e.showHat != own.isShowHat; e.showHat = own.isShowHat }
                else -> {}
            }
            table[viewer.tabUuid] = e
            net.fissionmc.net.trace.Trace.emit("tab.update") { str("uuid", viewer.tabUuid.toString()); str("name", e.name); strs("actions", w.actions.map { it.name }); bool("changed", changed); bool("listed", e.listed); int("latency", e.latency) }
            if (changed) push(viewer, e, w.actions)
        } catch (t: Throwable) { log("[tab] cannot merge a PlayerInfoUpdate of ${viewer.tabUuid}: $t") }
        frame
    }

    /** A PlayerInfoRemove frame from the viewer's backend: lines of players who are still online on ANOTHER backend stay; null = nothing left to send. */
    fun remove(viewer: TabViewer, frame: ByteArray): ByteArray? = locked {
        val m = members[viewer.tabUuid] ?: return@locked frame
        try {
            val w = WrapperPlayServerPlayerInfoRemove(m.decode(frame) ?: return@locked frame)
            val node = viewer.tabNode
            val keep = w.profileIds.filter { u ->
                val o = members[u]
                val stays = o != null && o.viewer.tabNode != null && o.viewer.tabNode != node
                !stays && u in m.shown
            }
            m.shown.removeAll(keep.toSet())
            return@locked when {
                keep.size == w.profileIds.size -> frame
                keep.isEmpty() -> null
                else -> Limbo.encode(WrapperPlayServerPlayerInfoRemove(keep))
            }
        } catch (t: Throwable) { log("[tab] cannot filter a PlayerInfoRemove of ${viewer.tabUuid}: $t") }
        frame
    }

    /** The viewer's client dropped its tab list (cleanup before a switch) or is about to be filled (first arrival): forget what it holds. */
    fun reset(viewer: TabViewer) { locked { members[viewer.tabUuid]?.shown?.clear() } }

    /** The viewer's join stream is in (after a Login / transplant): add the lines of all players on other backends. */
    fun placed(viewer: TabViewer) {
        locked {
            val m = members[viewer.tabUuid] ?: return@locked
            val node = viewer.tabNode ?: return@locked
            val remote = members.values.filter { it.viewer !== viewer && it.viewer.tabNode != null && it.viewer.tabNode != node }.mapNotNull { table[it.viewer.tabUuid] }
            if (remote.isEmpty()) return@locked
            remote.forEach { mark(m, it.uuid) }
            m.viewer.tabSend(Limbo.encode(WrapperPlayServerPlayerInfoUpdate(TabEntry.FULL, remote.map { it.toInfo() })))
        }
    }

    /** Sends the viewers on other backends than [owner]'s the new state of the owner's line. */
    private fun push(owner: TabViewer, e: TabEntry, actions: Set<Action>) {
        val node = owner.tabNode ?: return
        val adding = Action.ADD_PLAYER in actions
        val frame = Limbo.encode(WrapperPlayServerPlayerInfoUpdate(if (adding) TabEntry.FULL else EnumSet.copyOf(actions.filter { it != Action.ADD_PLAYER && it != Action.INITIALIZE_CHAT }.ifEmpty { return }), listOf(e.toInfo())))
        for (m in members.values) {
            val n = m.viewer.tabNode ?: continue
            if (m.viewer === owner || n == node) continue
            if (adding) mark(m, e.uuid) else if (e.uuid !in m.shown) continue
            m.viewer.tabSend(frame)
        }
    }
}
