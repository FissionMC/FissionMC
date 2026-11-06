package net.fissionmc.net

import java.nio.file.Files
import kotlin.test.*

class NodeIdsTest {
    private val dir = Files.createTempDirectory("nodeids")
    private fun write(text: String) = dir.resolve("fission.yml").also { Files.writeString(it, text) }

    @Test fun `ids look like backend plus six hex characters and differ`() {
        val ids = (1..50).map { NodeIds.random() }.toSet()
        assertTrue(ids.size > 45); ids.forEach { assertTrue(Regex("backend-[0-9a-f]{6}").matches(it), it) }
    }

    @Test fun `a hand written id wins and the file is not touched`() {
        val f = write("# mine\nnode:\n  mode: backend\n  id: backend-a\n")
        val before = Files.readString(f)
        val r = NodeIds.resolve(f, "backend-a")
        assertEquals("backend-a", r.id); assertFalse(r.generated); assertEquals(before, Files.readString(f)); assertFalse(Files.exists(dir.resolve(".fission-node-id")))
    }

    @Test fun `a missing id is generated saved into the yml with comments kept and reused`() {
        val f = write("# top comment\nnode:  # section comment\n  mode: backend   # keep\n  security: false\n  # id: backend-1   # example\n  master: \"127.0.0.1:25600\"\n")
        val first = NodeIds.resolve(f, null)
        assertTrue(first.generated); assertEquals(f, first.savedTo)
        val text = Files.readString(f)
        assertTrue(text.contains("# top comment") && text.contains("# section comment") && text.contains("# keep") && text.contains("# id: backend-1   # example"), text)
        val root = FissionConfig.parse(text); val cfg = NodeConfig.from(root, dir); root.finish()
        assertEquals(first.id, cfg.nodeId)
        // a restart reads it back from the file: same id, nothing written again
        val second = NodeIds.resolve(f, cfg.nodeId); assertEquals(first.id, second.id); assertFalse(second.generated); assertEquals(text, Files.readString(f))
    }

    @Test fun `an empty id line is replaced`() {
        val f = write("node:\n  mode: backend\n  id: \"\"\n")
        val r = NodeIds.resolve(f, null); assertEquals(1, Files.readAllLines(f).count { it.trim().startsWith("id:") })
        assertEquals(r.id, FissionConfig.parse(Files.readString(f)).section("node").optString("id"))
    }

    @Test fun `flow style combined mode or no node section use the side file and keep it`() {
        val f = write("node: { mode: backend }\n")
        val r = NodeIds.resolve(f, null); assertTrue(r.generated); assertEquals(dir.resolve(".fission-node-id"), r.savedTo); assertEquals("node: { mode: backend }\n", Files.readString(f))
        val again = NodeIds.resolve(f, null); assertEquals(r.id, again.id); assertFalse(again.generated)
        val g = write("node:\n  mode: combined\n"); Files.delete(dir.resolve(".fission-node-id"))
        val c = NodeIds.resolve(g, null, persistInYml = false); assertEquals(dir.resolve(".fission-node-id"), c.savedTo); assertEquals("node:\n  mode: combined\n", Files.readString(g))
    }

    @Test fun `config without an id parses with an empty id and stays strict`() {
        val root = FissionConfig.parse("node: { mode: standalone }"); assertEquals("", NodeConfig.from(root, dir).nodeId)
        val bad = FissionConfig.parse("node: { mode: standalone, ide: x }"); NodeConfig.from(bad, dir); assertFailsWith<ConfigException> { bad.finish() }
    }
}
