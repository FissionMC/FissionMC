package net.fissionmc.net

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.LengthFieldPrepender
import net.fissionmc.protocol.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The node link after the 2026-11-10 review: encrypted after the handshake, small frames and no pings before it. */
class LinkCryptoTest {
    private val masterKeys = Keys.generate(); private val beKeys = Keys.generate()

    @Test fun `both sides derive the same keys and a changed or replayed frame is refused`() {
        val a = LinkCrypto.newEphemeral(); val b = LinkCrypto.newEphemeral(); val ctx = "transcript".toByteArray()
        val (i2a, a2i) = LinkCrypto.deriveKeys(a.private, b.public.encoded, ctx)
        val (i2aB, a2iB) = LinkCrypto.deriveKeys(b.private, a.public.encoded, ctx)
        assertContentEquals(i2a, i2aB); assertContentEquals(a2i, a2iB); assertFalse(i2a.contentEquals(a2i))
        val seal = LinkCrypto.Sealer(i2a, javax.crypto.Cipher.ENCRYPT_MODE); val open = LinkCrypto.Sealer(i2aB, javax.crypto.Cipher.DECRYPT_MODE)
        val c1 = seal.apply("hello".toByteArray()); val c2 = seal.apply("hello".toByteArray())
        assertFalse(c1.contentEquals(c2), "the same text never encrypts to the same bytes")
        assertEquals("hello", String(open.apply(c1)))
        assertFails { open.apply(c1) }                                        // replay: the counter moved on
        val open2 = LinkCrypto.Sealer(i2aB, javax.crypto.Cipher.DECRYPT_MODE)
        assertFails { open2.apply(c1.copyOf().also { it[2] = (it[2] + 1).toByte() }) }   // tampering
    }

    @Test fun `messages of every size cross an authenticated link`() {
        val addr = NodeAddress.Local("crypto-" + System.nanoTime())
        val m = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"), security = NodeSecurity(masterKeys.private) { if (it == "be-1") beKeys.public else null })
        val b = NettyTransport(LocalNode("be-1", NodeRole.BACKEND, "26.3"), security = NodeSecurity(beKeys.private) { if (it == "master") masterKeys.public else null })
        val atMaster = LinkedBlockingQueue<NodeMessage>(); val atBackend = LinkedBlockingQueue<NodeMessage>()
        val bound = m.bind(addr) { l -> l.handler = { atMaster.add(it) }; l.ready.thenAccept { _ -> l.send(WorldRequest("from-master")) } }
        try {
            val link = b.connect(addr).get(5, TimeUnit.SECONDS)
            link.handler = { atBackend.add(it) }
            link.ready.get(5, TimeUnit.SECONDS)
            val big = ByteArray(200_000) { (it % 251).toByte() }     // far above the pre-handshake frame limit
            link.send(WorldRequest("w")); link.send(FileChunk("w", "level.dat", 0, big, true))
            assertEquals("w", (atMaster.poll(5, TimeUnit.SECONDS) as WorldRequest).name)
            assertContentEquals(big, (atMaster.poll(5, TimeUnit.SECONDS) as FileChunk).data)
            assertEquals("from-master", (atBackend.poll(5, TimeUnit.SECONDS) as WorldRequest).name)
        } finally { bound.close(); b.close(); m.close() }
    }

    private fun acceptor(): EmbeddedChannel {
        val reg = MessageRegistry.core()
        return EmbeddedChannel(LinkFrameDecoder(PREAUTH_FRAME), LengthFieldPrepender(4), MessageDecoder(reg), MessageEncoder(reg),
            LinkHandler(LocalNode("master", NodeRole.MASTER, "26.3"), false, 5_000, 20_000, null))
    }
    private fun frame(msg: NodeMessage): ByteBuf { val body = Unpooled.buffer(); MessageRegistry.core().encode(msg, body); return Unpooled.buffer().writeInt(body.readableBytes()).writeBytes(body) }

    @Test fun `a ping before the handshake ends the connection`() {
        val ch = acceptor()
        ch.writeInbound(frame(Ping(1, 1))); ch.runPendingTasks()
        assertFalse(ch.isOpen, "an unauthenticated peer cannot keep the connection alive with pings")
    }

    @Test fun `a large frame before the handshake ends the connection`() {
        val ch = acceptor()
        runCatching { ch.writeInbound(Unpooled.buffer().writeInt(1_000_000).writeZero(64)) }
        ch.runPendingTasks()
        assertFalse(ch.isOpen, "nothing above ${PREAUTH_FRAME} bytes is buffered for a peer that has not authenticated")
    }
}
