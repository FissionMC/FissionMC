package net.fissionmc.master

import io.netty.buffer.Unpooled
import net.fissionmc.master.chunk.*
import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ChunkAuthorityTest {
    private val key = ChunkKey("minecraft:overworld", ChunkKind.CHUNK, 3, -7)
    private fun blob(s: String) = ChunkImport.deflate(s.toByteArray())
    private fun store() = FileChunkStore(Files.createTempDirectory("chunks"))

    @Test fun `first write wins an empty slot and versions only grow`() {
        val s = store()
        assertNull(s.get(key))
        val a = s.put(key, 5, 0, blob("a"), "a"); assertTrue(a.accepted)
        val b = s.put(key, 9, a.version, blob("b"), "a"); assertTrue(b.accepted); assertTrue(b.version > a.version)
        assertContentEquals(blob("b"), s.get(key)!!.bytes); assertEquals(9, s.get(key)!!.status)
    }

    @Test fun `a lower status is refused, a higher one wins whatever it saw`() {
        val s = store()
        s.put(key, 9, 0, blob("full"), "a")
        assertFalse(s.put(key, 4, 0, blob("partial"), "a").accepted)
        val s2 = store(); s2.put(key, 4, 0, blob("partial"), "a")
        assertTrue(s2.put(key, 9, 0, blob("full"), "b").accepted, "a status upgrade needs no matching base version")
        assertContentEquals(blob("full"), s2.get(key)!!.bytes)
    }

    @Test fun `an equal status update needs the version the sender loaded (compare and set)`() {
        val s = store(); val v1 = s.put(key, 9, 0, blob("v1"), "a").version
        assertFalse(s.put(key, 9, 0, blob("stale writer"), "b").accepted, "generated elsewhere without seeing v1: refused")
        assertEquals(v1, s.put(key, 9, 0, blob("stale"), "b").version, "the refusal reports the stored version")
        val ok = s.put(key, 9, v1, blob("edited"), "b"); assertTrue(ok.accepted)
        assertFalse(s.put(key, 9, v1, blob("second editor on v1"), "c").accepted)
        assertTrue(s.put(key, 9, v1, blob("b again, base not yet acknowledged"), "b").accepted, "the same backend may chain saves")
        assertContentEquals(blob("b again, base not yet acknowledged"), s.get(key)!!.bytes)
    }

    @Test fun `import keeps the first of equal status and takes the higher`() {
        val s = store()
        assertTrue(s.putIfBetter(key, 9, blob("a")).accepted); assertFalse(s.putIfBetter(key, 9, blob("b")).accepted)
        assertFalse(s.putIfBetter(key, 3, blob("c")).accepted); assertContentEquals(blob("a"), s.get(key)!!.bytes)
    }

    @Test fun `files are written atomically and the version counter survives a restart`() {
        val dir = Files.createTempDirectory("chunks"); val s = FileChunkStore(dir)
        val v = s.put(key, 9, 0, blob("x"), "a").version
        assertTrue(Files.walk(dir).use { w -> w.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        val again = FileChunkStore(dir)
        assertContentEquals(blob("x"), again.get(key)!!.bytes)
        assertTrue(again.put(key, 9, v, blob("y"), "a").version > v, "versions never repeat after a restart")
        assertEquals(1, again.count())
    }

    @Test fun `corrupt files read as absent`() {
        val dir = Files.createTempDirectory("chunks"); val s = FileChunkStore(dir)
        s.put(key, 9, 0, blob("x"), "a")
        Files.walk(dir).use { w -> w.filter { it.fileName.toString().endsWith(".fch") }.forEach { Files.write(it, byteArrayOf(1, 2, 3)) } }
        assertNull(s.get(key))
    }

    // ---- NBT status scan
    private fun nbt(status: String?, withNoise: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream(); val d = DataOutputStream(o)
        d.writeByte(10); d.writeUTF("")
        if (withNoise) { d.writeByte(3); d.writeUTF("DataVersion"); d.writeInt(5000)
            d.writeByte(9); d.writeUTF("sections"); d.writeByte(10); d.writeInt(1); d.writeByte(1); d.writeUTF("Y"); d.writeByte(4); d.writeByte(0)
            d.writeByte(12); d.writeUTF("longs"); d.writeInt(2); d.writeLong(1); d.writeLong(2)
            d.writeByte(10); d.writeUTF("nested"); d.writeByte(8); d.writeUTF("Status"); d.writeUTF("minecraft:noise-decoy"); d.writeByte(0) }
        if (status != null) { d.writeByte(8); d.writeUTF("Status"); d.writeUTF(status) }
        d.writeByte(0); return o.toByteArray()
    }

    @Test fun `the status scan finds only the top level Status and skips every tag type`() {
        assertEquals("minecraft:features", NbtScan.statusOf(nbt("minecraft:features")))
        assertNull(NbtScan.statusOf(nbt(null))); assertNull(NbtScan.statusOf(byteArrayOf(1, 2, 3)))
        assertEquals(5, ChunkStatusRank.of("minecraft:features")); assertEquals(ChunkStatusRank.FULL, ChunkStatusRank.of("full")); assertEquals(0, ChunkStatusRank.of("nonsense"))
    }

    // ---- import of a region file
    private fun writeRegion(file: java.nio.file.Path, slot: Int, raw: ByteArray, type: Int = 2) {
        Files.createDirectories(file.parent)
        val payload = if (type == 2) ChunkImport.deflate(raw) else raw
        RandomAccessFile(file.toFile(), "rw").use { r ->
            val header = ByteArray(8192); header[slot * 4 + 2] = 2; header[slot * 4 + 3] = 1   // sector 2, one sector
            r.write(header); r.seek(2 * 4096L); r.writeInt(payload.size + 1); r.writeByte(type); r.write(payload); r.setLength(3 * 4096L)
        }
    }

    @Test fun `import reads a region slot with its status and keeps the better one on a second import`() {
        val world = Files.createTempDirectory("world")
        writeRegion(world.resolve("dimensions/minecraft/overworld/region/r.1.-1.mca"), 33, nbt("minecraft:full"))   // slot 33 = local x 1, z 1
        writeRegion(world.resolve("dimensions/minecraft/overworld/poi/r.1.-1.mca"), 33, nbt(null, false))
        writeRegion(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), 0, nbt("minecraft:full"), type = 4)
        val s = store(); val r = ChunkImport.import(world, s)
        assertEquals(2, r.imported); assertEquals(1, r.unsupported)
        val k = ChunkKey("minecraft:overworld", ChunkKind.CHUNK, 32 + 1, -32 + 1)
        assertEquals(ChunkStatusRank.FULL, s.get(k)!!.status)
        assertNotNull(s.get(k.copy(kind = ChunkKind.POI)))
        val world2 = Files.createTempDirectory("world2")
        writeRegion(world2.resolve("region/r.1.-1.mca"), 33, nbt("minecraft:features"))      // legacy layout, lower status
        assertEquals(0, ChunkImport.import(world2, s).imported)
        assertEquals(ChunkStatusRank.FULL, s.get(k)!!.status)
    }

    // ---- authority: leases
    private class Link(val id: String) : NodeLink {
        val sent = java.util.concurrent.LinkedBlockingQueue<NodeMessage>()
        override val ready = CompletableFuture<PeerInfo>(); override val closed = CompletableFuture<String>(); override val latencyNanos = -1L
        @Volatile override var isOpen = true; override val isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        override fun send(msg: NodeMessage) { sent.add(msg) }
        override fun close(reason: String) { isOpen = false }
        fun next(): NodeMessage = sent.poll(5, TimeUnit.SECONDS) ?: error("no message")
        /** The next message that is not a broadcast notice. */
        fun nextReal(): NodeMessage { while (true) { val m = next(); if (m !is ChunkAvailable) return m } }
    }

    private fun rig(leaseMs: Long = 30_000, deterministic: (String) -> Boolean = { false }): Triple<ChunkAuthority, NodeRegistry, Map<String, Link>> {
        val reg = NodeRegistry(); val links = listOf("a", "b", "c").associateWith { Link(it) }
        links.forEach { (id, l) -> reg.register(PeerInfo(id, NodeRole.BACKEND, 1, "26.3"), l) }
        return Triple(ChunkAuthority(store(), reg, Executors.newSingleThreadScheduledExecutor { Thread(it).also { t -> t.isDaemon = true } }, {}, leaseMs, deterministic), reg, links)
    }

    @Test fun `the first requester gets the lease, the second waits and is served by the push`() {
        val (auth, reg, links) = rig()
        auth.handle(reg["a"]!!, ChunkRequest(1, "minecraft:overworld", 0, 5, 5))
        assertEquals(ChunkAbsent(1, true), links["a"]!!.next())
        auth.handle(reg["b"]!!, ChunkRequest(7, "minecraft:overworld", 0, 5, 5))
        assertTrue(links["b"]!!.sent.isEmpty(), "b waits for a's push")
        assertEquals(1, auth.parked.get())
        auth.handle(reg["a"]!!, ChunkPush("minecraft:overworld", 0, 5, 5, ChunkStatusRank.FULL, 0, blob("generated by a")))
        val stored = links["a"]!!.next() as ChunkStored; assertTrue(stored.accepted)
        val data = links["b"]!!.nextReal() as ChunkData
        assertEquals(7, data.reqId); assertContentEquals(blob("generated by a"), data.bytes); assertEquals(stored.version, data.version)
        assertTrue(links["c"]!!.next() is ChunkAvailable, "the other backends hear about it")
        assertEquals(1L, auth.leasesGranted.get(), "generated exactly once")
    }

    @Test fun `a lease that runs out passes to the next waiting backend`() {
        val (auth, reg, links) = rig(leaseMs = 150)
        auth.handle(reg["a"]!!, ChunkRequest(1, "minecraft:overworld", 0, 1, 1)); assertEquals(ChunkAbsent(1, true), links["a"]!!.next())
        auth.handle(reg["b"]!!, ChunkRequest(2, "minecraft:overworld", 0, 1, 1))
        assertEquals(ChunkAbsent(2, true), links["b"]!!.next(), "a never pushed: b may generate")
        assertEquals(2L, auth.leasesGranted.get()); assertEquals(1L, auth.leaseExpired.get())
    }

    @Test fun `a holder that keeps pushing keeps its leases, a holder that left loses them`() {
        val (auth, reg, links) = rig(leaseMs = 400)
        auth.handle(reg["a"]!!, ChunkRequest(1, "minecraft:overworld", 0, 1, 1)); assertEquals(ChunkAbsent(1, true), links["a"]!!.next())
        auth.handle(reg["b"]!!, ChunkRequest(2, "minecraft:overworld", 0, 1, 1))
        repeat(4) { i -> Thread.sleep(250); auth.handle(reg["a"]!!, ChunkPush("minecraft:overworld", 0, 50 + i, 50, 3, 0, blob("progress $i"))) }   // a is busy elsewhere
        assertTrue(links["b"]!!.sent.none { it is ChunkAbsent }, "a made progress: its lease on 1,1 stands (1 s > 400 ms lease)")
        reg.register(PeerInfo("a", NodeRole.BACKEND, 1, "26.3"), Link("a2"))   // a reconnects: the old link closes
        links["a"]!!.close("gone"); auth.listener.left(reg["a"]!!, "gone")
        assertEquals(ChunkAbsent(2, true), links["b"]!!.sent.first { it is ChunkAbsent }, "a left: b may generate")
    }

    @Test fun `entities and poi never need a lease, deterministic worlds neither`() {
        val (auth, reg, links) = rig(deterministic = { it == "minecraft:void" })
        auth.handle(reg["a"]!!, ChunkRequest(1, "minecraft:overworld", ChunkKind.ENTITIES, 0, 0)); assertEquals(ChunkAbsent(1, false), links["a"]!!.next())
        auth.handle(reg["a"]!!, ChunkRequest(2, "minecraft:void", ChunkKind.CHUNK, 0, 0)); assertEquals(ChunkAbsent(2, false), links["a"]!!.next())
        assertEquals(0L, auth.leasesGranted.get())
    }

    @Test fun `a peek never takes a lease or waits`() {
        val (auth, reg, links) = rig()
        auth.handle(reg["a"]!!, ChunkRequest(1, "minecraft:overworld", 0, 8, 8, peek = true)); assertEquals(ChunkAbsent(1, false), links["a"]!!.next())
        assertEquals(0L, auth.leasesGranted.get())
        auth.handle(reg["b"]!!, ChunkRequest(2, "minecraft:overworld", 0, 8, 8)); assertEquals(ChunkAbsent(2, true), links["b"]!!.next(), "the peek left nothing behind")
        auth.handle(reg["b"]!!, ChunkPush("minecraft:overworld", 0, 8, 8, 9, 0, blob("x"))); links["b"]!!.nextReal()
        auth.handle(reg["a"]!!, ChunkRequest(3, "minecraft:overworld", 0, 8, 8, peek = true)); assertTrue(links["a"]!!.nextReal() is ChunkData)
    }

    @Test fun `a rejected push is answered and not broadcast`() {
        val (auth, reg, links) = rig()
        auth.handle(reg["a"]!!, ChunkPush("minecraft:overworld", 0, 2, 2, ChunkStatusRank.FULL, 0, blob("one"))); links["a"]!!.next()
        auth.handle(reg["b"]!!, ChunkPush("minecraft:overworld", 0, 2, 2, ChunkStatusRank.FULL, 0, blob("two")))
        val r = links["b"]!!.nextReal() as ChunkStored; assertFalse(r.accepted)
        assertEquals(1L, auth.pushesRejected.get())
        assertTrue(links["c"]!!.next() is ChunkAvailable); assertTrue(links["c"]!!.sent.isEmpty(), "only the accepted push was announced")
    }

    @Test fun `chunk messages survive the wire`() {
        val reg = MessageRegistry.core().registerChunkMessages(); val bytes = blob("payload")
        fun <T : NodeMessage> rt(m: T): NodeMessage { val b = Unpooled.buffer(); reg.encode(m, b); return reg.decode(b).also { assertEquals(0, b.readableBytes()) } }
        assertEquals(ChunkRequest(5, "minecraft:the_nether", 2, -1, 30, true), rt(ChunkRequest(5, "minecraft:the_nether", 2, -1, 30, true)))
        val d = rt(ChunkData(5, 9, 77, bytes)) as ChunkData; assertEquals(77, d.version); assertContentEquals(bytes, d.bytes)
        assertEquals(ChunkAbsent(5, true), rt(ChunkAbsent(5, true)))
        val p = rt(ChunkPush("minecraft:overworld", 0, 100000, -100000, 9, 3, bytes)) as ChunkPush; assertEquals(-100000, p.z); assertEquals(3, p.baseVersion); assertContentEquals(bytes, p.bytes)
        assertEquals(ChunkStored("minecraft:overworld", 1, 1, 2, false, 9), rt(ChunkStored("minecraft:overworld", 1, 1, 2, false, 9)))
        assertEquals(ChunkAvailable("minecraft:overworld", 0, 1, 2, 9), rt(ChunkAvailable("minecraft:overworld", 0, 1, 2, 9)))
    }
}
