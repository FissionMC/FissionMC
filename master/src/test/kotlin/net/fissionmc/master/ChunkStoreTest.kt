package net.fissionmc.master

import net.fissionmc.master.chunk.ChunkKey
import net.fissionmc.master.chunk.FileChunkStore
import java.nio.file.Files
import kotlin.test.*

class ChunkStoreTest {
    private val key = ChunkKey("minecraft:overworld", 0, 1, 2)

    @Test fun `a damaged chunk file is moved aside, not overwritten`() {
        val dir = Files.createTempDirectory("chunks"); val msgs = mutableListOf<String>()
        val store = FileChunkStore(dir) { msgs += it }
        assertTrue(store.put(key, 5, 0, ByteArray(100) { it.toByte() }, "a").accepted)
        val f = Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".fch") }.toList() }.single()
        Files.write(f, Files.readAllBytes(f).copyOf(40))               // truncated
        assertNull(store.get(key)); assertEquals(1L, store.quarantined)
        assertFalse(Files.exists(f)); assertEquals(1, Files.list(f.parent).use { s -> s.filter { it.fileName.toString().contains(".bad-") }.count() }.toInt())
        assertTrue(msgs.any { it.contains("damaged") })
        assertTrue(store.put(key, 5, 0, ByteArray(10), "a").accepted)     // the chunk is stored again; the bad file is still there
        assertNotNull(store.get(key))
    }

    @Test fun `a corrupt length does not allocate and is quarantined`() {
        val dir = Files.createTempDirectory("chunks"); val store = FileChunkStore(dir) { }
        store.put(key, 5, 0, ByteArray(10), "a")
        val f = Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".fch") }.toList() }.single()
        val b = Files.readAllBytes(f); b[16] = 0x7f; b[17] = 0x7f   // the length int (offset 4+4+8)
        Files.write(f, b)
        assertNull(store.get(key)); assertEquals(1L, store.quarantined)
    }

    @Test fun `parallel pushes and reads of different chunks never see a torn blob and every version is unique`() {
        val dir = Files.createTempDirectory("chunks"); val store = FileChunkStore(dir)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8); val versions = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>(); val torn = java.util.concurrent.atomic.AtomicInteger()
        val jobs = (0 until 400).map { i -> pool.submit {
            val k = ChunkKey("minecraft:overworld", 0, i % 40, i / 40)
            val body = ByteArray(2000) { (i % 100).toByte() }
            val r = store.put(k, 5, 0, body, "a"); if (r.accepted) versions += r.version
            val got = store.get(k); if (got != null && !got.bytes.contentEquals(body)) torn.incrementAndGet()
        } }
        jobs.forEach { it.get() }; pool.shutdown()
        assertEquals(0, torn.get()); assertEquals(400, versions.size); assertEquals(400L, store.count())
    }
}
