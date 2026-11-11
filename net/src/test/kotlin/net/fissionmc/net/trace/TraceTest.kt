package net.fissionmc.net.trace

import io.netty.buffer.Unpooled
import net.fissionmc.net.ConfigException
import net.fissionmc.net.FissionConfig
import net.fissionmc.net.FissionDebug
import net.fissionmc.protocol.MessageRegistry
import net.fissionmc.protocol.TraceDump
import net.fissionmc.protocol.registerTraceMessages
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.test.*

class TraceTest {
    @AfterTest fun reset() { Trace.resetForTests(); FissionDebug.fromConfig(false) }

    // ---- writer
    @Test fun `the writer rotates at the size cap and keeps only the newest rotated files`() {
        val dir = Files.createTempDirectory("trace-w")
        val line = "x".repeat(99)   // 100 bytes with the newline
        TraceWriter(dir, "n1", maxBytes = 1000, keep = 2, queueSize = 100_000, today = { "2026-01-01" }).use { w -> repeat(1000) { assertTrue(w.offer(line)) } }
        val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertTrue("n1-2026-01-01.jsonl" in names, names.toString())
        val rotated = names.filter { it != "n1-2026-01-01.jsonl" }
        assertEquals(2, rotated.size, "only the 2 newest rotated files stay: $names")
        for (n in names) assertTrue(Files.size(dir.resolve(n)) <= 1000, "$n is over the cap")
        assertEquals(setOf("n1-2026-01-01.98.jsonl", "n1-2026-01-01.99.jsonl"), rotated.toSet())
    }

    @Test fun `a new day starts a new file`() {
        val dir = Files.createTempDirectory("trace-d"); var day = "2026-01-01"
        TraceWriter(dir, "n", 1 shl 20, today = { day }).use { w -> w.offer("a"); Thread.sleep(300); day = "2026-01-02"; w.offer("b") }
        assertEquals("a\n", Files.readString(dir.resolve("n-2026-01-01.jsonl"))); assertEquals("b\n", Files.readString(dir.resolve("n-2026-01-02.jsonl")))
    }

    @Test fun `a full queue drops lines, counts them and never blocks the caller`() {
        val dir = Files.createTempDirectory("trace-q")
        val w = TraceWriter(dir, "n", 1 shl 30, queueSize = 4)
        val t0 = System.nanoTime(); var accepted = 0
        repeat(200_000) { if (w.offer("line $it")) accepted++ }
        val ms = (System.nanoTime() - t0) / 1_000_000
        w.close()
        assertTrue(w.dropped.get() > 0, "a 4 slot queue must overflow"); assertEquals(200_000L, accepted + w.dropped.get())
        assertTrue(ms < 5_000, "offer must not block ($ms ms)")
        val text = Files.readString(dir.resolve(w.currentFile().fileName)); assertTrue(text.contains("trace.dropped"), "the drops are reported in the file")
    }

    // ---- ring
    @Test fun `the ring keeps the window and is bounded in size`() {
        val r = TraceRing(10_000_000_000L)   // 10 s
        r.add(0, "old"); r.add(5_000_000_000L, "mid"); r.add(12_000_000_000L, "new")
        assertEquals(listOf("mid", "new"), r.snapshot(12_000_000_000L))
        assertEquals(listOf("new"), r.snapshot(12_000_000_000L, seconds = 3))
        val small = TraceRing(Long.MAX_VALUE / 4, maxEntries = 100)
        repeat(1000) { small.add(it.toLong(), "l$it") }
        assertEquals(100, small.size); assertEquals("l900", small.snapshot(1000).first())
    }

    // ---- recorder
    @Test fun `enabled events carry time, node, seq, type, player and payload and dump to a file`() {
        val dir = Files.createTempDirectory("trace-r")
        Trace.init("backend-x", dir); Trace.apply(TraceConfig(enabled = true, ringSeconds = 60, maxFileMb = 1))
        assertTrue(Trace.on)
        val u = java.util.UUID(1, 2)
        Trace.emit("pkt.c2s") { player("Steve \"q\"", u); int("id", 42); blob("data", byteArrayOf(1, 2, 3)); ints("entityIds", intArrayOf(7, 1000001)) }
        Trace.mark("incident 1", "admin")
        val res = Trace.dumpNow(60, "req1")
        val lines = Files.readAllLines(java.nio.file.Path.of(res.path))
        assertEquals(res.events + 1, lines.size); assertTrue(lines[0].contains("\"type\":\"trace.dump\""))
        val pkt = lines.first { it.contains("pkt.c2s") }
        for (k in listOf("\"w\":", "\"ns\":", "\"node\":\"backend-x\"", "\"seq\":", "\"uuid\":\"${u}\"", "\"player\":\"Steve \\\"q\\\"\"", "\"id\":42", "\"size\":3", "\"entityIds\":[7,1000001]")) assertTrue(pkt.contains(k), "$k in $pkt")
        assertTrue(lines.any { it.contains("\"type\":\"mark\"") && it.contains("incident 1") })
        val seqs = lines.drop(1).map { Regex("\"seq\":(\\d+)").find(it)!!.groupValues[1].toLong() }; assertEquals(seqs.sorted(), seqs)
    }

    @Test fun `start and stop override the config and a changed enabled value clears the override`() {
        Trace.init("n", Files.createTempDirectory("trace-s")); Trace.apply(TraceConfig(enabled = false))
        assertFalse(Trace.on); assertTrue(Trace.start()); assertTrue(Trace.on)
        Trace.apply(TraceConfig(enabled = false, ringSeconds = 10)); assertTrue(Trace.on, "same enabled value: the override stays")
        assertTrue(Trace.stop()); assertFalse(Trace.on)
        Trace.apply(TraceConfig(enabled = true)); assertTrue(Trace.on, "config change wins again")
    }

    @Test fun `off means no event is built and nothing is allocated`() {
        Trace.resetForTests(); assertFalse(Trace.on)
        var built = 0
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val name = "Steve"; val n = 1_000_000
        repeat(50_000) { Trace.emit("warm") { str("a", name + built); built++ } }
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until n) Trace.emit("pkt.s2c") { str("a", name + i); int("i", i); built++ }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertEquals(0, built, "the payload block must not run while off")
        assertTrue(allocated < 64 * 1024, "off path allocated $allocated bytes for $n calls")
    }

    // ---- config
    @Test fun `trace config defaults, values and validation`() {
        assertEquals(TraceConfig(false, 300, 64), TraceConfig.from(FissionConfig.parse("")))
        assertEquals(TraceConfig(true, 30, 8), TraceConfig.from(FissionConfig.parse("trace: { enabled: true, ring-seconds: 30, max-file-mb: 8 }")))
        assertTrue(assertFailsWith<ConfigException> { TraceConfig.from(FissionConfig.parse("trace: { ring-seconds: 0 }")) }.message!!.contains("trace.ring-seconds"))
        assertTrue(assertFailsWith<ConfigException> { TraceConfig.from(FissionConfig.parse("trace: { max-file-mb: x }")) }.message!!.contains("trace.max-file-mb"))
        val root = FissionConfig.parse("trace: { enabled: true, typo: 1 }"); TraceConfig.from(root)
        assertTrue(assertFailsWith<ConfigException> { root.finish() }.message!!.contains("trace.typo"))
    }

    @Test fun `debug is a plain flag or a section with enabled`() {
        assertEquals(true, FissionConfig.parse("debug: true").flagOrSection("debug", false).first)
        val (on, sec) = FissionConfig.parse("debug: { enabled: true, packets: true }").flagOrSection("debug", false)
        assertTrue(on); assertTrue(sec.bool("packets", false))
        assertFalse(FissionConfig.parse("").flagOrSection("debug", false).first)
    }

    @Test fun `FissionDebug follows the config unless forced`() {
        FissionDebug.fromConfig(true); assertTrue(FissionDebug.on)
        FissionDebug.fromConfig(false); assertEquals(System.getProperty("fission.debug") != null || System.getenv("FISSION_DEBUG") != null, FissionDebug.on)
    }

    // ---- wire
    @Test fun `TraceDump survives the wire`() {
        val reg = MessageRegistry.core().registerTraceMessages()
        val m = TraceDump(TraceDump.REPLY, "abc12", seconds = 90, text = "ok", node = "backend-a", path = "/x/trace/backend-a-dump.jsonl", events = 77, dropped = 3)
        val b = Unpooled.buffer(); reg.encode(m, b)
        assertEquals(105, reg.idOf(m))
        val d = reg.decode(b) as TraceDump
        assertEquals(listOf(4, "abc12", 90, "ok", "backend-a", "/x/trace/backend-a-dump.jsonl", 77, 3L), listOf(d.op, d.reqId, d.seconds, d.text, d.node, d.path, d.events, d.dropped))
        assertFalse(b.isReadable)
    }
}
