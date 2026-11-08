package net.fissionmc.master.client

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.util.EventCreationUtil
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.PacketTap
import net.fissionmc.packets.Side
import net.kyori.adventure.text.Component
import java.util.EnumSet
import java.util.UUID
import kotlin.test.*

class TabListHubTest {
    init { FissionPacketEventsAPI.install(Side.MASTER) }

    private val tap = PacketTap.create(EmbeddedChannel(), UUID(0, 1), "T", start = ConnectionState.PLAY)!!
    private val decode: (ByteArray) -> PacketSendEvent? = { f -> EventCreationUtil.createSendEvent(EmbeddedChannel(), tap.user, null, Unpooled.wrappedBuffer(f), false) }

    private class Viewer(val name: String, override var tabNode: String?) : TabViewer {
        override val tabUuid: UUID = UUID.nameUUIDFromBytes(name.toByteArray())
        val sent = ArrayList<ByteArray>()
        override fun tabSend(frame: ByteArray) { sent += frame }
    }

    private fun add(v: Viewer, gm: GameMode = GameMode.SURVIVAL, latency: Int = 5, display: Component? = null) = Limbo.encode(WrapperPlayServerPlayerInfoUpdate(TabEntry.FULL,
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo(UserProfile(v.tabUuid, v.name, mutableListOf(TextureProperty("textures", "skin-${v.name}", "sig"))), true, latency, gm, display, null, 0, true)))
    private fun latency(v: Viewer, ms: Int) = Limbo.encode(WrapperPlayServerPlayerInfoUpdate(Action.UPDATE_LATENCY, WrapperPlayServerPlayerInfoUpdate.PlayerInfo(UserProfile(v.tabUuid, null)).also { it.latency = ms }))
    private fun remove(vararg u: UUID) = Limbo.encode(WrapperPlayServerPlayerInfoRemove(u.toList()))
    private fun parseUpdate(f: ByteArray) = WrapperPlayServerPlayerInfoUpdate(decode(f)!!)
    private fun parseRemove(f: ByteArray) = WrapperPlayServerPlayerInfoRemove(decode(f)!!)

    private fun hub() = TabListHub().also { }
    private fun join(h: TabListHub, v: Viewer) { h.register(v, decode); h.update(v, add(v)) }

    @Test fun `a player on another backend appears for a new joiner with skin game mode latency and display name`() {
        val h = hub(); val a = Viewer("Alice", "a"); val b = Viewer("Bob", "b")
        h.register(a, decode); h.update(a, add(a, GameMode.CREATIVE, 42, Component.text("Ali")))
        join(h, b); h.placed(b)
        val f = b.sent.single(); val w = parseUpdate(f)
        assertEquals(TabEntry.FULL, w.actions)
        val e = w.entries.single()
        assertEquals(a.tabUuid, e.profileId); assertEquals("Alice", e.gameProfile.name)
        assertEquals("skin-Alice", e.gameProfile.textureProperties.single().value)
        assertEquals(GameMode.CREATIVE, e.gameMode); assertEquals(42, e.latency); assertEquals(Component.text("Ali"), e.displayName)
        assertEquals(setOf(a.tabUuid), h.shownTo(b.tabUuid) - b.tabUuid)
    }

    @Test fun `players on the same backend are left to the backend`() {
        val h = hub(); val a = Viewer("Alice", "a"); val a2 = Viewer("Amy", "a")
        join(h, a); join(h, a2); h.placed(a2)
        assertTrue(a.sent.isEmpty()); assertTrue(a2.sent.isEmpty())
    }

    @Test fun `a later join or a change reaches the viewers of other backends only`() {
        val h = hub(); val a = Viewer("Alice", "a"); val a2 = Viewer("Amy", "a"); val b = Viewer("Bob", "b")
        join(h, a); join(h, a2); join(h, b); h.placed(b)
        assertEquals(2, parseUpdate(b.sent.single()).entries.size)   // a and a2 when b is placed
        b.sent.clear(); a.sent.clear(); a2.sent.clear()
        h.update(a, latency(a, 99))
        assertTrue(a.sent.isEmpty() && a2.sent.isEmpty())
        val w = parseUpdate(b.sent.single()); assertEquals(EnumSet.of(Action.UPDATE_LATENCY), w.actions); assertEquals(99, w.entries.single().latency)
        b.sent.clear()
        val c = Viewer("Cid", "c"); join(h, c)
        assertEquals(1, b.sent.size); assertTrue(Action.ADD_PLAYER in parseUpdate(b.sent.single()).actions)   // Cid joins on c: Bob (b) sees it
        assertTrue(a.sent.size == 1)
    }

    @Test fun `a backend remove for a player who moved to another backend is filtered, a real leave removes everywhere`() {
        val h = hub(); val a = Viewer("Alice", "a"); val a2 = Viewer("Amy", "a"); val b = Viewer("Bob", "b")
        join(h, a); join(h, a2); join(h, b)
        h.update(a, add(a2)); h.update(a2, add(a)); h.placed(b)    // backend a lists both of them to each other; Bob is shown both remotely
        // Amy moves from backend a to backend b: her session now reports node b, backend a sends Remove(Amy) to Alice
        a2.tabNode = "b"
        assertNull(h.remove(a, remove(a2.tabUuid)), "the only removed uuid is still online elsewhere")
        assertTrue(a2.tabUuid in h.shownTo(a.tabUuid))
        // Alice leaves the network: everybody who holds her gets a remove, and the backend's own remove afterwards is dropped
        a.sent.clear(); b.sent.clear(); a2.sent.clear()
        h.unregister(a)
        assertEquals(listOf(a.tabUuid), parseRemove(b.sent.single()).profileIds)
        assertEquals(listOf(a.tabUuid), parseRemove(a2.sent.single()).profileIds)
        assertNull(h.remove(b, remove(a.tabUuid)), "b does not hold Alice any more")
    }

    @Test fun `a mixed remove keeps the players who are still online and passes the rest`() {
        val h = hub(); val a = Viewer("Alice", "a"); val a2 = Viewer("Amy", "a"); val b = Viewer("Bob", "b")
        join(h, a); join(h, a2); join(h, b); h.placed(a)
        val npc = UUID(7, 7)
        h.update(a, Limbo.encode(WrapperPlayServerPlayerInfoUpdate(Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.PlayerInfo(UserProfile(npc, "Npc")))))
        assertTrue(npc in h.shownTo(a.tabUuid))
        val out = h.remove(a, remove(b.tabUuid, npc))        // b is on another backend: its line stays; the npc line is a native entry and goes
        assertEquals(listOf(npc), parseRemove(assertNotNull(out)).profileIds)
    }

    @Test fun `a player who goes to the limbo disappears for the others and comes back with the next placement`() {
        val h = hub(); val a = Viewer("Alice", "a"); val b = Viewer("Bob", "b")
        join(h, a); join(h, b); h.placed(b); b.sent.clear()
        a.tabNode = null; h.hidden(a)
        assertEquals(listOf(a.tabUuid), parseRemove(b.sent.single()).profileIds)
        assertEquals(null, h.entry(a.tabUuid))
    }

    @Test fun `shown notifications reach the ghost hub outside the lock`() {
        val h = hub(); val a = Viewer("Alice", "a"); val b = Viewer("Bob", "b")
        val seen = ArrayList<Pair<UUID, UUID>>(); h.onShown = { v, p -> seen += v to p }
        join(h, a); join(h, b); h.placed(b)
        assertTrue((b.tabUuid to a.tabUuid) in seen)
        assertTrue(h.holds(b.tabUuid, a.tabUuid))
    }
}
