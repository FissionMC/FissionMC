package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.*
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class ReplicationTest {
    private fun bc(x: Int) = BlockChange("minecraft:overworld", x, 64, 0, 1, "test")

    private class FakeLink : NodeLink {
        val sent = mutableListOf<NodeMessage>()
        override val ready = CompletableFuture<PeerInfo>()
        override val closed = CompletableFuture<String>()
        override val latencyNanos = -1L
        @Volatile override var isOpen = true
        override val isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        override fun send(msg: NodeMessage) { synchronized(sent) { sent += msg } }
        override fun close(reason: String) { isOpen = false }
        fun seqs() = synchronized(sent) { sent.filterIsInstance<SequencedEvent>().map { it.seq } }
    }

    private fun node(id: String, l: FakeLink) = RegisteredNode(PeerInfo(id, NodeRole.BACKEND, 1, "26.3"), l)

    @Test fun sequencesAreMonotonicAndLogIsBounded() {
        val log = EventLog(capacity = 3)
        val seqs = (1..5).map { log.append("a", bc(it)).seq }
        assertEquals(listOf(1L, 2, 3, 4, 5), seqs)
        assertEquals(3, log.size())
        assertIs<EventLog.Since.Gap>(log.since(1)).also { assertEquals(3L, it.oldestSeq) }
        assertEquals(listOf(4L, 5), assertIs<EventLog.Since.Events>(log.since(3)).events.map { it.seq })
        assertEquals(listOf(3L, 4, 5), assertIs<EventLog.Since.Events>(log.since(2)).events.map { it.seq })
        assertTrue(assertIs<EventLog.Since.Events>(log.since(5)).events.isEmpty())
    }

    @Test fun duplicateAndStaleAcksNeverMoveBackwards() {
        val log = EventLog()
        repeat(5) { log.append("a", bc(it)) }
        log.ack("b", 4); log.ack("b", 4); log.ack("b", 2)
        assertEquals(4, log.acked("b"))
        log.ack("b", 99) // beyond the log: ignored
        assertEquals(4, log.acked("b"))
        assertEquals(0, log.acked("nobody"))
    }

    @Test fun hubBroadcastsToAllLiveNodesInOneOrderIncludingOrigin() {
        val hub = ReplicationHub()
        val la = FakeLink(); val lb = FakeLink()
        val a = node("a", la); val b = node("b", lb)
        hub.handle(a, ResumeFrom(0, 0)); hub.handle(b, ResumeFrom(0, 0))
        hub.handle(a, EventSubmit(bc(1))); hub.handle(b, EventSubmit(bc(2))); hub.handle(a, EventSubmit(bc(3)))
        assertEquals(listOf(1L, 2, 3), la.seqs()); assertEquals(listOf(1L, 2, 3), lb.seqs())
        val firstForB = lb.sent.filterIsInstance<SequencedEvent>().first()
        assertEquals("a", firstForB.origin)
        assertEquals(setOf("a", "b"), hub.liveNodes())
    }

    @Test fun resumeReplaysOnlyMissedEventsAfterReconnect() {
        val hub = ReplicationHub()
        val la = FakeLink(); val a = node("a", la)
        hub.handle(a, ResumeFrom(0, 0))
        repeat(3) { hub.handle(a, EventSubmit(bc(it))) }
        // b joins late, then drops after seeing 3, misses 4 and 5, reconnects
        val lb1 = FakeLink(); val b1 = node("b", lb1)
        hub.handle(b1, ResumeFrom(0, 0))
        assertEquals(listOf(1L, 2, 3), lb1.seqs())
        val epoch = lb1.sent.filterIsInstance<LogReset>().single().epoch
        hub.listener.left(b1, "gone")
        hub.handle(a, EventSubmit(bc(4))); hub.handle(a, EventSubmit(bc(5)))
        assertEquals(listOf(1L, 2, 3), lb1.seqs()) // nothing after it left
        val lb2 = FakeLink(); val b2 = node("b", lb2)
        hub.handle(b2, ResumeFrom(epoch, 3))
        assertEquals(listOf(4L, 5), lb2.seqs())
        assertTrue(lb2.sent.none { it is LogReset })
        hub.handle(a, EventSubmit(bc(6)))
        assertEquals(listOf(4L, 5, 6), lb2.seqs())
    }

    @Test fun newEpochForcesFullReplayAndReset() {
        val hub = ReplicationHub()
        hub.submit("a", bc(1)); hub.submit("a", bc(2))
        val l = FakeLink()
        hub.handle(node("b", l), ResumeFrom(epoch = 12345, lastSeq = 50))
        assertIs<LogReset>(l.sent.first())
        assertEquals(listOf(1L, 2), l.seqs())
    }

    @Test fun truncatedHistoryIsReported() {
        val hub = ReplicationHub(EventLog(2))
        repeat(5) { hub.submit("a", bc(it)) }
        val l = FakeLink()
        hub.handle(node("b", l), ResumeFrom(hub.epoch, 1))
        assertEquals(4L, l.sent.filterIsInstance<LogTruncated>().single().oldestSeq)
    }

    @Test fun messagesRoundTripOnTheWire() {
        val reg = MessageRegistry.core().registerReplicationMessages()
        val buf = io.netty.buffer.Unpooled.buffer()
        val msgs = listOf(
            EventSubmit(bc(7)), SequencedEvent(9, "a", EntityRemoved("minecraft:the_nether", 1, -2, "killed")),
            SequencedEvent(10, "b", bc(-5)), EventAck(3), ResumeFrom(4, 5), LogReset(6), LogTruncated(7),
        )
        for (m in msgs) { reg.encode(m, buf); val d = reg.decode(buf)
            when (m) { is EventSubmit -> assertEquals(m.event, (d as EventSubmit).event); else -> assertEquals(m, d) } }
        assertEquals(0, buf.readableBytes())
    }
}
