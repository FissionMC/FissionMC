package net.fissionmc.master

import net.fissionmc.net.LocalNode
import net.fissionmc.net.NettyTransport
import net.fissionmc.net.NodeAddress
import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** A master without a world: the alphabetically first backend seeds it, the others wait, nobody else can write. */
class WorldSeedingTest {
    private class Backend(val id: String, addr: NodeAddress) {
        val t = NettyTransport(LocalNode(id, NodeRole.BACKEND, "26.3"))
        val inbox = LinkedBlockingQueue<NodeMessage>()
        val link: NodeLink = t.connect(addr).get(10, TimeUnit.SECONDS).also { l -> l.handler = { inbox.add(it) }; l.ready.get(10, TimeUnit.SECONDS) }
        inline fun <reified T : NodeMessage> next(): T { while (true) { val m = inbox.poll(10, TimeUnit.SECONDS) ?: error("no ${T::class.simpleName} for $id"); if (m is T) return m } }
    }

    private fun file(l: NodeLink, world: String, path: String, text: String) = l.send(FileChunk(world, path, 0, text.toByteArray(), true))

    @Test fun firstBackendAlphabeticallySeedsAndOthersGetTheWorld() {
        val root = Files.createTempDirectory("seed-root")
        val addr = NodeAddress.Local("seed-" + System.nanoTime())
        val mt = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val master = MasterServer(mt, addr, root).start()
        val b = Backend("backend-b", addr); val a = Backend("backend-a", addr)
        try {
            // wait until the master has registered both (the handshake's Welcome precedes the registration)
            while (master.registry.all.size < 2) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000)
            b.link.send(WorldRequest("world")); a.link.send(WorldRequest("world"))
            assertEquals("backend-a", b.next<WorldMissing>().seeder, "b is told to wait for a")
            assertEquals("backend-a", a.next<WorldMissing>().seeder, "a is the seeder")
            // the seeder uploads; a stranger's upload is refused
            file(b.link, "world", "level.dat", "evil"); assertFalse(b.next<WorldSeeded>().ok)
            file(a.link, "world", "level.dat", "LEVEL"); file(a.link, "world", "data/minecraft/world_gen_settings.dat", "SEED")
            a.link.send(WorldSeedDone("world", 2))
            assertTrue(a.next<WorldSeeded>().ok); assertTrue(b.next<WorldSeeded>().ok)
            assertEquals("LEVEL", Files.readString(root.resolve("world/level.dat")))
            assertEquals("SEED", Files.readString(root.resolve("world/data/minecraft/world_gen_settings.dat")))
            assertFalse(Files.exists(root.resolve("world.seeding")))
            // from now on the world is served like any other
            b.link.send(WorldRequest("world"))
            assertEquals(setOf("level.dat", "data/minecraft/world_gen_settings.dat"), b.next<WorldManifest>().files.map { it.path }.toSet())
        } finally { a.t.close(); b.t.close(); master.close(); mt.close() }
    }

    @Test fun seederThatLeavesHandsTheJobOver() {
        val root = Files.createTempDirectory("seed-root2")
        val addr = NodeAddress.Local("seed2-" + System.nanoTime())
        val mt = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val master = MasterServer(mt, addr, root).start()
        val a = Backend("backend-a", addr); val b = Backend("backend-b", addr)
        try {
            while (master.registry.all.size < 2) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000)
            a.link.send(WorldRequest("world")); assertEquals("backend-a", a.next<WorldMissing>().seeder)
            a.link.close("gone"); a.t.close()
            while (master.registry["backend-a"] != null) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000)
            b.link.send(WorldRequest("world")); assertEquals("backend-b", b.next<WorldMissing>().seeder)
        } finally { a.t.close(); b.t.close(); master.close(); mt.close() }
    }

    @Test fun uploadWithoutLevelDatOrWithBadPathsIsRefused() {
        val root = Files.createTempDirectory("seed-root3")
        val addr = NodeAddress.Local("seed3-" + System.nanoTime())
        val mt = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val master = MasterServer(mt, addr, root).start()
        val a = Backend("backend-a", addr)
        try {
            while (master.registry.all.isEmpty()) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000)
            a.link.send(WorldRequest("world")); a.next<WorldMissing>()
            file(a.link, "world", "../escape.txt", "x"); assertFalse(a.next<WorldSeeded>().ok)
            a.link.send(WorldRequest("world")); a.next<WorldMissing>()
            file(a.link, "world", "data/x.dat", "x"); a.link.send(WorldSeedDone("world", 1))
            assertFalse(a.next<WorldSeeded>().ok, "no level.dat")
            assertFalse(Files.exists(root.resolve("world")))
        } finally { a.t.close(); master.close(); mt.close() }
    }
}
