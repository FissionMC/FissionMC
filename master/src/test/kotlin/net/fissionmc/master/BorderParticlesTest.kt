package net.fissionmc.master

import io.netty.buffer.Unpooled
import net.fissionmc.master.region.BorderParticleSettings
import net.fissionmc.master.region.BorderParticles
import net.fissionmc.master.region.RegionMap
import net.fissionmc.master.seamless.PlayPacketIds
import kotlin.test.*

class BorderParticlesTest {
    init { net.fissionmc.packets.FissionPacketEventsAPI.install(net.fissionmc.packets.Side.MASTER) }
    private fun cfg(yaml: String) = net.fissionmc.net.FissionConfig.parse(yaml)
    private val ow = "minecraft:overworld"
    private fun map(vararg rules: String) = RegionMap.parse(cfg("regions:\n  dimensions:\n    overworld:\n" + rules.joinToString("\n") { "      $it" }))
    private val split = map("- { area: [min, min, 0, max], owner: a }", "- { area: [0, min, max, max], owner: b }")
    private val on = BorderParticleSettings(enabled = true)

    @Test fun `two regions give one vertical edge on the shared line`() {
        assertEquals(listOf(RegionMap.Edge(true, 0, Int.MIN_VALUE, Int.MAX_VALUE)), split.edges(ow))
        assertTrue(RegionMap.OFF.edges(ow).isEmpty()); assertTrue(split.edges("minecraft:the_nether").isEmpty())
    }

    @Test fun `neighbours with the same owner make no edge and an L shape makes three`() {
        val same = map("- { area: [min, min, 0, max], owner: a }", "- { area: [0, min, 32, max], owner: a }", "- { area: [32, min, max, max], owner: b }")
        assertEquals(listOf(RegionMap.Edge(true, 32, Int.MIN_VALUE, Int.MAX_VALUE)), same.edges(ow))
        val l = map("- { area: [min, min, 0, max], owner: a }", "- { area: [0, min, max, 0], owner: a }", "- { area: [0, 0, max, max], owner: b }")
        assertEquals(setOf(RegionMap.Edge(true, 0, 0, Int.MAX_VALUE), RegionMap.Edge(false, 0, 0, Int.MAX_VALUE)), l.edges(ow).toSet())
    }

    @Test fun `particles lie exactly on the border line and within the distance`() {
        val pts = BorderParticles.sample(split.edges(ow), 5.3, 64.7, 100.0, on)
        assertTrue(pts.isNotEmpty())
        for (p in pts) { assertEquals(0.0, p[0]); assertTrue(Math.hypot(p[0] - 5.3, p[2] - 100.0) <= on.distance + 1e-9); assertTrue(p[1] >= 61.0 && p[1] <= 69.0) }
        assertEquals(pts.size, pts.map { Triple(it[0], it[1], it[2]) }.toSet().size)
    }

    @Test fun `nothing far from a border and the cap holds`() {
        assertTrue(BorderParticles.sample(split.edges(ow), 40.0, 64.0, 0.0, on).isEmpty())
        assertEquals(10, BorderParticles.sample(split.edges(ow), 0.5, 64.0, 0.0, on.copy(maxPerRefresh = 10)).size)
    }

    @Test fun `a corner is drawn once and positions are stable between refreshes`() {
        val l = map("- { area: [min, min, 0, max], owner: a }", "- { area: [0, min, max, 0], owner: a }", "- { area: [0, 0, max, max], owner: b }")
        val pts = BorderParticles.sample(l.edges(ow), 1.0, 64.0, 1.0, on)
        assertEquals(pts.size, pts.map { Triple(it[0], it[1], it[2]) }.toSet().size)
        assertTrue(pts.any { it[0] == 0.0 && it[2] == 0.0 })
        val again = BorderParticles.sample(l.edges(ow), 1.0, 64.0, 1.0, on)
        assertEquals(pts.map { it.toList() }, again.map { it.toList() })
    }

    @Test fun `the frame is a level particles packet with the position and the flags`() {
        val f = BorderParticles.frame(doubleArrayOf(0.0, 64.0, 7.0), on)
        val b = Unpooled.wrappedBuffer(f)
        fun varInt(): Int { var v = 0; var s = 0; while (true) { val x = b.readByte().toInt(); v = v or ((x and 0x7F) shl s); if (x and 0x80 == 0) return v; s += 7 } }
        assertEquals(PlayPacketIds.c("LevelParticles"), varInt())
        varInt()
        assertTrue(b.readBoolean()); assertTrue(b.readBoolean())
        assertEquals(0.0, b.readDouble()); assertEquals(64.0, b.readDouble()); assertEquals(7.0, b.readDouble())
        repeat(6) { assertEquals(0f, b.readFloat()) }
        assertEquals(0, varInt()); assertEquals(0, varInt()); assertEquals(0, b.readableBytes())
    }

    @Test fun `the particle setting is read from a client information payload`() {
        fun payload(status: Int): ByteArray { val o = java.io.ByteArrayOutputStream(); val loc = "en_us".toByteArray(); o.write(loc.size); o.write(loc); o.write(12); o.write(0); o.write(1); o.write(0x7F); o.write(1); o.write(0); o.write(1); o.write(status); return o.toByteArray() }
        assertEquals(0, BorderParticles.particleStatus(payload(0))); assertEquals(2, BorderParticles.particleStatus(payload(2))); assertNull(BorderParticles.particleStatus(ByteArray(3)))
    }

    @Test fun `settings parse with defaults and reject bad values`() {
        assertFalse(BorderParticleSettings.from(cfg("")).enabled)
        val s = BorderParticleSettings.from(cfg("regions:\n  border-particles:\n    enabled: true\n    particle: dust\n    color: \"#ff0000\"\n    distance: 20"))
        assertTrue(s.enabled); assertEquals(0xFF0000, s.color); assertEquals(20, s.distance)
        assertFailsWith<net.fissionmc.net.ConfigException> { BorderParticleSettings.from(cfg("regions:\n  border-particles:\n    particle: lava")) }
        assertFailsWith<net.fissionmc.net.ConfigException> { BorderParticleSettings.from(cfg("regions:\n  border-particles:\n    distance: 1")) }
    }
}
