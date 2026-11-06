package net.fissionmc.master

import net.fissionmc.master.chat.ChatTemplate
import net.fissionmc.master.chat.ChatVectors
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.test.*

class ChatTemplateTest {
    @Test fun `the shared vectors pass on this adventure line`() {
        val f = ChatVectors.failures()
        assertTrue(f.isEmpty(), f.joinToString("\n"))
        assertTrue(ChatVectors.rows().size >= 15)
    }

    private fun render(t: String, msg: String, allow: Boolean = false, display: String = "Alex", syntax: String = "minimessage") =
        ChatTemplate.render(t, syntax, "Alex", Component.text(display), ChatVectors.uuid, "backend-a", ChatTemplate.messageComponent(msg, allow))

    @Test fun `the users two templates give a head object and the right colours`() {
        val d = ChatTemplate.describe(render("<#b0d8d8>Global <#759e9e>| &r<head:{uuid}> <#949393>{message}", "hello"))
        assertTrue(d.startsWith("Global {#B0D8D8}|"), d)
        assertTrue(d.contains("head(35deb110-752a-3928-95c4-cc89250ce08e,true){-}"), d)   // &r reset the colour before the head
        assertTrue(d.endsWith("hello{#949393}"), d)
        val l = ChatTemplate.describe(render("<#b0d8d8>{serverid} <#759e9e>| &r<head:{uuid}> <#949393>{message}", "hello"))
        assertTrue(l.startsWith("backend-a {#B0D8D8}|"), l)
    }

    private fun plain(c: Component) = net.fissionmc.master.client.ChatFormatter.plain(c)
    private fun events(c: Component): Boolean = c.clickEvent() != null || c.hoverEvent() != null || c.children().any { events(it) }

    @Test fun `player text can never inject tags or codes or placeholders`() {
        val c = render("{name}: {message}", "<red>x</red><click:run_command:/op me>y</click> &c{name}")
        assertEquals("Alex: <red>x</red><click:run_command:/op me>y</click> &c{name}", plain(c))
        assertFalse(events(c))
        fun colours(x: Component): List<Any?> = listOf(x.color()) + x.children().flatMap { colours(it) }
        assertTrue(colours(c).all { it == null })
        // a hostile display name stays text as well
        assertEquals("<b>x</b>&c: m", plain(render("{displayname}: {message}", "m", display = "<b>x</b>&c")))
        assertFalse(events(render("{displayname}", "m", display = "<click:run_command:/op me>x")))
    }

    @Test fun `colours allowed means ampersand codes only`() {
        val on = ChatTemplate.describe(render("{message}", "&chello <blue>x", allow = true))
        assertEquals("hello <blue>x{#FF5555}", on)
    }

    @Test fun `legacy to minimessage conversion`() {
        assertEquals("<reset><red>x<#ff00aa><bold>", ChatTemplate.legacyToMini("&r&cx&#ff00aa&l"))
        assertEquals("A & B &z &#12", ChatTemplate.legacyToMini("A & B &z &#12"))
        assertEquals("<reset>", ChatTemplate.legacyToMini("&R"))
    }

    @Test fun `the head object serialises through packetevents for the 26_3 client`() {
        net.fissionmc.packets.FissionPacketEventsAPI.install(net.fissionmc.packets.Side.MASTER)
        val c = render("<head:{uuid}>{message}", "x")
        val nbt = com.github.retrooper.packetevents.util.adventure.AdventureSerializer.toNbt(c)
        val s = nbt.toString()
        assertTrue(s.contains("player") || s.contains("object"), s)
        assertTrue(s.contains("903786768"), s)   // 0x35deb110: the uuid as the int array vanilla's ResolvableProfile codec reads
        println("[chat] head as nbt: $s")
    }
}
