package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.protocol.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PluginBusTest {
    private class Link(val id: String) : NodeLink {
        val sent = LinkedBlockingQueue<NodeMessage>()
        override val ready = CompletableFuture<PeerInfo>(); override val closed = CompletableFuture<String>(); override val latencyNanos = -1L
        @Volatile override var isOpen = true; override val isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        @Volatile var failWith: Throwable? = null
        override fun send(msg: NodeMessage) { sent.add(msg) }
        override fun sendTracked(msg: NodeMessage, done: (Throwable?) -> Unit) { val f = failWith; if (f == null) { send(msg); done(null) } else done(f) }
        override fun close(reason: String) { isOpen = false }
        fun next(): NodeMessage = sent.poll(2, TimeUnit.SECONDS) ?: error("no message")
        fun none() = sent.isEmpty()
    }
    private val registry = NodeRegistry()
    private val links = HashMap<String, Link>()
    private fun node(id: String): Link = Link(id).also { l -> links[id] = l; registry.register(PeerInfo(id, NodeRole.BACKEND, 1, null), l) }
    private val logs = ArrayList<String>()
    private fun bus() = PluginBus(registry) { logs += it }
    private fun msg(target: String, data: ByteArray = byteArrayOf(1, 2, 3), plugin: String = "p", channel: String = "t:c", origin: String = "forged") = NetPluginMessage(origin, target, plugin, channel, data)

    @Test fun `routes to a node with the origin of the authenticated link`() {
        val bus = bus(); val a = node("a"); val b = node("b")
        assertTrue(bus.handle(a, "a", msg("b")))
        val got = b.next() as NetPluginMessage
        assertEquals("a", got.origin, "a backend cannot forge the origin"); assertContentEquals(byteArrayOf(1, 2, 3), got.data); assertEquals("t:c", got.channel)
        assertTrue(a.none())
    }

    @Test fun `broadcast reaches every backend but the sender`() {
        val bus = bus(); val a = node("a"); val b = node("b"); val c = node("c")
        bus.handle(a, "a", msg("*"))
        assertEquals("a", (b.next() as NetPluginMessage).origin); assertEquals("a", (c.next() as NetPluginMessage).origin); assertTrue(a.none())
    }

    @Test fun `an unknown target is reported back to the sender`() {
        val bus = bus(); val a = node("a")
        bus.handle(a, "a", msg("nobody"))
        val u = a.next() as NetPluginUndeliverable
        assertEquals("nobody", u.target); assertEquals("t:c", u.channel); assertTrue("nobody" in u.reason)
        val off = node("off"); off.isOpen = false
        bus.handle(a, "a", msg("off")); assertTrue(a.next() is NetPluginUndeliverable)
    }

    @Test fun `a target link that refuses the message is reported`() {
        val bus = bus(); val a = node("a"); val b = node("b"); b.failWith = LinkLimitException("too big for b")
        bus.handle(a, "a", msg("b")); assertTrue("too big for b" in (a.next() as NetPluginUndeliverable).reason)
    }

    @Test fun `messages to the master go to its listeners`() {
        val bus = bus(); val a = node("a"); val got = ArrayList<Pair<String, Int>>()
        bus.listen("t:c") { from, data -> got += from to data.size }
        bus.handle(a, "a", msg("master", ByteArray(500)))
        assertEquals(listOf("a" to 500), got)
        bus.handle(a, "a", msg("master", channel = "t:unheard")); assertTrue(logs.any { "nothing on the master listens" in it })
    }

    @Test fun `selftest channel logs size and hash`() {
        val bus = bus(); val a = node("a"); val data = ByteArray(1000) { it.toByte() }
        bus.handle(a, "a", msg("master", data, channel = PluginBus.SELFTEST_CHANNEL))
        val sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data))
        assertTrue(logs.any { "1000 bytes sha256=$sha" in it })
    }

    @Test fun `quota per plugin`() {
        val bus = bus(); val a = node("a"); node("b")
        val rate = LimitsConfig.active.pluginRate
        repeat(rate) { bus.handle(a, "a", msg("master", plugin = "spam")) }
        bus.handle(a, "a", msg("master", plugin = "spam"))
        assertTrue("quota of spam" in (a.next() as NetPluginUndeliverable).reason)
        bus.handle(a, "a", msg("b", plugin = "polite"))      // another plugin of the same node is not affected
        assertTrue(a.none())
    }

    @Test fun `other messages are not consumed and a message without a known node is ignored`() {
        val bus = bus(); val a = node("a")
        assertFalse(bus.handle(a, "a", Ping(1, 1))); assertTrue(bus.handle(a, "", msg("master")))
    }
}
