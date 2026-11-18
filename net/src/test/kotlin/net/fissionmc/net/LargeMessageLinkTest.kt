package net.fissionmc.net

import net.fissionmc.protocol.*
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The large message layer on real links (Netty pipeline, link handshake, encryption). */
class LargeMessageLinkTest {
    private val masterKeys = Keys.generate(); private val beKeys = Keys.generate()
    private val saved = Limits.own()
    @BeforeTest fun raise() { Limits.setOwn(Limits(maxPluginMessage = 32 shl 20)) }
    @AfterTest fun restore() { Limits.setOwn(saved) }
    private fun bytes(n: Int) = ByteArray(n) { ((it * 7 + (it shr 8)) and 0xff).toByte() }

    private class Pair(val m: NettyTransport, val b: NettyTransport, val serverLink: NodeLink, val clientLink: NodeLink, val atServer: LinkedBlockingQueue<NodeMessage>, val atClient: LinkedBlockingQueue<NodeMessage>, val bound: java.io.Closeable) {
        fun close() { bound.close(); b.close(); m.close() }
    }

    private fun connect(tcp: Boolean, secure: Boolean): Pair {
        val addr: NodeAddress = if (tcp) NodeAddress.Tcp("127.0.0.1", ServerSocket(0).use { it.localPort }) else NodeAddress.Local("lm-" + System.nanoTime())
        val m = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"), security = if (secure) NodeSecurity(masterKeys.private) { if (it == "be-1") beKeys.public else null } else null)
        val b = NettyTransport(LocalNode("be-1", NodeRole.BACKEND, "26.3"), security = if (secure) NodeSecurity(beKeys.private) { if (it == "master") masterKeys.public else null } else null)
        val atServer = LinkedBlockingQueue<NodeMessage>(); val atClient = LinkedBlockingQueue<NodeMessage>()
        val sl = java.util.concurrent.CompletableFuture<NodeLink>()
        val bound = m.bind(addr) { l -> l.handler = { atServer.add(it) }; sl.complete(l) }
        val cl = b.connect(addr).get(5, TimeUnit.SECONDS); cl.handler = { atClient.add(it) }
        cl.ready.get(5, TimeUnit.SECONDS); val s = sl.get(5, TimeUnit.SECONDS); s.ready.get(5, TimeUnit.SECONDS)
        return Pair(m, b, s, cl, atServer, atClient, bound)
    }

    private fun plugin(ch: String, n: Int) = NetPluginMessage("be-1", "x", "p", ch, bytes(n))

    @Test fun `20 MiB over an encrypted tcp link, in both directions, with small messages in between`() {
        val p = connect(tcp = true, secure = true)
        try {
            val big = plugin("t:big", 20 shl 20)
            p.clientLink.send(WorldRequest("before")); p.clientLink.send(big); p.clientLink.send(WorldRequest("after-big"))
            p.serverLink.send(plugin("t:down", 8 shl 20)); p.serverLink.send(WorldRequest("down-small"))
            val up = ArrayList<NodeMessage>(); repeat(3) { up += p.atServer.poll(60, TimeUnit.SECONDS) ?: fail("missing message at the master: got $up") }
            val bigAt = up.filterIsInstance<NetPluginMessage>().single(); assertContentEquals(big.data, bigAt.data)
            assertEquals("before", (up[0] as WorldRequest).name)
            assertTrue(up.indexOf(bigAt) > 0 && up.map { (it as? WorldRequest)?.name }.contains("after-big"))
            val down = ArrayList<NodeMessage>(); repeat(2) { down += p.atClient.poll(60, TimeUnit.SECONDS) ?: fail("missing message at the backend") }
            assertContentEquals(bytes(8 shl 20), down.filterIsInstance<NetPluginMessage>().single().data)
            assertTrue(p.clientLink.isOpen && p.serverLink.isOpen)
        } finally { p.close() }
    }

    @Test fun `a small message is not held up by a big transfer`() {
        val p = connect(tcp = false, secure = false)
        try {
            p.clientLink.send(plugin("t:big", 24 shl 20)); p.clientLink.send(WorldRequest("quick"))
            val first = p.atServer.poll(30, TimeUnit.SECONDS)
            assertEquals("quick", (first as WorldRequest).name, "the small message arrives before the 24 MiB one completes")
            assertEquals(24 shl 20, (p.atServer.poll(60, TimeUnit.SECONDS) as NetPluginMessage).data.size)
        } finally { p.close() }
    }

    @Test fun `plugin messages of one channel keep their order around a big one`() {
        val p = connect(tcp = false, secure = false)
        try {
            for (i in 0 until 5) p.clientLink.send(NetPluginMessage("be-1", "x", "p", "t:c", if (i == 1) bytes(6 shl 20) else byteArrayOf(i.toByte())))
            val sizes = (0 until 5).map { (p.atServer.poll(60, TimeUnit.SECONDS) as NetPluginMessage).data.size }
            assertEquals(listOf(1, 6 shl 20, 1, 1, 1), sizes)
        } finally { p.close() }
    }

    @Test fun `a big player save is delivered before the flush confirmation that follows it`() {
        val p = connect(tcp = false, secure = false)
        try {
            p.clientLink.send(PlayerDataSave(1L, 2L, bytes(3 shl 20))); p.clientLink.send(PlayerFlushed(1L, 2L)); p.clientLink.send(PlayerFlushed(9L, 9L))
            val got = (0 until 3).map { p.atServer.poll(60, TimeUnit.SECONDS) ?: fail("missing message") }
            val save = got.indexOfFirst { it is PlayerDataSave }; val flushed = got.indexOfFirst { it is PlayerFlushed && it.uuidHigh == 1L }
            assertTrue(save in 0 until flushed, "the save must arrive before its flush confirmation: " + got.map { it.javaClass.simpleName })
        } finally { p.close() }
    }

    @Test fun `a handler that throws on one message does not close the link`() {
        val p = connect(tcp = false, secure = false)
        try {
            p.serverLink.handler = { if (it is WorldRequest && it.name == "boom") throw IllegalStateException("handler bug") else p.atServer.add(it) }
            p.clientLink.send(WorldRequest("boom")); p.clientLink.send(WorldRequest("after"))
            assertEquals("after", (p.atServer.poll(10, TimeUnit.SECONDS) as WorldRequest).name)
            assertTrue(p.serverLink.isOpen && p.clientLink.isOpen)
        } finally { p.close() }
    }

    @Test fun `a message over the peer's limit is refused locally and the link stays up`() {
        val p = connect(tcp = false, secure = false)
        try {
            val end = System.nanoTime() + 5_000_000_000L; while (p.clientLink.peerLimits == null && System.nanoTime() < end) Thread.onSpinWait()
            val limits = p.clientLink.peerLimits!!
            val huge = plugin("t:c", limits.maxPluginMessage + 1)
            val err = java.util.concurrent.CompletableFuture<Throwable?>()
            p.clientLink.sendTracked(huge) { err.complete(it) }
            val e = err.get(10, TimeUnit.SECONDS)
            assertIs<LinkLimitException>(e); assertTrue("NetPluginMessage" in e.message!!, e.message)
            p.clientLink.send(WorldRequest("still-works"))
            assertEquals("still-works", (p.atServer.poll(10, TimeUnit.SECONDS) as WorldRequest).name)
            assertTrue(p.clientLink.isOpen)
        } finally { p.close() }
    }

    @Test fun `sending right after connect waits for the limits exchange and arrives in order`() {
        val p = connect(tcp = true, secure = true)
        try {
            for (i in 0 until 50) p.clientLink.send(WorldRequest("m$i"))
            assertEquals((0 until 50).map { "m$it" }, (0 until 50).map { (p.atServer.poll(10, TimeUnit.SECONDS) as WorldRequest).name })
        } finally { p.close() }
    }
}
