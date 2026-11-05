package net.fissionmc.master

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.PlayerProfile
import net.fissionmc.master.client.ProfileProperty
import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class PlayerRelayTest {
    private class FakeLink : NodeLink {
        val sent = mutableListOf<NodeMessage>()
        override val ready = CompletableFuture<PeerInfo>()
        override val closed = CompletableFuture<String>()
        override val latencyNanos = -1L
        @Volatile override var isOpen = true
        @Volatile override var isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        override fun send(msg: NodeMessage) { sent += msg }
        override fun close(reason: String) { isOpen = false; closed.complete(reason) }
    }
    private class FakeClient(val name: String) : RelayClient {
        override val profile = PlayerProfile(UUID.nameUUIDFromBytes(name.toByteArray()), name, listOf(ProfileProperty("textures", "v", null)))
        override val protocol = 777
        val got = mutableListOf<ByteArray>(); var detachedReason: String? = null; var lost: Boolean? = null
        override val remoteAddress = java.net.InetSocketAddress(java.net.InetAddress.getByAddress(byteArrayOf(10, 1, 2, 3)), 50505)
        override val virtualHost = "play.example.org"
        override fun deliver(frame: ByteArray) { got += frame }
        override fun detached(reason: String, backendLost: Boolean) { detachedReason = reason; lost = backendLost }
    }
    private fun registryWith(vararg ids: String): Pair<NodeRegistry, Map<String, FakeLink>> {
        val reg = NodeRegistry(); val links = ids.associateWith { FakeLink() }
        links.forEach { (id, l) -> reg.register(PeerInfo(id, NodeRole.BACKEND, 1, "26.3"), l) }
        return reg to links
    }

    @Test fun `messages survive the wire`() {
        val reg = MessageRegistry.core().registerPlayerMessages()
        val msgs = listOf(
            PlayerAttach(7, 1L, -2L, "Steve", listOf(ProfilePropertyData("textures", "v", "sig"), ProfilePropertyData("x", "y", null)), 777),
            PlayerClientInfo(7, "10.1.2.3", 50505, "play.example.org"), PlayerPacketToBackend(7, byteArrayOf(1, 2, 3)), PlayerPacketToClient(7, ByteArray(100_000) { it.toByte() }), PlayerDetach(7, "bye"),
        )
        for (m in msgs) {
            val b = Unpooled.buffer(); reg.encode(m, b); val d = reg.decode(b)
            when (m) {
                is PlayerPacketToBackend -> assertContentEquals(m.bytes, (d as PlayerPacketToBackend).bytes)
                is PlayerPacketToClient -> assertContentEquals(m.bytes, (d as PlayerPacketToClient).bytes)
                else -> assertEquals(m, d)
            }
            assertEquals(0, b.readableBytes())
        }
    }

    @Test fun `no backend means no attach`() {
        assertNull(PlayerRelay(NodeRegistry()).attach(FakeClient("a")))
    }

    @Test fun `round robin over live backends and frames flow both ways`() {
        val (reg, links) = registryWith("b1", "b2")
        val relay = PlayerRelay(reg); reg.addListener(relay.listener)
        val c1 = FakeClient("one"); val c2 = FakeClient("two")
        val id1 = relay.attach(c1)!!; val id2 = relay.attach(c2)!!
        assertEquals(1, links["b1"]!!.sent.filterIsInstance<PlayerAttach>().size)
        assertEquals(1, links["b2"]!!.sent.filterIsInstance<PlayerAttach>().size)
        val info = links["b1"]!!.sent.filterIsInstance<PlayerClientInfo>().single()
        assertEquals(PlayerClientInfo(id1, "10.1.2.3", 50505, "play.example.org"), info)
        assertTrue(links["b1"]!!.sent.indexOf(info) < links["b1"]!!.sent.indexOfFirst { it is PlayerAttach })   // info precedes attach
        val a = links["b1"]!!.sent.filterIsInstance<PlayerAttach>().single()
        assertEquals("one", a.name); assertEquals(1, a.properties.size)

        assertTrue(relay.toBackend(id1, byteArrayOf(9)))
        assertContentEquals(byteArrayOf(9), links["b1"]!!.sent.filterIsInstance<PlayerPacketToBackend>().single().bytes)
        // a frame for player 1 from the wrong backend is ignored
        relay.handle(reg["b2"]!!, PlayerPacketToClient(id1, byteArrayOf(5)))
        assertTrue(c1.got.isEmpty())
        relay.handle(reg["b1"]!!, PlayerPacketToClient(id1, byteArrayOf(5)))
        assertContentEquals(byteArrayOf(5), c1.got.single())
        relay.handle(reg["b2"]!!, PlayerDetach(id2, "kicked")); assertEquals("kicked", c2.detachedReason); assertEquals(false, c2.lost)
    }

    @Test fun `saturated link reports backpressure and client detach reaches the backend`() {
        val (reg, links) = registryWith("b1")
        val relay = PlayerRelay(reg); val id = relay.attach(FakeClient("x"))!!
        links["b1"]!!.isWritable = false
        assertFalse(relay.toBackend(id, byteArrayOf(1))); assertFalse(relay.isWritable(id))
        links["b1"]!!.isWritable = true; assertTrue(relay.isWritable(id))
        relay.detach(id, "client left")
        assertEquals(PlayerDetach(id, "client left"), links["b1"]!!.sent.last())
        assertEquals(0, relay.attached)
    }

    @Test fun `losing the backend detaches its players only`() {
        val (reg, links) = registryWith("b1", "b2")
        val relay = PlayerRelay(reg); reg.addListener(relay.listener)
        val c1 = FakeClient("one"); val c2 = FakeClient("two")
        relay.attach(c1); relay.attach(c2)
        links["b1"]!!.close("gone")
        assertNotNull(c1.detachedReason); assertEquals(true, c1.lost); assertNull(c2.detachedReason)
    }
}
