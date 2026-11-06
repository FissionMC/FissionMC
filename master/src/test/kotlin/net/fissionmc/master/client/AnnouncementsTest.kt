package net.fissionmc.master.client

import io.netty.buffer.Unpooled
import net.fissionmc.protocol.*
import net.fissionmc.net.FissionConfig
import kotlin.test.*

class AnnouncementsTest {
    private fun plain(c: net.kyori.adventure.text.Component?) = c?.let { ChatFormatter.plain(it) }
    private fun parse(yaml: String = "") = MasterSettings.from(FissionConfig.parse(yaml))

    @Test fun `default is the master announcing with vanilla translatable lines`() {
        val a = parse().announcements
        assertEquals("multiplayer.player.joined", plain(a.join("Steve", "backend-a")))
        assertEquals("multiplayer.player.left", plain(a.leave("Steve", "backend-a")))
        assertNull(a.switched("Steve", "backend-b"), "switch lines are off by default")
        assertFalse(a.local)
    }

    @Test fun `each announcement can be switched off`() {
        val a = parse("messages: { join: { global: false } }").announcements
        assertNull(a.join("S", null)); assertNotNull(a.leave("S", null))
        val b = parse("messages: { leave: { global: false } }").announcements
        assertNotNull(b.join("S", null)); assertNull(b.leave("S", null))
    }

    @Test fun `custom formats use name and backend placeholders and colour codes`() {
        val a = parse("messages:\n  join: { format: \"&e{name} joined via {backend}\" }\n  leave: { format: \"{name} left\" }\n  switch: { enabled: true }").announcements
        assertEquals("Steve joined via backend-a", plain(a.join("Steve", "backend-a")))
        assertEquals("Steve left", plain(a.leave("Steve", "backend-a")))
        assertEquals("Steve moved to backend-b", plain(a.switched("Steve", "backend-b")))
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.YELLOW, a.join("Steve", "x")!!.children().firstOrNull()?.color() ?: a.join("Steve", "x")!!.color())
    }

    @Test fun `local mode hands everything to the backends and the master stays silent`() {
        val a = parse("messages: { local: { enabled: true }, switch: { enabled: true } }").announcements
        assertTrue(a.local); assertNull(a.join("S", "b")); assertNull(a.leave("S", "b")); assertNull(a.switched("S", "b"))
    }

    @Test fun `player names cannot inject format codes into the line`() {
        val a = parse("messages: { join: { format: \"{name} arrived\" } }").announcements
        assertEquals("&cEvil arrived", plain(a.join("&cEvil", "b")), "the name is plain text, not parsed")
    }

    @Test fun `the announcement mode survives the wire`() {
        val reg = MessageRegistry.core().registerPlayerMessages()
        for (v in listOf(true, false)) { val b = Unpooled.buffer(); reg.encode(AnnouncementMode(v), b); assertEquals(AnnouncementMode(v), reg.decode(b)); assertEquals(0, b.readableBytes()) }
    }
}
