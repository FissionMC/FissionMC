package net.fissionmc.master

import net.fissionmc.master.client.ClientConfig
import net.fissionmc.net.ConfigException
import net.fissionmc.net.FissionConfig
import kotlin.test.*

class PlayerCountsTest {
    private fun cfg(vararg lines: String) = ClientConfig.from(FissionConfig.parse("client:\n" + lines.joinToString("\n") { "  $it" }))

    @Test fun `by default the list shows the real numbers and the cap holds`() {
        val c = cfg()
        assertEquals(5, c.shownOnline(5)); assertEquals(100, c.shownMax(5)); assertTrue(c.hasRoom(99)); assertFalse(c.hasRoom(100))
    }

    @Test fun `unlimited lets everybody in and shows one free slot`() {
        for (v in listOf("unlimited", "-1", "infinite")) { val c = cfg("max-players: $v"); assertTrue(c.hasRoom(1_000_000)); assertEquals(8, c.shownMax(7)) }
    }

    @Test fun `fake numbers only change what the list shows`() {
        val c = cfg("max-players: unlimited", "fake-max-players: 1", "fake-online-players: 0")
        assertEquals(0, c.shownOnline(40)); assertEquals(1, c.shownMax(40)); assertTrue(c.hasRoom(40))
        val m = cfg("fake-online-minimum: 12")
        assertEquals(12, m.shownOnline(3)); assertEquals(30, m.shownOnline(30)); assertEquals(100, m.shownMax(3))
        assertEquals(2, cfg("fake-online-minimum: 12", "fake-online-players: 2").shownOnline(50), "a fixed number wins over the minimum")
    }

    @Test fun `bad values are rejected with the key`() {
        assertFailsWith<ConfigException> { cfg("max-players: lots") }
        assertFailsWith<ConfigException> { cfg("max-players: -5") }
        assertFailsWith<ConfigException> { cfg("fake-max-players: -2") }
        assertFailsWith<ConfigException> { cfg("fake-online-minimum: -1") }
    }
}
