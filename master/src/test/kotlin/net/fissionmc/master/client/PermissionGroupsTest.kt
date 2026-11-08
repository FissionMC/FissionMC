package net.fissionmc.master.client

import io.netty.buffer.Unpooled
import net.fissionmc.net.ConfigException
import net.fissionmc.net.FissionConfig
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class PermissionGroupsTest {
    private val yaml = """
        permissions:
          groups:
            fission.admin: 4
            fission.moderator: 2
            fission.trainee: 0
          players:
            R4T_2: fission.admin, fission.moderator, fission.trainee
            Mod: [fission.moderator, fission.trainee]
            Tr: fission.trainee
            Typo: fission.admn, fission.trainee
            "00000000-0000-0000-0000-000000000007": fission.moderator
          bind-names: true
    """.trimIndent()

    private fun store(text: String, logs: MutableList<String> = ArrayList(), dir: java.nio.file.Path = Files.createTempDirectory("perm")): PermissionStore =
        PermissionStore.from(FissionConfig.parse(text), dir.resolve("permissions.txt"), dir.resolve("admins.txt")) { logs += it }

    @Test fun `groups, comma text and yaml list, level is the highest, level 0 still grants the node`() {
        val logs = ArrayList<String>(); val s = store(yaml, logs)
        val r4t = UUID(1, 1); val mod = UUID(2, 2); val tr = UUID(3, 3)
        s.bindOnLogin("r4t_2", r4t); s.bindOnLogin("Mod", mod); s.bindOnLogin("Tr", tr)
        assertEquals(listOf("fission.admin", "fission.moderator", "fission.trainee"), s.nodesOf(r4t).toList()); assertEquals(4, s.levelOf(r4t)); assertTrue(s.isAdmin(r4t))
        assertEquals(setOf("fission.moderator", "fission.trainee"), s.nodesOf(mod)); assertEquals(2, s.levelOf(mod)); assertFalse(s.isAdmin(mod), "level 2 gets no master commands")
        assertEquals(setOf("fission.trainee"), s.nodesOf(tr)); assertEquals(0, s.levelOf(tr)); assertTrue(s.has(tr, "fission.trainee"), "level 0 still grants the node")
        assertEquals(2, s.levelOf(UUID(0, 7)), "uuid key"); assertEquals(0, s.levelOf(UUID(8, 8))); assertTrue(s.nodesOf(null).isEmpty())
    }

    @Test fun `an unknown group warns in the log and is skipped`() {
        val logs = ArrayList<String>(); val s = store(yaml, logs); val u = UUID(4, 4)
        assertTrue(logs.any { it.startsWith("WARN") && it.contains("permissions.players.Typo") && it.contains("fission.admn") }, logs.toString())
        s.bindOnLogin("typo", u); assertEquals(setOf("fission.trainee"), s.nodesOf(u)); assertEquals(0, s.levelOf(u))
    }

    @Test fun `no groups section defaults to fission admin level 4`() {
        val s = store("permissions:\n  players:\n    Bob: fission.admin"); s.bindOnLogin("Bob", UUID(1, 2)); assertTrue(s.isAdmin(UUID(1, 2)))
        assertEquals(mapOf("fission.admin" to 4), s.groups)
    }

    @Test fun `the legacy admin section counts as fission admin with a warning`() {
        val logs = ArrayList<String>(); val u = UUID(5, 6)
        val s = store("admin:\n  players: [Alice, \"$u\"]\n  bind-names: true", logs)
        assertTrue(s.isAdmin(u)); assertEquals(4, s.levelOf(u)); assertTrue(logs.count { it.contains("admin: section is deprecated") } == 1, logs.toString())
        s.bindOnLogin("alice", UUID(7, 7)); assertTrue(s.isAdmin(UUID(7, 7)))
        // an empty legacy section (the old default file) is silent and valid
        val quiet = ArrayList<String>(); store("admin:\n  players: []\n  bind-names: true", quiet); assertTrue(quiet.isEmpty(), quiet.toString())
        // a legacy admin list next to groups that lack fission.admin adds it at level 4
        assertTrue(store("permissions:\n  groups: { fission.moderator: 2 }\nadmin: { players: [Zed] }").groups["fission.admin"] == 4)
        assertFailsWith<ConfigException> { store("admin: { players: { a: b } }") }
    }

    @Test fun `a legacy admins txt is migrated once to permissions txt as fission admin`() {
        val dir = Files.createTempDirectory("perm"); val u = UUID(9, 1)
        Files.write(dir.resolve("admins.txt"), listOf("# master admins", "$u Old"))
        val logs = ArrayList<String>(); val s = store("", logs, dir)
        assertTrue(s.isAdmin(u)); assertTrue(logs.any { it.contains("migrated") }); assertTrue(Files.isRegularFile(dir.resolve("permissions.txt")))
        val logs2 = ArrayList<String>(); val again = store("", logs2, dir)
        assertTrue(again.isAdmin(u)); assertTrue(logs2.none { it.contains("migrated") }, "only once")
        assertEquals(u, again.uuidByName("old"))
    }

    @Test fun `bound names keep taking their nodes from the yaml`() {
        val dir = Files.createTempDirectory("perm"); val u = UUID(2, 9)
        store(yaml, dir = dir).bindOnLogin("Mod", u)
        assertEquals(setOf("fission.moderator", "fission.trainee"), store(yaml, dir = dir).nodesOf(u))
        assertTrue(store("permissions: { groups: { fission.moderator: 2 } }", dir = dir).nodesOf(u).isEmpty(), "the name entry is gone from the yaml: nothing granted")
    }

    @Test fun `bind-names false ignores names`() {
        val s = store("permissions:\n  bind-names: false\n  players: { Bob: fission.admin }"); assertFalse(s.bindOnLogin("Bob", UUID(1, 1))); assertFalse(s.isAdmin(UUID(1, 1)))
    }

    @Test fun `the permission message carries level and nodes over the wire`() {
        val reg = MessageRegistry.core().registerPlayerMessages()
        for (m in listOf(PlayerPermission(7, 1L, -2L, 4, listOf("fission.admin", "fission.trainee")), PlayerPermission(8, 3L, 4L, 0), PlayerPermission(9, 5L, 6L, 0, listOf("fission.trainee")))) {
            val b = Unpooled.buffer(); reg.encode(m, b); assertEquals(m, reg.decode(b)); assertEquals(0, b.readableBytes())
        }
    }
}
