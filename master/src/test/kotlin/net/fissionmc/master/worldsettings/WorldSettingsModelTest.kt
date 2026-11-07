package net.fissionmc.master.worldsettings

import io.netty.buffer.Unpooled
import net.fissionmc.protocol.*
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

class WorldSettingsModelTest {
    private fun model(cfg: WorldSettingsConfig = WorldSettingsConfig(), seed: Int = 1) = WorldSettingsModel(cfg, Random(seed))
    private val ow = "clock:minecraft:overworld"

    @Test fun `a clock advances with its rate and stops when paused or when advance_time is off`() {
        val m = model(); assertTrue(m.set(ow, "100,0.0,1.0,false"))
        repeat(10) { m.tick() }; assertEquals("110,0.0,1.0,false", m.get(ow))
        m.set(ow, "110,0.0,0.5,false"); repeat(4) { m.tick() }; assertEquals("112,0.0,0.5,false", m.get(ow))     // half speed: one tick per two
        m.set(ow, "0,0.0,2.0,false"); m.tick(); assertEquals("2,0.0,2.0,false", m.get(ow))
        m.set(ow, "50,0.0,1.0,true"); repeat(5) { m.tick() }; assertEquals("50,0.0,1.0,true", m.get(ow))
        m.set(ow, "50,0.0,1.0,false"); m.set("rule:advance_time", "false"); repeat(5) { m.tick() }; assertEquals("50,0.0,1.0,false", m.get(ow))
        m.set("rule:advance_time", "true"); m.tick(); assertEquals("51,0.0,1.0,false", m.get(ow))
    }

    @Test fun `game time always advances`() {
        val m = model(); m.set("gametime", "1000"); repeat(7) { m.tick() }; assertEquals("1007", m.get("gametime"))
        assertNull(model().get("gametime"), "no game time until a backend reported one")
    }

    @Test fun `weather follows the vanilla cycle with the master's own dice`() {
        val m = model(); m.set("weather", "0,3,0,false,false")
        repeat(2) { m.tick() }; assertEquals("false", m.get("weather")!!.split(',')[3])
        m.tick(); assertTrue(m.weather!!.raining, "the rain timer ran out: it rains")
        m.tick(); val w = m.weather!!; assertTrue(w.rain in 12000..24000, "rain duration ${w.rain}")
        // /weather clear 100: clear time counts down, nothing rains meanwhile
        m.set("weather", "100,5,5,true,true"); m.tick(); val c = m.weather!!; assertFalse(c.raining); assertFalse(c.thundering); assertEquals(99, c.clear)
        m.set("rule:advance_weather", "false"); val before = m.get("weather"); repeat(10) { m.tick() }; assertEquals(before, m.get("weather"))
    }

    @Test fun `master order wins and an invalid or disabled setting changes nothing`() {
        val m = model(WorldSettingsConfig(weather = false))
        assertTrue(m.set("rule:mob_griefing", "false")); assertTrue(m.set("rule:mob_griefing", "true")); assertEquals("true", m.get("rule:mob_griefing"))
        assertFalse(m.set("weather", "0,0,0,false,false")); assertNull(m.get("weather"))
        assertFalse(m.set(ow, "broken")); assertFalse(m.set("something:else", "1"))
        assertFalse(model(WorldSettingsConfig(enabled = false)).set("rule:pvp", "false"))
    }

    @Test fun `the first backend seeds unknown keys and later hellos cannot override`() {
        val m = model()
        assertEquals(setOf("difficulty", "rule:pvp"), m.seed(mapOf("difficulty" to "3,false", "rule:pvp" to "false", "bogus" to "1")).toSet())
        assertTrue(m.seed(mapOf("difficulty" to "1,false", "rule:pvp" to "true")).isEmpty()); assertEquals("3,false", m.get("difficulty"))
    }

    @Test fun `snapshot only carries enabled categories and serialization round trips`() {
        val m = model(); m.set(ow, "5,0.0,1.0,false"); m.set("gametime", "9"); m.set("weather", "1,2,3,true,false"); m.set("rule:pvp", "false"); m.set("spawn", "minecraft:overworld|1|2|3|0.0|0.0"); m.set("border:minecraft:overworld", "0.0,0.0,1000.0,0,0.2,5.0,5,15"); m.set("difficulty", "2,false")
        val snap = m.snapshot(); assertEquals(7, snap.size, snap.toString())
        val copy = model(); copy.load(m.serialize()); assertEquals(snap, copy.snapshot())
        val noTime = model(WorldSettingsConfig(time = false, border = false)); noTime.load(m.serialize())
        assertTrue(noTime.snapshot().keys.none { it.startsWith("clock:") || it == "gametime" || it.startsWith("border:") })
    }

    @Test fun `persistence survives a restart`() {
        val dir = Files.createTempDirectory("ws"); val f = dir.resolve("world-settings.txt")
        val m = model(); m.set(ow, "24000,0.0,1.0,false"); m.set("rule:keep_inventory", "true"); m.save(f)
        val again = model(); again.load(Files.readString(f)); assertEquals("24000,0.0,1.0,false", again.get(ow)); assertEquals("true", again.get("rule:keep_inventory"))
        assertTrue(again.summary().startsWith("day 1 06:00"), again.summary())
    }

    @Test fun `summary shows day clock and weather`() {
        val m = model(); m.set(ow, "13000,0.0,1.0,false"); m.set("weather", "0,100,100,true,true")
        assertEquals("day 0 19:00, thunder", m.summary())
        m.set("rule:advance_time", "false"); assertTrue(m.summary().contains("(stopped)"))
    }

    @Test fun `config categories`() {
        assertEquals(7, WorldSettingsConfig().categories.size); assertTrue(WorldSettingsConfig(enabled = false).categories.isEmpty())
        assertEquals("gamemode", WorldSettingsConfig.categoryOf("gametype")); assertEquals("border", WorldSettingsConfig.categoryOf("border:minecraft:the_nether")); assertNull(WorldSettingsConfig.categoryOf("nope"))
    }

    @Test fun `wire messages round trip`() {
        val reg = MessageRegistry.core().registerWorldSettingsMessages()
        fun rt(m: NodeMessage): NodeMessage { val b = Unpooled.buffer(); reg.encode(m, b); return reg.decode(b).also { assertEquals(0, b.readableBytes()) } }
        val state = mapOf("clock:minecraft:overworld" to "1,0.0,1.0,false", "rule:pvp" to "true")
        assertEquals(WorldSettingsHello(state), rt(WorldSettingsHello(state))); assertEquals(WorldSettingChange("weather", "1,2,3,true,false"), rt(WorldSettingChange("weather", "1,2,3,true,false")))
        assertEquals(WorldSettingsState(99L, "time,weather", state), rt(WorldSettingsState(99L, "time,weather", state))); assertEquals(WorldSettingApply("gametype", "1", "backend-a"), rt(WorldSettingApply("gametype", "1", "backend-a")))
    }
}
