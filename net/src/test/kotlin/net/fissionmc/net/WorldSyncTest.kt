package net.fissionmc.net

import net.fissionmc.net.world.*
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorldSyncTest {
    @Test fun pullsDiffsAndDeletes() {
        val root = Files.createTempDirectory("ws-root"); val dest = Files.createTempDirectory("ws-dest")
        val w = root.resolve("world")
        Files.createDirectories(w.resolve("region"))
        Files.write(w.resolve("level.dat"), ByteArray(100) { it.toByte() })
        Files.write(w.resolve("region/r.0.0.mca"), ByteArray(FILE_CHUNK_SIZE * 2 + 17) { (it * 7).toByte() }) // multi-chunk
        Files.write(w.resolve("region/exact.mca"), ByteArray(FILE_CHUNK_SIZE)) // exact chunk multiple
        Files.write(w.resolve("empty"), ByteArray(0))
        Files.write(w.resolve("session.lock"), byteArrayOf(1))
        // dest: one identical file, one stale, one extra
        Files.write(dest.resolve("level.dat"), ByteArray(100) { it.toByte() })
        Files.createDirectories(dest.resolve("region"))
        Files.write(dest.resolve("region/r.0.0.mca"), byteArrayOf(9))
        Files.write(dest.resolve("junk.txt"), byteArrayOf(1))

        val a = NodeAddress.Local("w-" + System.nanoTime())
        val m = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3")); val b = NettyTransport(LocalNode("be", NodeRole.BACKEND, "26.3"))
        val server = WorldServer(root)
        val bound = m.bind(a) { l -> l.handler = { server.handle(l, it) } }
        try {
            val link = b.connect(a).get(5, TimeUnit.SECONDS); link.ready.get(5, TimeUnit.SECONDS)
            val puller = WorldPuller(link, dest); link.handler = { puller.handle(it) }
            val r = puller.pull("world").get(20, TimeUnit.SECONDS)
            assertEquals(1, r.unchanged); assertEquals(3, r.downloaded); assertEquals(1, r.deleted)
            for (rel in WorldFiles.list(w)) assertContentEquals(Files.readAllBytes(w.resolve(rel)), Files.readAllBytes(dest.resolve(rel)), rel)
            assertEquals(WorldFiles.list(w), WorldFiles.list(dest))
            // second pull is a no-op
            val r2 = WorldPuller(link, dest).also { p -> link.handler = { p.handle(it) } }.pull("world").get(20, TimeUnit.SECONDS)
            assertEquals(0, r2.downloaded); assertEquals(4, r2.unchanged)
        } finally { bound.close(); server.close(); b.close(); m.close() }
    }

    @Test fun unknownWorldReportsError() {
        val root = Files.createTempDirectory("ws-root")
        val a = NodeAddress.Local("w-" + System.nanoTime())
        val m = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3")); val b = NettyTransport(LocalNode("be", NodeRole.BACKEND, "26.3"))
        val server = WorldServer(root)
        val bound = m.bind(a) { l -> l.handler = { server.handle(l, it) } }
        try {
            val link = b.connect(a).get(5, TimeUnit.SECONDS); link.ready.get(5, TimeUnit.SECONDS)
            val puller = WorldPuller(link, Files.createTempDirectory("d")); link.handler = { puller.handle(it) }
            val ex = assertFailsWith<java.util.concurrent.ExecutionException> { puller.pull("nope").get(5, TimeUnit.SECONDS) }
            assertTrue(ex.cause!!.message!!.contains("no such world"))
        } finally { bound.close(); server.close(); b.close(); m.close() }
    }

    @Test fun pathChecks() {
        for (bad in listOf("", "/etc/passwd", "../x", "a/../../x", "a//b", "a\\b", "C:/x")) assertFalse(WorldFiles.safeRelative(bad), bad)
        assertTrue(WorldFiles.safeRelative("region/r.0.0.mca"))
    }

    @Test fun `player files are local only at any depth, other world files are not`() {
        for (local in listOf("players/data/1234.dat", "players/stats/1234.json", "players/advancements/1234.json", "players/data/x/y.dat", "playerdata/1.dat", "stats/1.json", "advancements/1.json", "session.lock", "a/b/session.lock"))
            assertTrue(WorldFiles.isLocalOnly(local), local)
        for (shared in listOf("level.dat", "data/minecraft/scoreboard.dat", "dimensions/minecraft/overworld/region/r.0.0.mca", "datapacks/x/pack.mcmeta", "playersX/data.dat", "my/players/data/1.dat"))
            assertFalse(WorldFiles.isLocalOnly(shared), shared)
    }

    @Test fun `a pull keeps the backend's own player files and the master never lists or serves them`() {
        val tmp = Files.createTempDirectory("wsync-local")
        val master = tmp.resolve("master"); val dest = tmp.resolve("dest")
        Files.createDirectories(master.resolve("players/data")); Files.writeString(master.resolve("players/data/m.dat"), "master copy")
        Files.writeString(master.resolve("level.dat"), "lvl")
        Files.createDirectories(dest.resolve("players/data")); Files.createDirectories(dest.resolve("players/stats"))
        Files.writeString(dest.resolve("players/data/local.dat"), "local player"); Files.writeString(dest.resolve("players/stats/local.json"), "{}")
        Files.writeString(dest.resolve("stale.txt"), "gone after the pull")
        assertEquals(listOf("level.dat"), WorldFiles.list(master), "the master manifest has no player files")
        assertEquals(listOf("stale.txt"), WorldFiles.list(dest))
        assertTrue(Files.exists(dest.resolve("players/data/local.dat")))
    }
}
