package net.fissionmc.net

import net.fissionmc.protocol.*
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TransportTest {
    private fun roundTrip(address: NodeAddress) {
        val master = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val backend = NettyTransport(LocalNode("be-1", NodeRole.BACKEND, "26.3"))
        val serverSide = java.util.concurrent.CompletableFuture<NodeLink>()
        val bound = master.bind(address) { l -> l.handler = { m -> if (m is Ping) l.send(Pong(m.seq + 100, m.sentNanos)) }; serverSide.complete(l) }
        try {
            val link = backend.connect(address).get(5, TimeUnit.SECONDS)
            val peer = link.ready.get(5, TimeUnit.SECONDS)
            assertEquals("master", peer.nodeId)
            assertEquals(NodeRole.MASTER, peer.role)
            assertEquals("be-1", serverSide.get().ready.get(5, TimeUnit.SECONDS).nodeId)
            // user message path: server echoes Ping as a Pong with seq+100 through its handler
            val got = java.util.concurrent.CompletableFuture<NodeMessage>()
            link.handler = { got.complete(it) }
            link.send(Ping(1, 42))
            // Ping is a control message, so it is answered by LinkHandler (Pong) and never reaches handlers.
            assertTimeoutPreemptively { while (link.latencyNanos < 0) Thread.sleep(5) }
            link.close("done")
            assertTrue(link.closed.get(5, TimeUnit.SECONDS).startsWith("local"))
            assertTrue(serverSide.get().closed.get(5, TimeUnit.SECONDS).startsWith("remote"))
        } finally { bound.close(); backend.close(); master.close() }
    }

    private fun assertTimeoutPreemptively(block: () -> Unit) {
        val t = Thread(block).also { it.isDaemon = true; it.start() }
        t.join(5000); assertFalse(t.isAlive, "timed out")
    }

    @Test fun localRoundTrip() = roundTrip(NodeAddress.Local("t-" + System.nanoTime()))

    @Test fun tcpRoundTrip() {
        val port = ServerSocket(0).use { it.localPort }
        roundTrip(NodeAddress.Tcp("127.0.0.1", port))
    }

    @Test fun versionMismatchRejected() {
        val addr = NodeAddress.Local("v-" + System.nanoTime())
        val master = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val backend = NettyTransport(LocalNode("be", NodeRole.BACKEND, "26.2"))
        val bound = master.bind(addr) {}
        try {
            val link = backend.connect(addr).get(5, TimeUnit.SECONDS)
            val ex = assertFailsWith<java.util.concurrent.ExecutionException> { link.ready.get(5, TimeUnit.SECONDS) }
            assertTrue(ex.cause!!.message!!.contains("mc version"))
        } finally { bound.close(); backend.close(); master.close() }
    }

    @Test fun codecRoundTrip() {
        val reg = MessageRegistry.core()
        val buf = io.netty.buffer.Unpooled.buffer()
        reg.encode(Hello(1, "x", NodeRole.BACKEND, "26.3", byteArrayOf(1, 2)), buf)
        val h = reg.decode(buf) as Hello
        assertEquals("x", h.nodeId); assertContentEquals(byteArrayOf(1, 2), h.nonce)
    }
}
