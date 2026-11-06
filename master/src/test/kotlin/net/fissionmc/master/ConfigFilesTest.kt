package net.fissionmc.master

import net.fissionmc.master.client.ClientConfig
import net.fissionmc.net.ConfigException
import net.fissionmc.net.FissionConfig
import net.fissionmc.net.NodeConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** fission.yml: the committed defaults and the user's converted dev files parse into the typed settings without errors; unknown keys and wrong types fail with the key path. */
class ConfigFilesTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    private fun master(yaml: String) = MasterConfig.read(FissionConfig.parse(yaml), Path.of("/tmp/x"))

    @Test fun `the committed master default parses and has the documented defaults`() {
        val text = Files.readString(repo.resolve("master/src/main/resources/fission.default.yml"))
        val c = MasterConfig.read(FissionConfig.parse(text), repo.resolve("run-master"))
        assertTrue(c.node.masterProcess); assertEquals(25600, c.node.listen!!.port); assertEquals(25565, c.client.port)
        assertFalse(c.regions.enabled); assertEquals("minimessage", c.settings.chat.syntax); assertEquals(24, c.settings.petRadius)
        assertTrue(c.chunkAuthority); assertEquals(60L, c.leaseSeconds); assertEquals("chunkstore", c.chunkStoreDir)
    }

    @Test fun `the commented examples of the default parse once uncommented`() {
        var text = Files.readString(repo.resolve("master/src/main/resources/fission.default.yml"))
        // uncomment the regions and debug examples and the two chat templates
        text = text.lines().joinToString("\n") { l -> if (l.startsWith("# ") && (l.startsWith("#   ") || l.startsWith("# regions:") || l.startsWith("# debug:"))) l.removePrefix("# ") else l }
        val c = MasterConfig.read(FissionConfig.parse(text), repo.resolve("run-master"))
        assertTrue(c.regions.enabled); assertEquals("backend-b", c.regions.owner("minecraft:overworld", 5, 0)); assertEquals("backend-a", c.regions.owner("minecraft:the_nether", 5, 0))
        assertEquals("/tmp/fission-dump", c.debugDumpClientStream)
        val t = Files.readString(repo.resolve("master/src/main/resources/fission.default.yml")).lines().filter { it.trim().startsWith("# format: \"") }.map { it.trim().removePrefix("# ") }
        assertEquals(2, t.size)
        val y = "chat:\n  global:\n    ${t[0]}\n  local:\n    ${t[1]}\n"
        val s = net.fissionmc.master.client.MasterSettings.from(FissionConfig.parse(y))
        assertTrue(s.chat.format.startsWith("<#b0d8d8>Global")); assertTrue(s.chat.localFormat.startsWith("<#b0d8d8>{serverid}"))
    }

    @Test fun `the committed backend default parses`() {
        val root = FissionConfig.parse(Files.readString(repo.resolve("backend/src/main/resources/fission.backend.default.yml")))
        val n = NodeConfig.from(root, repo.resolve("run-a")); ClientConfig.from(root); root.finish()
        assertEquals(net.fissionmc.net.NodeMode.STANDALONE, n.mode)
    }

    @Test fun `the converted dev files of the user parse when present`() {
        val m = repo.resolve("run-master/fission.yml")
        if (Files.isRegularFile(m)) { val c = MasterConfig.read(FissionConfig.load(m), m.parent); assertTrue(c.node.masterProcess) } else println("[config-test] no run-master/fission.yml: skipped")
        for (n in listOf("run-a", "run-b")) {
            val f = repo.resolve("$n/fission.yml")
            if (!Files.isRegularFile(f)) { println("[config-test] no $n/fission.yml: skipped"); continue }
            val root = FissionConfig.load(f); val c = NodeConfig.from(root, f.parent); ClientConfig.from(root); root.finish()
            assertEquals(net.fissionmc.net.NodeMode.BACKEND, c.mode); assertEquals("backend-${n.last()}", c.nodeId)
        }
    }

    @Test fun `an unknown key stops with its path`() {
        val e = assertFailsWith<ConfigException> { master("node: { mode: master, listen: \"127.0.0.1:1\" }\nchat:\n  global: { formt: x }\nbogus: 1") }
        assertTrue(e.message!!.contains("chat.global.formt") && e.message!!.contains("bogus"), e.message)
        // the old flat properties style is not understood
        val flat = assertFailsWith<ConfigException> { master("node: { mode: master, listen: \"127.0.0.1:1\" }\nchat.default: global") }
        assertTrue(flat.message!!.contains("chat.default"), flat.message)
    }

    @Test fun `a wrong type stops with its path`() {
        fun fails(yaml: String, vararg parts: String) { val e = assertFailsWith<ConfigException> { master("node: { mode: master, listen: \"127.0.0.1:1\" }\n$yaml") }; parts.forEach { assertTrue(e.message!!.contains(it), "${e.message} should contain $it") } }
        fails("chat:\n  global: { max-length: lots }", "chat.global.max-length", "whole number")
        fails("chat:\n  global: { allow-colors: maybe }", "chat.global.allow-colors", "true or false")
        fails("chat: { default: sideways }", "chat.default")
        fails("chat: { format: { syntax: html } }", "chat.format.syntax")
        fails("client: { listen: nonsense }", "client.listen", "host:port")
        fails("chunk: { authority: 3 }", "chunk.authority")
        fails("travel: { pet-radius: 999 }", "travel.pet-radius")
        fails("admin: { players: { a: b } }", "admin.players")
        fails("regions: { dimensions: { overworld: [ 5 ] } }", "regions.dimensions.overworld[0]")
        fails("messages: [1, 2]", "messages", "section")
    }

    @Test fun `a broken yaml file and a wrong top level are reported`() {
        assertFailsWith<ConfigException> { FissionConfig.parse("node: [unclosed") }
        assertFailsWith<ConfigException> { FissionConfig.parse("- just\n- a list") }
        assertEquals(emptyList(), FissionConfig.parse("").unknownKeys())
    }

    @Test fun `an invalid region map still names the place and defaults apply to a minimal file`() {
        val e = assertFailsWith<IllegalArgumentException> { master("node: { mode: master, listen: \"127.0.0.1:1\" }\nregions:\n  dimensions:\n    overworld:\n      - { area: [min, min, 0, max], owner: a }") }
        assertTrue(e.message!!.contains("invalid regions") && e.message!!.contains("nobody owns"), e.message)
        val c = master("node: { mode: master, listen: \"127.0.0.1:1\" }")
        assertEquals(8, c.settings.regionMargin); assertEquals(12, c.ghostZone); assertEquals(1, c.borderStrip); assertEquals(25565, c.client.port)
    }

    @Test fun `hex colours and templates need quotes and survive them`() {
        val s = net.fissionmc.master.client.MasterSettings.from(FissionConfig.parse("chat:\n  global:\n    format: \"&#b0d8d8Global &#759e9e| &r&#949393{name}: {message}\"\n    prefix: '\\<x>'\n"))
        assertEquals("&#b0d8d8Global &#759e9e| &r&#949393{name}: {message}", s.chat.format); assertEquals("\\<x>", s.chat.prefix)
        // unquoted, a ` #` starts a comment and the value is cut
        val cut = net.fissionmc.master.client.MasterSettings.from(FissionConfig.parse("chat:\n  global:\n    format: &#b0d8d8Global\n".replace("&#b0d8d8Global", "Global #b0d8d8 rest")))
        assertEquals("Global", cut.chat.format)
    }
}
