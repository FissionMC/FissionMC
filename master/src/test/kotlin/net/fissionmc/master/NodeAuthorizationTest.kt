package net.fissionmc.master

import net.fissionmc.master.chunk.ChunkAuthority
import net.fissionmc.master.chunk.ChunkKey
import net.fissionmc.master.client.PlayerProfile
import net.fissionmc.master.nodes.NodeAdmin
import net.fissionmc.net.*
import net.fissionmc.net.pairing.*
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

/** What a compromised backend may NOT do on the master: speak for another node, touch players it does not host, queue without limit, write outside the chunk store. */
class NodeAuthorizationTest {
    private class FakeLink : NodeLink {
        val sent = mutableListOf<NodeMessage>()
        override val ready = CompletableFuture<PeerInfo>(); override val closed = CompletableFuture<String>()
        override val latencyNanos = -1L; @Volatile override var isOpen = true; @Volatile override var isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        override fun send(msg: NodeMessage) { sent += msg }
        override fun close(reason: String) { isOpen = false; closed.complete(reason) }
    }
    private class FakeClient(name: String) : RelayClient {
        override val profile = PlayerProfile(UUID.nameUUIDFromBytes(name.toByteArray()), name, emptyList())
        override val protocol = 777
        val got = mutableListOf<ByteArray>()
        override fun deliver(frame: ByteArray) { got += frame }
        override fun detached(reason: String, backendLost: Boolean) {}
    }
    private val reg = NodeRegistry(); private val links = HashMap<String, FakeLink>()
    private fun node(id: String): RegisteredNode { val l = FakeLink(); links[id] = l; reg.register(PeerInfo(id, NodeRole.BACKEND, 1, "26.3"), l); return reg[id]!! }
    private val relay = PlayerRelay(reg)
    private val a = node("a"); private val b = node("b"); private val c = node("c")
    init { Audit.sink = { } }
    private fun sentTo(id: String) = links[id]!!.sent
    private fun gz(s: String): ByteArray = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(s.repeat(5).toByteArray()) } }.toByteArray()
    private fun uuid(n: String) = UUID.nameUUIDFromBytes(n.toByteArray())

    @Test fun `a backend cannot send handovers, border ticks, ghost traffic or items in the name of another node`() {
        relay.handle(a, EntityHandover("b", "c", "minecraft:overworld", emptyList()))
        relay.handle(a, BorderForward("b", "c", "minecraft:overworld", emptyList(), emptyList()))
        relay.handle(a, ItemTransfer("b", "c", "minecraft:overworld", 0, 0, 0, emptyList()))
        relay.handle(a, BlockEntitySignal("b", "c", "minecraft:overworld", 0, 0, 0, 1))
        relay.handle(a, GhostDemand("b", "c", "minecraft:overworld", longArrayOf(1)))
        relay.handle(a, GhostSync("b", "c", "minecraft:overworld", 0, 0, intArrayOf()))
        relay.handle(a, GhostFix("b", "c", "minecraft:overworld", 0, 0, emptyList()))
        assertTrue(sentTo("c").none { it !is AnnouncementMode }, "nothing was forwarded: ${sentTo("c").map { it.javaClass.simpleName }}")
        // the honest versions are forwarded
        relay.handle(a, EntityHandover("a", "c", "minecraft:overworld", emptyList())); relay.handle(a, BorderForward("a", "c", "minecraft:overworld", emptyList(), emptyList()))
        assertEquals(listOf("EntityHandover", "BorderForward"), sentTo("c").map { it.javaClass.simpleName })
    }

    /** zlib NBT of an entity compound with its UUID and passengers (each with their own UUID), written as `NbtIo.write` does. */
    private fun entityNbt(uuid: UUID, vararg passengers: UUID, junk: Boolean = true): ByteArray {
        fun ints(u: UUID, o: java.io.DataOutputStream) { o.writeByte(11); o.writeUTF("UUID"); o.writeInt(4); o.writeInt((u.mostSignificantBits shr 32).toInt()); o.writeInt(u.mostSignificantBits.toInt()); o.writeInt((u.leastSignificantBits shr 32).toInt()); o.writeInt(u.leastSignificantBits.toInt()) }
        val bo = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(bo).use { z ->
            val o = java.io.DataOutputStream(z)
            o.writeByte(10); o.writeUTF("")
            if (junk) { o.writeByte(8); o.writeUTF("id"); o.writeUTF("minecraft:horse"); o.writeByte(10); o.writeUTF("leash"); ints(UUID(9, 9), o); o.writeByte(0) }   // a leash holder's UUID must not count
            ints(uuid, o)
            if (passengers.isNotEmpty()) { o.writeByte(9); o.writeUTF("Passengers"); o.writeByte(10); o.writeInt(passengers.size); for (p in passengers) { ints(p, o); o.writeByte(0) } }
            o.writeByte(0)
        }
        return bo.toByteArray()
    }
    private fun pair(u: UUID) = listOf(u.mostSignificantBits, u.leastSignificantBits)
    private fun blob(u: UUID, vararg pass: UUID) = EntityBlob(1, u.mostSignificantBits, u.leastSignificantBits, entityNbt(u, *pass))

    @Test fun `a handover ack must answer a handover that was forwarded to the acking node`() {
        val horse = UUID(1, 2); val rider = UUID(3, 4)
        relay.handle(b, EntityHandoverAck("a", pair(horse)))     // b was never handed anything by a
        assertTrue(sentTo("a").none { it is EntityRemove }, "a compromised backend cannot make another one remove entities")
        relay.handle(a, EntityHandover("a", "b", "minecraft:overworld", listOf(blob(horse, rider))))
        relay.handle(b, EntityHandoverAck("a", pair(horse) + pair(rider)))
        assertEquals(pair(horse) + pair(rider), sentTo("a").filterIsInstance<EntityRemove>().single().uuids, "the offered entity and its passenger")
        relay.handle(c, EntityHandoverAck("a", pair(UUID(6, 6))))
        assertEquals(1, sentTo("a").count { it is EntityRemove }, "c got nothing from a either")
    }

    @Test fun `a handover ack cannot name entities that were not offered`() {
        val horse = UUID(1, 2); val rider = UUID(3, 4); val stranger = UUID(7, 7); val holder = UUID(9, 9)
        relay.handle(a, EntityHandover("a", "b", "minecraft:overworld", listOf(blob(horse, rider))))
        relay.handle(b, EntityHandoverAck("a", pair(stranger) + pair(holder) + pair(rider)))   // a leash holder in the blob's leash tag is not an offered entity either
        assertEquals(pair(rider), sentTo("a").filterIsInstance<EntityRemove>().single().uuids)
        relay.handle(b, EntityHandoverAck("a", pair(stranger)))
        assertEquals(1, sentTo("a").count { it is EntityRemove }, "nothing offered in that ack: nothing sent")
        // an unreadable blob offers only its own uuid
        relay.handle(a, EntityHandover("a", "c", "minecraft:overworld", listOf(EntityBlob(1, 11, 12, ByteArray(10)))))
        relay.handle(c, EntityHandoverAck("a", pair(UUID(11, 12)) + pair(UUID(1, 1))))
        assertEquals(pair(UUID(11, 12)), sentTo("a").filterIsInstance<EntityRemove>().last().uuids)
    }

    @Test fun `players are only acted on by the backend that hosts them`() {
        val alice = FakeClient("alice"); val id = relay.attach(alice, "a")!!
        // another backend cannot send frames to her, detach her, answer chat for her or report her position
        relay.handle(b, PlayerPacketToClient(id, byteArrayOf(1, 2, 3))); assertTrue(alice.got.isEmpty())
        relay.handle(a, PlayerPacketToClient(id, byteArrayOf(1, 2, 3))); assertEquals(1, alice.got.size)
        var chat = 0; relay.chatResultHandler = { chat++ }
        relay.handle(b, GlobalChatResult(id, 1, false, true, "x", "{}")); assertEquals(0, chat)
        relay.handle(a, GlobalChatResult(id, 1, false, true, "x", "{}")); assertEquals(1, chat)
        // items to a player on a node where she is not
        relay.handle(b, ItemGive("c", alice.profile.uuid.mostSignificantBits, alice.profile.uuid.leastSignificantBits, ByteArray(4)))
        assertTrue(sentTo("c").none { it is ItemGive })
        relay.handle(b, ItemGive("a", alice.profile.uuid.mostSignificantBits, alice.profile.uuid.leastSignificantBits, ByteArray(4)))
        assertTrue(sentTo("a").none { it is ItemGive }, "no pickup was asked of b for alice")
        relay.notePickup("b", alice.profile.uuid, "a")
        relay.handle(c, ItemGive("a", alice.profile.uuid.mostSignificantBits, alice.profile.uuid.leastSignificantBits, ByteArray(4)))
        assertTrue(sentTo("a").none { it is ItemGive }, "the pickup was asked of b, not of c")
        relay.handle(b, ItemGive("a", alice.profile.uuid.mostSignificantBits, alice.profile.uuid.leastSignificantBits, ByteArray(4)))
        assertTrue(sentTo("a").any { it is ItemGive })
        // ghosts
        var ghosts = 0; relay.ghostHandler = { ghosts++ }
        relay.handle(b, GhostPose(alice.profile.uuid.mostSignificantBits, alice.profile.uuid.leastSignificantBits, "minecraft:overworld", 0.0, 0.0, 0.0, 0f, 0f, 0f, false)); assertEquals(0, ghosts)
        relay.handle(a, GhostPose(alice.profile.uuid.mostSignificantBits, alice.profile.uuid.leastSignificantBits, "minecraft:overworld", 0.0, 0.0, 0.0, 0f, 0f, 0f, false)); assertEquals(1, ghosts)
    }

    @Test fun `a tracked item give is acknowledged back to the owner, and a refused one is answered at once`() {
        val alice = FakeClient("alice"); relay.attach(alice, "a")
        val h = alice.profile.uuid.mostSignificantBits; val l = alice.profile.uuid.leastSignificantBits
        // no pickup asked: refused straight back to the owner (b), never forwarded
        relay.handle(b, ItemGiveTracked("a", "b", 7, 8, h, l, ByteArray(4)))
        assertTrue(sentTo("a").none { it is ItemGiveTracked })
        assertEquals(false, sentTo("b").filterIsInstance<ItemGiveAck>().single().accepted)
        // a spoofed origin is dropped
        relay.handle(c, ItemGiveTracked("a", "b", 7, 8, h, l, ByteArray(4))); assertEquals(1, sentTo("b").count { it is ItemGiveAck })
        relay.notePickup("b", alice.profile.uuid, "a")
        relay.handle(b, ItemGiveTracked("a", "b", 7, 8, h, l, ByteArray(4)))
        assertEquals(1, sentTo("a").count { it is ItemGiveTracked })
        // an ack from a node that did not get the give is ignored; the right one reaches the owner, once
        relay.handle(c, ItemGiveAck("b", 7, 8, true)); assertEquals(1, sentTo("b").count { it is ItemGiveAck })
        relay.handle(a, ItemGiveAck("b", 9, 9, true)); assertEquals(1, sentTo("b").count { it is ItemGiveAck }, "an ack for a give nobody made")
        relay.handle(a, ItemGiveAck("b", 7, 8, true)); assertEquals(2, sentTo("b").count { it is ItemGiveAck })
        assertTrue(sentTo("b").filterIsInstance<ItemGiveAck>().last().accepted)
        relay.handle(a, ItemGiveAck("b", 7, 8, true)); assertEquals(2, sentTo("b").count { it is ItemGiveAck }, "answered once")
    }

    @Test fun `a transfer ack that comes after the ack window removes nothing on the origin`() {
        relay.playerData = net.fissionmc.master.PlayerDataStore(Files.createTempDirectory("pd"))
        fun run(name: String, ttl: Long): Boolean {
            relay.ackTtlMs = ttl
            val p = FakeClient(name); val h = p.profile.uuid.mostSignificantBits; val l = p.profile.uuid.leastSignificantBits
            val id = relay.attach(p, "a")!!
            relay.handle(a, EntityTransfer(h, l, listOf(EntityBlob(1, 1, 1, ByteArray(10)))))
            relay.detach(id, "x"); relay.attach(p, "b")
            assertTrue(sentTo("b").any { it is EntityTransfer })
            val before = sentTo("a").count { it is EntityRemove }
            relay.handle(b, EntityTransferAck(h, l, listOf(1L, 1L)))
            return sentTo("a").count { it is EntityRemove } > before
        }
        assertTrue(run("p1", 120_000), "control: inside the window the origin removes its originals")
        assertFalse(run("p2", -1), "the window is over: the stale entry is ignored")
    }

    @Test fun `pending travelling entities are bounded and only come from the player's backend`() {
        val bob = FakeClient("bob"); relay.attach(bob, "a")
        relay.handle(b, EntityTransfer(bob.profile.uuid.mostSignificantBits, bob.profile.uuid.leastSignificantBits, listOf(EntityBlob(1, 1, 1, ByteArray(10)))))
        // the pending map is private: a transfer from b is not kept, so attaching bob to b delivers nothing
        val link = links["b"]!!; link.sent.clear(); relay.detach(relay.bindingNodes().keys.first(), "x")
        relay.attach(bob, "b"); assertTrue(link.sent.none { it is EntityTransfer })
        // flood of transfers for players nobody hosts keeps nothing
        repeat(5000) { i -> relay.handle(a, EntityTransfer(i.toLong(), 1, listOf(EntityBlob(1, 1, 1, ByteArray(10))))) }
        val carol = FakeClient("carol"); relay.attach(carol, "c"); assertTrue(sentTo("c").none { it is EntityTransfer })
    }

    @Test fun `chunk messages with a path-like level id are dropped, the store never leaves its directory`() {
        val dir = Files.createTempDirectory("chunks"); val outside = dir.resolveSibling(dir.fileName.toString() + "-sibling")
        val store = net.fissionmc.master.chunk.FileChunkStore(dir) { }
        val auth = ChunkAuthority(store, reg, java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r).also { it.isDaemon = true } }, { }, 60_000)
        for (bad in listOf("..", "../x", "a:../../x", "minecraft:..", "", "x".repeat(300), "minecraft:over world", "/etc:passwd")) {
            auth.handle(a, ChunkPush(bad, 0, 0, 0, 1, 0, ByteArray(8)))
            auth.handle(a, ChunkRequest(1, bad, 0, 0, 0, false))
        }
        auth.handle(a, ChunkPush("minecraft:overworld", 77, 0, 0, 1, 0, ByteArray(8)))
        assertEquals(0L, store.count(), "nothing was stored")
        assertFalse(Files.exists(outside))
        assertFalse(ChunkKey.validDimension("..")); assertTrue(ChunkKey.validDimension("minecraft:overworld")); assertTrue(ChunkKey.validDimension("my_pack:dims/the_end"))
        assertFailsWith<IllegalArgumentException> { store.put(ChunkKey("..", 0, 0, 0), 1, 0, ByteArray(1), "a") }
    }

    @Test fun `player data of a player nobody hosts is not created by a backend`() {
        val store = PlayerDataStore(Files.createTempDirectory("pd"), { })
        val u = uuid("ghost-player")
        assertFalse(store.onSave("a", u, gz("player")), "no owner and no file: refused")
        store.setOwner(u, "a"); assertTrue(store.onSave("a", u, gz("player")))
        assertFalse(store.onSave("b", u, gz("player")), "not the owner")
    }

    // ---- node administration
    private fun admin(): Triple<NodeAdmin, PairingStore, java.nio.file.Path> {
        val dir = Files.createTempDirectory("admin"); val keyFile = dir.resolve("fission-node.key"); val pair = Keys.loadOrCreate(keyFile)
        val store = PairingStore(AuthorizedKeys(dir.resolve("authorized")))
        val sec = NodeSecurity(pair.private, publicKey = pair.public) { store.authorized[it] }.also { it.pairingStore = store }
        return Triple(NodeAdmin(keyFile, sec, store, reg), store, dir)
    }

    @Test fun `node add stores the key at once, remove closes the live link and refuses a return, a rename moves the key`() {
        val (admin, store, _) = admin(); val say = ArrayList<String>()
        val be = Keys.generate()
        val code = PairingCode("new-be", be.public, ByteArray(16) { 3 }, System.currentTimeMillis() + 600_000).encode()
        admin.command(listOf("add", code), "console") { say += it }
        assertTrue(say.joinToString().contains("authorized") && say.joinToString().contains(Fingerprint.of(be.public)), say.toString())
        assertEquals(be.public, store.authorized["new-be"]); assertNotNull(store.pendingFor("new-be"))
        say.clear(); admin.command(listOf("pending"), "console") { say += it }; assertTrue(say[0].startsWith("new-be"))
        say.clear(); admin.command(listOf("add", "garbage"), "console") { say += it }; assertTrue(say[0].startsWith("not added"))
        val live = node("new-be"); admin.command(listOf("rename", "new-be", "renamed-be"), "console") { }
        assertNull(store.authorized["new-be"]); assertEquals(be.public, store.authorized["renamed-be"]); assertFalse(links["new-be"]!!.isOpen, "renaming drops the live link")
        val live2 = node("renamed-be"); admin.command(listOf("remove", "renamed-be"), "console") { }
        assertFalse(links["renamed-be"]!!.isOpen); assertNull(store.authorized["renamed-be"]); assertNull(store.pendingFor("renamed-be"))
        say.clear(); admin.command(listOf("list"), "console") { say += it }; assertTrue(say[0].startsWith("no authorized nodes"))
        admin.close()
    }

    @Test fun `a backend can only rotate its own key, and only when the admin asked`() {
        val (admin, store, _) = admin(); val old = Keys.generate(); val fresh = Keys.generate()
        store.authorized.put("rot-be", old.public); val n = node("rot-be")
        admin.handle(n, Rotation.nodeRotation("rot-be", 1, old.private, fresh))
        assertTrue((links["rot-be"]!!.sent.last() as KeyRotationAck).ok.not(), "not asked for")
        admin.command(listOf("rotate", "rot-be"), "console") { }
        val req = (links["rot-be"]!!.sent.last() as RotateKeyRequest).reqId
        admin.handle(n, Rotation.nodeRotation("rot-be", req, Keys.generate().private, fresh))   // signed with a key that is not the authorized one
        assertEquals(old.public, store.authorized["rot-be"])
        admin.command(listOf("rotate", "rot-be"), "console") { }
        val req2 = (links["rot-be"]!!.sent.last() as RotateKeyRequest).reqId
        admin.handle(n, Rotation.nodeRotation("rot-be", req2, old.private, fresh))
        assertEquals(fresh.public, store.authorized["rot-be"]); assertTrue((links["rot-be"]!!.sent.last() as KeyRotationAck).ok)
        admin.close()
    }
}
