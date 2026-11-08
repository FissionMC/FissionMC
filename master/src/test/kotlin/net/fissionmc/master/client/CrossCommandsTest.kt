package net.fissionmc.master.client

import net.fissionmc.net.ConfigException
import net.fissionmc.net.FissionConfig
import net.fissionmc.protocol.*
import io.netty.buffer.Unpooled
import kotlin.test.*

class CrossCommandsTest {
    private val online = mapOf("bob" to ("Bob" to "b"), "carl" to ("Carl" to "a"))
    private val lookup = CrossCommands.Lookup { online[it.lowercase()] }
    private fun cc(vararg cmds: String) = CrossCommands(CrossCommandSettings(true, cmds.toList()))

    @Test fun `tp to a player on another backend matches`() {
        val m = assertNotNull(cc("tp", "teleport").match("tp bob", "a", lookup))
        assertEquals("Bob", m.targetName); assertEquals("b", m.targetNode)
        assertNotNull(cc("tp", "teleport").match("teleport BOB", "a", lookup))
        assertNotNull(cc("tp").match("minecraft:tp Bob", "a", lookup)); assertNotNull(cc("tp").match("/tp   Bob ", "a", lookup))
    }

    @Test fun `same backend, offline, unlisted commands, extra arguments and selectors do not match`() {
        assertNull(cc("tp").match("tp carl", "a", lookup), "carl is on the sender's backend")
        assertNull(cc("tp").match("tp nobody", "a", lookup))
        assertNull(cc("tp").match("msg bob", "a", lookup))
        assertNull(cc("tp").match("tp bob alice", "a", lookup), "moves Bob, not the sender")
        assertNull(cc("tp").match("tp bob 1 2 3", "a", lookup))
        assertNull(cc("tp").match("tp @p", "a", lookup)); assertNull(cc("tp").match("tp", "a", lookup)); assertNull(cc("tp").match("tp bob", null, lookup))
        assertNull(CrossCommands(CrossCommandSettings(false)).match("tp bob", "a", lookup))
    }

    @Test fun `an argument index picks the target position`() {
        val c = cc("tpask@1")
        assertNotNull(c.match("tpask here bob", "a", lookup)); assertNull(c.match("tpask bob", "a", lookup))
    }

    @Test fun `settings read the cross-commands and ghosts sections and refuse nonsense`() {
        val s = MasterSettings.from(FissionConfig.parse("cross-commands:\n  commands: [tp, \"home@0\"]\n  check-timeout-ms: 500\nghosts:\n  range: 64\n  enabled: false\n"))
        assertEquals(listOf("tp", "home@0"), s.cross.commands); assertEquals(500, s.cross.checkTimeoutMs); assertEquals(64, s.ghosts.range); assertFalse(s.ghosts.enabled)
        val d = MasterSettings.from(FissionConfig.parse("")); assertEquals(listOf("tp", "teleport"), d.cross.commands); assertTrue(d.cross.enabled); assertEquals(128, d.ghosts.range)
        assertFailsWith<ConfigException> { MasterSettings.from(FissionConfig.parse("cross-commands:\n  commands: [\"tp@x\"]\n")) }
        assertFailsWith<ConfigException> { MasterSettings.from(FissionConfig.parse("ghosts:\n  range: 5\n")) }
    }

    @Test fun `ghost and command messages survive the wire`() {
        val reg = MessageRegistry.core().registerGhostMessages()
        val msgs = listOf(GhostPose(1, 2, "minecraft:overworld", 1.5, 64.0, -3.25, 90f, -10f, 91f, true), GhostSwing(1, 2, 1),
            CommandCheck(5, 9, "tp Bob"), CommandCheckResult(5, 9, true))
        for (m in msgs) { val b = Unpooled.buffer(); reg.encode(m, b); assertEquals(m, reg.decode(b)); assertEquals(0, b.readableBytes()) }
        val meta = GhostMeta(1, 2, byteArrayOf(1, 2, 3), ByteArray(0)); val b = Unpooled.buffer(); reg.encode(meta, b)
        val d = reg.decode(b) as GhostMeta; assertContentEquals(meta.entityData, d.entityData); assertEquals(0, d.equipment.size)
    }
}
