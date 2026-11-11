package net.fissionmc.master

import net.fissionmc.master.chunk.ChunkAuthority
import net.fissionmc.master.chunk.FileChunkStore
import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Fixes from the 2026-11-10 review: event log across a master restart, generation jobs whose owner is gone, the position memory for region-aware joins. */
class HardeningTest {
    private class Link : NodeLink {
        val sent = java.util.concurrent.LinkedBlockingQueue<NodeMessage>()
        override val ready = CompletableFuture<PeerInfo>(); override val closed = CompletableFuture<String>(); override val latencyNanos = -1L
        @Volatile override var isOpen = true; override val isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        override fun send(msg: NodeMessage) { sent.add(msg) }
        override fun close(reason: String) { isOpen = false }
        fun next(): NodeMessage = sent.poll(5, TimeUnit.SECONDS) ?: error("no message")
    }

    @Test fun `a restored event log keeps numbering and reports a gap to a backend that is behind`() {
        val log = EventLog(); log.restore(500)
        val gap = log.since(120)
        assertTrue(gap is EventLog.Since.Gap); assertEquals(501, (gap as EventLog.Since.Gap).oldestSeq)
        assertEquals(501, log.append("a", BlockChange("minecraft:overworld", 1, 2, 3, 7, "block")).seq)
        assertTrue(log.since(500) is EventLog.Since.Events)
    }

    @Test fun `the hub's epoch and position survive a restart`() {
        val dir = Files.createTempDirectory("hub"); val file = dir.resolve("replication.state")
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it).also { t -> t.isDaemon = true } }
        val first = ReplicationHub.persistent(file, timer)
        repeat(3) { first.submit("a", BlockChange("minecraft:overworld", it, 2, 3, 7, "block")) }
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end && Files.readString(file).trim() != "${first.epoch} 3") Thread.sleep(50)
        assertEquals("${first.epoch} 3", Files.readString(file).trim())
        val second = ReplicationHub.persistent(file, timer)
        assertEquals(first.epoch, second.epoch, "same epoch: backends are not told to start over")
        assertEquals(3 + ReplicationHub.RESTART_MARGIN, second.log.lastSeq)
        // a backend that had applied everything resumes: it is told where the log starts now and goes on from there
        val reg = NodeRegistry(); val link = Link(); reg.register(PeerInfo("a", NodeRole.BACKEND, 1, "26.3"), link)
        second.handle(reg["a"]!!, ResumeFrom(first.epoch, 3))
        val m = link.next()
        assertTrue(m is LogTruncated, "got $m"); assertEquals(second.log.lastSeq + 1, (m as LogTruncated).oldestSeq)
    }

    @Test fun `a generation job whose owner is gone goes to the waiting backend`() {
        val reg = NodeRegistry(); val a = Link(); val b = Link()
        reg.register(PeerInfo("a", NodeRole.BACKEND, 1, "26.3"), a); reg.register(PeerInfo("b", NodeRole.BACKEND, 2, "26.3"), b)
        val auth = ChunkAuthority(FileChunkStore(Files.createTempDirectory("store")), reg, Executors.newSingleThreadScheduledExecutor { Thread(it).also { t -> t.isDaemon = true } }, {}, 30_000,
            generationOwner = { _, _, _ -> "a" })
        auth.handle(reg["b"]!!, ChunkRequest(7, "minecraft:overworld", ChunkKind.CHUNK, 4, 4, false))
        assertTrue(a.next() is GenerateChunk)                       // the owner is asked
        val t0 = System.currentTimeMillis()
        auth.retryJobs(t0 + 11_000)
        assertTrue(a.next() is GenerateChunk, "asked again while a requester still waits")
        a.isOpen = false
        auth.retryJobs(t0 + 30_000)
        val r = b.next()
        assertTrue(r is ChunkAbsent && r.lease && r.reqId == 7L, "the waiting backend generates it: $r")
    }

    @Test fun `the store remembers where a player was`() {
        val s = PlayerDataStore(Files.createTempDirectory("pd")); val u = java.util.UUID.randomUUID()
        assertNull(s.readPosition(u))
        s.savePosition(u, "minecraft:overworld", -120, 44)
        assertEquals(Triple("minecraft:overworld", -120, 44), s.readPosition(u))
    }
}
