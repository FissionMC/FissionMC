package net.fissionmc.master.ticksync

import net.fissionmc.master.NodeRegistry
import net.fissionmc.master.RegisteredNode
import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.*
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class TickSyncHubTest {
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
        fun last() = synchronized(sent) { sent.lastOrNull() }
        fun count() = synchronized(sent) { sent.size }
    }

    private class Net(enabled: Boolean = true) {
        val registry = NodeRegistry(); val links = HashMap<String, FakeLink>(); val nodes = HashMap<String, RegisteredNode>()
        val hub = TickSyncHub(registry, TickSyncModel(), enabled, 0)
        fun add(id: String) { val l = FakeLink(); val info = PeerInfo(id, NodeRole.BACKEND, 1, "26.3"); links[id] = l; registry.register(info, l); nodes[id] = registry[id]!! }
        fun send(id: String, m: NodeMessage) = hub.onMessage(nodes[id]!!, m)
    }

    @Test fun `a change goes to every other backend and not back to its origin`() {
        val n = Net(); listOf("a", "b", "c").forEach(n::add)
        n.send("b", TickSyncChange(TickAction.RATE, 1f))
        assertEquals(TickSyncApply(TickAction.RATE, 1f, "b"), n.links["a"]!!.last()); assertEquals(TickSyncApply(TickAction.RATE, 1f, "b"), n.links["c"]!!.last())
        assertEquals(0, n.links["b"]!!.count()); assertEquals(1f, n.hub.model.rate)
    }

    @Test fun `a backend that attaches gets the current state, a restarted one too`() {
        val n = Net(); n.add("a"); n.add("b")
        n.send("a", TickSyncHello(20f, false))       // seeds the empty master
        n.send("b", TickSyncChange(TickAction.RATE, 5f)); n.send("a", TickSyncChange(TickAction.FREEZE, 0f))
        n.send("b", TickSyncHello(20f, false))       // b restarted at 20
        assertEquals(TickSyncState(true, 5f, true), n.links["b"]!!.last())
    }

    @Test fun `a rejected change is not forwarded and the origin is given the canonical state`() {
        val n = Net(); n.add("a"); n.add("b"); n.send("a", TickSyncChange(TickAction.RATE, 20_000f))
        assertEquals(0, n.links["b"]!!.count()); assertEquals(TickSyncState(true, 20f, false), n.links["a"]!!.last()); assertEquals(1, n.hub.rejected.get())
        n.send("a", TickSyncChange(TickAction.STEP, 5f))      // not frozen
        assertEquals(0, n.links["b"]!!.count())
    }

    @Test fun `with tick sync off nothing is forwarded and a hello is answered as disabled`() {
        val n = Net(false); n.add("a"); n.add("b")
        n.send("a", TickSyncChange(TickAction.RATE, 1f)); assertEquals(0, n.links["b"]!!.count()); assertEquals(20f, n.hub.model.rate)
        n.send("a", TickSyncHello(3f, false)); assertEquals(TickSyncState(false, 20f, false), n.links["a"]!!.last())
    }

    @Test fun `a step and a sprint are forwarded but leave the stored state alone`() {
        val n = Net(); n.add("a"); n.add("b"); n.send("a", TickSyncChange(TickAction.FREEZE, 0f))
        n.send("a", TickSyncChange(TickAction.STEP, 3f)); n.send("a", TickSyncChange(TickAction.SPRINT, 100f))
        assertEquals(TickSyncApply(TickAction.SPRINT, 100f, "a"), n.links["b"]!!.last()); assertEquals(3, n.links["b"]!!.count())
        assertTrue(n.hub.model.frozen); assertEquals(20f, n.hub.model.rate)
    }

    @Test fun `the periodic state broadcast reaches every backend, also the ones that changed something themselves`() {
        val n = Net(); n.add("a"); n.add("b")
        n.send("a", TickSyncChange(TickAction.RATE, 5f)); n.send("b", TickSyncChange(TickAction.RATE, 10f))   // a simultaneous change: each missed the other's
        n.hub.broadcastState()
        assertEquals(TickSyncState(true, 10f, false), n.links["a"]!!.last()); assertEquals(TickSyncState(true, 10f, false), n.links["b"]!!.last())
    }
    @Test fun `no periodic broadcast when sync is off`() { val n = Net(false); n.add("a"); n.hub.broadcastState(); assertEquals(0, n.links["a"]!!.count()) }
}
