package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.net.world.WorldPuller
import net.fissionmc.protocol.NodeRole
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MasterServerTest {
    @Test fun registersBackendsAndServesWorld() {
        val root = Files.createTempDirectory("m-root"); Files.createDirectories(root.resolve("world"))
        Files.writeString(root.resolve("world/level.dat"), "hello")
        val a = NodeAddress.Local("m-" + System.nanoTime())
        val mt = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val master = MasterServer(mt, a, root).start()
        val joined = java.util.concurrent.LinkedBlockingQueue<String>(); val left = java.util.concurrent.LinkedBlockingQueue<String>()
        master.registry.addListener(object : NodeRegistry.Listener {
            override fun joined(node: RegisteredNode) { joined.add(node.info.nodeId) }
            override fun left(node: RegisteredNode, reason: String) { left.add(node.info.nodeId) }
        })
        val bt = NettyTransport(LocalNode("be-1", NodeRole.BACKEND, "26.3"))
        try {
            val link = bt.connect(a).get(5, TimeUnit.SECONDS); link.ready.get(5, TimeUnit.SECONDS)
            assertEquals("be-1", joined.poll(5, TimeUnit.SECONDS))
            assertNotNull(master.registry["be-1"])
            val dest = Files.createTempDirectory("m-dest")
            val p = WorldPuller(link, dest); link.handler = { p.handle(it) }
            assertEquals(1, p.pull("world").get(10, TimeUnit.SECONDS).downloaded)
            assertEquals("hello", Files.readString(dest.resolve("level.dat")))
            link.close("bye")
            assertEquals("be-1", left.poll(5, TimeUnit.SECONDS))
            assertNull(master.registry["be-1"])
        } finally { master.close(); bt.close(); mt.close() }
    }
}
