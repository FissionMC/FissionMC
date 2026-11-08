package net.fissionmc.master

import net.fissionmc.master.client.ClientConfig
import net.fissionmc.master.client.ClientServer
import net.fissionmc.net.LocalNode
import net.fissionmc.net.NettyTransport
import net.fissionmc.net.NodeAddress
import net.fissionmc.protocol.NodeRole
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One TCP port for players and backends: the first byte tells a node link (0) from a Minecraft handshake (the packet length, never 0). */
class PortSharingTest {
    private fun varint(v: Int, o: ByteArrayOutputStream) { var x = v; while (x and 0x7F.inv() != 0) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }; o.write(x) }

    @Test fun nodeLinksAndPlayersShareOnePort() {
        val transport = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val master = MasterServer(transport, NodeAddress.Tcp("127.0.0.1", 0), Files.createTempDirectory("ps-world")).start(listen = false)
        val joined = java.util.concurrent.CountDownLatch(1)
        master.registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { if (node.info.nodeId == "be-shared") joined.countDown() } })
        val clients = ClientServer(ClientConfig(host = "127.0.0.1", port = 0, motd = "shared-port-test", onlineMode = false), nodeAdopt = master::adopt).start()
        val backend = NettyTransport(LocalNode("be-shared", NodeRole.BACKEND, "26.3"))
        try {
            // 1) a backend dials the players' port and is registered like on its own port
            val link = backend.connect(NodeAddress.Tcp("127.0.0.1", clients.port)).get(10, TimeUnit.SECONDS)
            assertEquals("master", link.ready.get(10, TimeUnit.SECONDS).nodeId)
            assertTrue(joined.await(10, TimeUnit.SECONDS), "master did not register the backend")
            // 2) a Minecraft client's status ping on the same port still gets the server list answer
            Socket("127.0.0.1", clients.port).use { s ->
                s.soTimeout = 10_000
                val hs = ByteArrayOutputStream().also { varint(0, it); varint(777, it); val h = "localhost".toByteArray(); varint(h.size, it); it.write(h); it.write(0x63); it.write(0xDD); varint(1, it) }.toByteArray()
                val out = ByteArrayOutputStream().also { varint(hs.size, it); it.write(hs); varint(1, it); it.write(0) }.toByteArray()
                s.getOutputStream().write(out); s.getOutputStream().flush()
                val buf = ByteArray(4096); var n = 0
                while (n < buf.size) { val r = s.getInputStream().read(buf, n, buf.size - n); if (r < 0) break; n += r; if (String(buf, 0, n).contains("shared-port-test")) break }
                assertTrue(String(buf, 0, n).contains("shared-port-test"), "no status answer on the shared port: ${String(buf, 0, n)}")
            }
        } finally { backend.close(); clients.close(); master.close(); transport.close() }
    }
}
