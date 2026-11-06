package net.fissionmc.master

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.ChatCommandTree
import net.fissionmc.master.client.PlayIds
import net.fissionmc.master.client.VarInts
import net.fissionmc.protocol.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class PlayerDataTest {
    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.repeat(5).toByteArray()) } }.toByteArray()
    private val u = UUID(1, 2)

    @Test fun `the store writes atomically, keeps the previous file and reads it back`() {
        val s = PlayerDataStore(Files.createTempDirectory("pd"))
        assertNull(s.read(u))
        assertTrue(s.write(u, gz("first"))); assertContentEquals(gz("first"), s.read(u))
        assertTrue(s.write(u, gz("second"))); assertContentEquals(gz("second"), s.read(u))
        assertContentEquals(gz("first"), Files.readAllBytes(s.dir.resolve("$u.dat_old")))
        assertEquals(listOf("$u.dat", "$u.dat_old"), Files.list(s.dir).use { l -> l.map { it.fileName.toString() }.sorted().toList() }, "no temp files left")
    }

    @Test fun `junk is refused and does not replace a good file`() {
        val s = PlayerDataStore(Files.createTempDirectory("pd"))
        s.write(u, gz("good"))
        assertFalse(s.write(u, ByteArray(100)), "not gzip"); assertFalse(s.write(u, ByteArray(3))); assertFalse(s.write(u, ByteArray(MAX_PLAYER_DATA + 1) { 0x1f }))
        assertContentEquals(gz("good"), s.read(u))
    }

    @Test fun `only the owning backend may update the data`() {
        val s = PlayerDataStore(Files.createTempDirectory("pd"))
        s.setOwner(u, "backend-b")
        assertFalse(s.onSave("backend-a", u, gz("late save of the old backend"))); assertNull(s.read(u))
        assertTrue(s.onSave("backend-b", u, gz("current"))); assertContentEquals(gz("current"), s.read(u))
        s.setOwner(u, null); assertTrue(s.onSave("backend-a", u, gz("nobody owns it (player left)")))
    }

    @Test fun `a flush waits for the next save, or times out`() {
        val s = PlayerDataStore(Files.createTempDirectory("pd")); s.setOwner(u, "b")
        val saved = CountDownLatch(1)
        s.awaitSave(u, 5000) { saved.countDown() }
        assertEquals(1, saved.count)
        s.onSave("b", u, gz("x")); assertTrue(saved.await(2, TimeUnit.SECONDS))
        val timedOut = CountDownLatch(1); s.awaitSave(UUID(9, 9), 100) { timedOut.countDown() }
        assertTrue(timedOut.await(3, TimeUnit.SECONDS), "no save: carry on after the timeout")
    }

    @Test fun `player data messages survive the wire`() {
        val reg = MessageRegistry.core().registerPlayerMessages()
        val data = gz("player")
        val d = Unpooled.buffer().also { reg.encode(PlayerData(7, 1, 2, true, data), it) }.let { reg.decode(it) as PlayerData }
        assertEquals(7, d.playerId); assertTrue(d.present); assertContentEquals(data, d.nbt)
        val none = Unpooled.buffer().also { reg.encode(PlayerData(8, 1, 2, false, ByteArray(0)), it) }.let { reg.decode(it) as PlayerData }
        assertFalse(none.present); assertEquals(0, none.nbt.size)
        val sv = Unpooled.buffer().also { reg.encode(PlayerDataSave(1, 2, data), it) }.let { reg.decode(it) as PlayerDataSave }
        assertEquals(1, sv.uuidHigh); assertContentEquals(data, sv.nbt)
        val b = Unpooled.buffer(); reg.encode(PlayerDataFlush(5), b); assertEquals(PlayerDataFlush(5), reg.decode(b))
    }

    @Test fun `attach sends the stored data first and records the owner, a save is stored, a flush asks the backend`() {
        class Link : net.fissionmc.net.NodeLink {
            val sent = mutableListOf<NodeMessage>()
            override val ready = java.util.concurrent.CompletableFuture<net.fissionmc.net.PeerInfo>(); override val closed = java.util.concurrent.CompletableFuture<String>()
            override val latencyNanos = -1L; @Volatile override var isOpen = true; override val isWritable = true
            override var handler: (NodeMessage) -> Unit = {}
            override fun send(msg: NodeMessage) { sent += msg }
            override fun close(reason: String) { isOpen = false }
        }
        val reg = NodeRegistry(); val link = Link()
        reg.register(net.fissionmc.net.PeerInfo("b1", NodeRole.BACKEND, 1, MC_VERSION), link)
        val relay = PlayerRelay(reg); val store = PlayerDataStore(Files.createTempDirectory("pd")); relay.playerData = store
        val client = object : RelayClient {
            override val profile = net.fissionmc.master.client.PlayerProfile(u, "Steve"); override val protocol = 777
            override fun deliver(frame: ByteArray) {}; override fun detached(reason: String, backendLost: Boolean) {}
        }
        val id1 = relay.attach(client)!!
        val first = link.sent.first { it is PlayerData } as PlayerData
        assertFalse(first.present, "first join on the network: nothing stored")
        assertTrue(link.sent.indexOfFirst { it is PlayerData } < link.sent.indexOfFirst { it is PlayerAttach }, "data before attach")
        assertEquals("b1", store.owner(u))
        relay.handle(reg["b1"]!!, PlayerDataSave(u.mostSignificantBits, u.leastSignificantBits, gz("saved")))
        assertContentEquals(gz("saved"), store.read(u))
        val id2 = relay.attach(client)!!
        val second = link.sent.filterIsInstance<PlayerData>().last()
        assertTrue(second.present); assertContentEquals(gz("saved"), second.nbt); assertEquals(id2, second.playerId)
        var flushed = false
        relay.flushPlayerData(id2, u, 5000) { flushed = true }
        assertTrue(link.sent.last() is PlayerDataFlush); assertFalse(flushed)
        relay.handle(reg["b1"]!!, PlayerDataSave(u.mostSignificantBits, u.leastSignificantBits, gz("after flush")))
        assertTrue(flushed); assertNotEquals(id1, id2)
    }

    // ---- the admin part of the command tree
    private fun treeFrame(): ByteArray {
        val b = Unpooled.buffer(); VarInts.write(b, PlayIds.commands); VarInts.write(b, 2)
        b.writeByte(0); VarInts.write(b, 1); VarInts.write(b, 1)
        b.writeByte(1 or 4); VarInts.write(b, 0); VarInts.write(b, 1); b.writeByte('a'.code)
        b.writeByte(0)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }

    @Test fun `admins get fission send with two ask_server string arguments, others only chat`() {
        val admin = ChatCommandTree.patch(treeFrame(), admin = true)!!
        val plain = ChatCommandTree.patch(treeFrame(), admin = false)!!
        val text = String(admin, Charsets.ISO_8859_1)
        for (w in listOf("fission", "send", "status", "player", "backend", "minecraft:ask_server", "chat")) assertTrue(text.contains(w), w)
        val p = String(plain, Charsets.ISO_8859_1)
        assertTrue(p.contains("chat") && !p.contains("fission") && !p.contains("ask_server"))
        // node count: 2 + chat(4) + fission(3) + two arguments(2) + admin add/remove (5) for admins
        assertEquals(2 + 14, Unpooled.wrappedBuffer(admin).also { VarInts.tryRead(it) }.let { VarInts.tryRead(it)!! })
        assertEquals(0, admin.last().toInt())
    }

    @Test fun `suggestions answer player names then backends for fission send`() {
        val relayReg = NodeRegistry()
        relayReg.register(net.fissionmc.net.PeerInfo("backend-a", NodeRole.BACKEND, 1, MC_VERSION), object : net.fissionmc.net.NodeLink {
            override val ready = java.util.concurrent.CompletableFuture<net.fissionmc.net.PeerInfo>(); override val closed = java.util.concurrent.CompletableFuture<String>()
            override val latencyNanos = -1L; override val isOpen = true; override val isWritable = true
            override var handler: (NodeMessage) -> Unit = {}; override fun send(msg: NodeMessage) {}; override fun close(reason: String) {}
        })
        val server = net.fissionmc.master.client.ClientServer(net.fissionmc.master.client.ClientConfig(host = "127.0.0.1", port = 0, onlineMode = false), relay = PlayerRelay(relayReg), log = {})
        fun req(text: String): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, PlayIds.commandSuggestion); VarInts.write(b, 77); val t = text.toByteArray(); VarInts.write(b, t.size); b.writeBytes(t); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
        fun parse(r: ByteArray): Triple<Int, Int, List<String>> {
            val b = Unpooled.wrappedBuffer(r); VarInts.tryRead(b); assertEquals(77, VarInts.tryRead(b)); val st = VarInts.tryRead(b)!!; val len = VarInts.tryRead(b)!!
            val l = List(VarInts.tryRead(b)!!) { val n = VarInts.tryRead(b)!!; ByteArray(n).also { x -> b.readBytes(x) }.toString(Charsets.UTF_8).also { assertFalse(b.readBoolean()) } }
            return Triple(st, len, l)
        }
        val backends = parse(net.fissionmc.master.client.MasterCommands.suggest(req("/fission send Steve back"), server)!!)
        assertEquals(Triple("/fission send Steve ".length, 4, listOf("backend-a")), backends)
        val none = parse(net.fissionmc.master.client.MasterCommands.suggest(req("/fission send Zed"), server)!!)
        assertEquals("/fission send ".length, none.first); assertEquals(3, none.second); assertEquals(emptyList(), none.third)
        assertNull(net.fissionmc.master.client.MasterCommands.suggest(req("/gamemode c"), server), "other commands go to the backend")
        server.close()
    }
}
