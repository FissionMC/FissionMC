package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.NodeRole
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class NodeRegistryTest {
    private class FakeLink : NodeLink {
        override val ready = CompletableFuture<PeerInfo>()
        override val closed = CompletableFuture<String>()
        override val latencyNanos = -1L
        @Volatile override var isOpen = true
        @Volatile override var isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        override fun send(msg: NodeMessage) {}
        override fun close(reason: String) { isOpen = false; closed.complete(reason) }
    }

    @Test fun `replacing a live link fires left for the old node exactly once`() {
        val reg = NodeRegistry(); val events = mutableListOf<String>()
        reg.addListener(object : NodeRegistry.Listener {
            override fun joined(node: RegisteredNode) { events += "joined:" + System.identityHashCode(node.link) }
            override fun left(node: RegisteredNode, reason: String) { events += "left:" + System.identityHashCode(node.link) }
        })
        val a = FakeLink(); val b = FakeLink(); val info = PeerInfo("backend-a", NodeRole.BACKEND, 1, "26.3")
        reg.register(info, a); reg.register(info, b)
        val ia = System.identityHashCode(a); val ib = System.identityHashCode(b)
        assertEquals(listOf("joined:$ia", "left:$ia", "joined:$ib"), events)
        assertSame(b, reg["backend-a"]!!.link)
        b.close("gone")
        assertEquals("left:$ib", events.last()); assertEquals(4, events.size)
    }

    @Test fun `a booting node is not ready until it says so and only a current link can say it`() {
        val reg = NodeRegistry(); val events = mutableListOf<String>()
        reg.addListener(object : NodeRegistry.Listener { override fun ready(node: RegisteredNode) { events += "ready:" + node.info.nodeId } })
        val info = PeerInfo("backend-b", NodeRole.BACKEND, 1, "26.3")
        reg.register(info, FakeLink(), ready = false)
        val old = reg["backend-b"]!!
        assertFalse(old.ready); assertEquals(emptyList(), events)
        val newer = FakeLink(); reg.register(info, newer, ready = false)   // the link was replaced: a late NodeReady of the old one must not make the new one ready
        reg.markReady(old); assertFalse(reg["backend-b"]!!.ready); assertEquals(emptyList(), events)
        reg.markReady(reg["backend-b"]!!); reg.markReady(reg["backend-b"]!!)
        assertTrue(reg["backend-b"]!!.ready); assertEquals(listOf("ready:backend-b"), events)   // once
        reg.register(PeerInfo("backend-a", NodeRole.BACKEND, 1, "26.3"), FakeLink())          // registered ready (tests, no readiness protocol)
        assertEquals(listOf("ready:backend-b", "ready:backend-a"), events)
    }
}
