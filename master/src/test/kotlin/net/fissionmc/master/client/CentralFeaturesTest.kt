package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.util.KnownPack
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientConfigurationEndAck
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSelectKnownPacks
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake.ConnectionIntention
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginSuccessAck
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerLoginSuccess
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerSetCompression
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerLoaded
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import io.netty.buffer.Unpooled
import net.fissionmc.master.NodeRegistry
import net.fissionmc.master.PlayerRelay
import net.fissionmc.master.seamless.GhostHub
import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import net.fissionmc.protocol.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The central tab list, the ghost players and the cross-backend commands through real client sockets and fake backend links (the scripted client stands in for a vanilla one). */
class CentralFeaturesTest {
    init { FissionPacketEventsAPI.install(Side.MASTER) }

    private class Link : NodeLink {
        val sent = LinkedBlockingQueue<NodeMessage>()
        override val ready = CompletableFuture<PeerInfo>(); override val closed = CompletableFuture<String>()
        override val latencyNanos = -1L
        @Volatile override var isOpen = true
        override val isWritable = true
        override var handler: (NodeMessage) -> Unit = {}
        override fun send(msg: NodeMessage) { sent.add(msg) }
        override fun close(reason: String) { isOpen = false; closed.complete(reason) }
        fun next(pred: (NodeMessage) -> Boolean): NodeMessage { val seen = ArrayList<String>(); return generateSequence { sent.poll(10, TimeUnit.SECONDS) }.firstOrNull { seen += it.javaClass.simpleName + (if (it is PlayerAttach) ":" + it.name else ""); pred(it) } ?: fail("no matching message, saw $seen") }
        fun none(ms: Long, pred: (NodeMessage) -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { val m = sent.poll(50, TimeUnit.MILLISECONDS) ?: continue; if (pred(m)) return false }; return true }
    }
    private class Rig {
        val registry = NodeRegistry(); val links = listOf("b1", "b2").associateWith { Link() }
        init { links.forEach { (id, l) -> registry.register(PeerInfo(id, NodeRole.BACKEND, 1, MC_VERSION), l) } }
        val relay = PlayerRelay(registry)
        fun node(id: String) = registry[id]!!
    }
    private class Player(val name: String, val client: TestClient, val node: String, val binding: Long) { val uuid: UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()) }

    private fun loginFrame(entityId: Int): ByteArray {
        val f = Limbo.encode(Limbo.joinGame(100, Limbo.config.overworldDimensionId)); val o = VarInts.size(Limbo.idOf(f))
        java.nio.ByteBuffer.wrap(f).putInt(o, entityId); return f
    }
    private fun joinLimbo(c: TestClient, port: Int, name: String) {
        c.send(WrapperHandshakingClientHandshake(777, "localhost", port, ConnectionIntention.LOGIN)); c.setState(ConnectionState.LOGIN)
        c.send(WrapperLoginClientLoginStart(ClientVersion.V_26_3, name, null, UUID(0, 1)))
        c.setCompression(c.expect(PacketType.Login.Server.SET_COMPRESSION) { WrapperLoginServerSetCompression(it) }.threshold)
        c.expect(PacketType.Login.Server.LOGIN_SUCCESS) { WrapperLoginServerLoginSuccess(it) }
        c.send(WrapperLoginClientLoginSuccessAck()); c.setState(ConnectionState.CONFIGURATION)
        c.skipUntil(PacketType.Configuration.Server.SELECT_KNOWN_PACKS)
        c.send(WrapperConfigClientSelectKnownPacks(listOf(KnownPack("minecraft", "core", MC_VERSION))))
        c.skipUntil(PacketType.Configuration.Server.UPDATE_TAGS); c.skipUntil(PacketType.Configuration.Server.CONFIGURATION_END)
        c.send(WrapperConfigClientConfigurationEndAck()); c.setState(ConnectionState.PLAY)
        c.skipUntil(PacketType.Play.Server.CHUNK_DATA)
        c.silentFor(200)
    }

    private fun addLine(name: String, uuid: UUID, gm: GameMode = GameMode.SURVIVAL, latency: Int = 7) = Limbo.encode(WrapperPlayServerPlayerInfoUpdate(TabEntry.FULL,
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo(UserProfile(uuid, name, mutableListOf(TextureProperty("textures", "skin-$name", null))), true, latency, gm, null, null, 0, true)))

    private val cfg get() = Limbo.config.let { it.beforeKnownPacks + it.afterKnownPacks }

    /** Joins [name] and lets the next backend (round robin) take it; its own tab line arrives like a real backend sends it. */
    private fun connect(r: Rig, s: ClientServer, name: String, selfId: Int): Player {
        val c = TestClient(s.port); joinLimbo(c, s.port, name)
        c.send(WrapperPlayClientPlayerLoaded())
        var attach: PlayerAttach? = null; var nodeId = ""
        val end = System.currentTimeMillis() + 10_000
        while (attach == null && System.currentTimeMillis() < end) for ((id, l) in r.links) { val m = l.sent.poll(20, TimeUnit.MILLISECONDS); if (m is PlayerAttach && m.name == name) { attach = m; nodeId = id } }
        attach!!
        cfg.forEach { f -> r.relay.handle(r.node(nodeId), PlayerPacketToClient(attach.playerId, f)) }
        r.relay.handle(r.node(nodeId), PlayerPacketToClient(attach.playerId, loginFrame(selfId)))
        val p = Player(name, c, nodeId, attach.playerId)
        r.relay.handle(r.node(nodeId), PlayerPacketToClient(attach.playerId, addLine(name, p.uuid)))
        c.skipUntil(PacketType.Play.Server.JOIN_GAME); c.skip(PacketType.Play.Server.RESPAWN)
        return p
    }

    /** Reads frames of [p] until [pred] accepts one (max 60 frames); returns the event of that frame. */
    private fun <T> readUntil(p: Player, pred: (com.github.retrooper.packetevents.event.PacketSendEvent) -> T?): T {
        repeat(60) { p.client.readAny(); val ev = p.client.lastEvent ?: return@repeat; pred(ev)?.let { return it } }
        fail("no matching frame for ${p.name}")
    }
    private fun tabLinesFor(p: Player, uuid: UUID): WrapperPlayServerPlayerInfoUpdate.PlayerInfo? = readUntil(p) { ev ->
        if (ev.packetType == PacketType.Play.Server.PLAYER_INFO_UPDATE) WrapperPlayServerPlayerInfoUpdate(ev).entries.firstOrNull { it.profileId == uuid } else null
    }

    private fun server(r: Rig, settings: MasterSettings = MasterSettings(switchMode = "login")) =
        ClientServer(ClientConfig(host = "127.0.0.1", port = 0, onlineMode = false, compressionThreshold = 256), relay = r.relay, settings = settings, log = {}).start()

    @Test fun `players on different backends see each other in the tab list, and a leave removes the line`() {
        val r = Rig(); r.registry.addListener(r.relay.listener)
        server(r).use { s ->
            val alice = connect(r, s, "Alice", 11); val bob = connect(r, s, "Bob", 22)
            try {
                val line = assertNotNull(tabLinesFor(alice, bob.uuid))
                assertEquals("Bob", line.gameProfile.name); assertEquals("skin-Bob", line.gameProfile.textureProperties.single().value)
                assertEquals(7, line.latency)
                assertNotNull(tabLinesFor(bob, alice.uuid), "and the other way round")
                // Bob's backend updates his latency: Alice (other backend) gets the update
                r.relay.handle(r.node(bob.node), PlayerPacketToClient(bob.binding, Limbo.encode(WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                    WrapperPlayServerPlayerInfoUpdate.PlayerInfo(UserProfile(bob.uuid, null)).also { it.latency = 123 }))))
                assertEquals(123, assertNotNull(tabLinesFor(alice, bob.uuid)).latency)
                // Bob leaves
                bob.client.close()
                val removed = readUntil(alice) { ev -> if (ev.packetType == PacketType.Play.Server.PLAYER_INFO_REMOVE) WrapperPlayServerPlayerInfoRemove(ev).profileIds else null }
                assertEquals(listOf(bob.uuid), removed)
            } finally { alice.client.close(); bob.client.close() }
        }
    }

    @Test fun `a backend remove for a player who is still online elsewhere never reaches the client`() {
        val r = Rig()
        server(r).use { s ->
            val alice = connect(r, s, "Alice", 11); val bob = connect(r, s, "Bob", 22)
            try {
                assertNotNull(tabLinesFor(alice, bob.uuid))
                r.relay.handle(r.node(alice.node), PlayerPacketToClient(alice.binding, Limbo.encode(WrapperPlayServerPlayerInfoRemove(listOf(bob.uuid)))))   // Bob "left" backend b1 (he never was there; stands for a move)
                r.relay.handle(r.node(alice.node), PlayerPacketToClient(alice.binding, Limbo.encode(WrapperPlayServerPlayerInfoRemove(listOf(UUID(9, 9))))))     // an unknown uuid: dropped too
                assertTrue(alice.client.silentFor(400), "both removes were filtered")
            } finally { alice.client.close(); bob.client.close() }
        }
    }

    @Test fun `a player of another backend becomes a ghost entity for a player in range and goes away again`() {
        val r = Rig()
        server(r).use { s ->
            val alice = connect(r, s, "Alice", 11); val bob = connect(r, s, "Bob", 22)
            try {
                assertNotNull(tabLinesFor(alice, bob.uuid)); assertNotNull(tabLinesFor(bob, alice.uuid))
                fun pose(p: Player, x: Double, z: Double) = GhostPose(p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, "minecraft:overworld", x, 64.0, z, 0f, 0f, 0f, true)
                r.relay.handle(r.node(bob.node), GhostMeta(bob.uuid.mostSignificantBits, bob.uuid.leastSignificantBits, byteArrayOf(-1), ByteArray(0)))
                r.relay.handle(r.node(alice.node), pose(alice, 0.0, 0.0)); r.relay.handle(r.node(bob.node), pose(bob, 30.0, 0.0))
                val spawn = readUntil(alice) { ev -> if (ev.packetType == PacketType.Play.Server.SPAWN_ENTITY) WrapperPlayServerSpawnEntity(ev) else null }
                assertEquals(GhostHub.ghostUuidOf(bob.uuid), spawn.uuid.get()); assertEquals(GhostHub.GHOST_BASE, spawn.entityId); assertEquals(30.0, spawn.position.x)
                // out of range: removed
                r.relay.handle(r.node(bob.node), pose(bob, 500.0, 0.0))
                val ids = readUntil(alice) { ev -> if (ev.packetType == PacketType.Play.Server.DESTROY_ENTITIES) WrapperPlayServerDestroyEntities(ev).entityIds.toList() else null }
                assertEquals(listOf(GhostHub.GHOST_BASE), ids)
            } finally { alice.client.close(); bob.client.close() }
        }
    }

    private fun commandFrame(text: String): ByteArray { val b = text.toByteArray(); val o = Unpooled.buffer(); VarInts.write(o, b.size); o.writeBytes(b); return PlayIds.frame(PlayIds.chatCommand, ByteArray(o.readableBytes()).also { o.readBytes(it) }) }
    private fun isCommand(m: NodeMessage, text: String) = m is PlayerPacketToBackend && Limbo.idOf(m.bytes) == PlayIds.chatCommand && Strings.readFirst(m.bytes) == text

    @Test fun `tp to a player on another backend asks the sender's backend, switches, then runs the command there`() {
        val r = Rig(); r.registry.addListener(r.relay.listener)
        server(r).use { s ->
            val alice = connect(r, s, "Alice", 11); val bob = connect(r, s, "Bob", 22)
            try {
                alice.client.sendRaw(commandFrame("tp Bob"))
                val check = r.links.getValue(alice.node).next { it is CommandCheck } as CommandCheck
                assertEquals("tp Bob", check.command); assertEquals(alice.binding, check.playerId)
                assertTrue(r.links.getValue(alice.node).none(300) { isCommand(it, "tp Bob") }, "the master holds the command back until the check is answered")
                r.relay.handle(r.node(alice.node), CommandCheckResult(alice.binding, check.seq, true))
                val attach = r.links.getValue(bob.node).next { it is PlayerAttach && it.name == "Alice" } as PlayerAttach   // the switch to Bob's backend
                cfg.forEach { f -> r.relay.handle(r.node(bob.node), PlayerPacketToClient(attach.playerId, f)) }
                r.relay.handle(r.node(bob.node), PlayerPacketToClient(attach.playerId, loginFrame(33)))
                val bl = r.links.getValue(bob.node)
                val probe1 = bl.next { it is CommandCheck } as CommandCheck   // arrival probe: no fixed delay, the backend is asked until it has the player
                assertEquals("tp Bob", probe1.command); assertEquals(attach.playerId, probe1.playerId)
                assertTrue(bl.none(250) { isCommand(it, "tp Bob") }, "not relayed before the backend has the player")
                r.relay.handle(r.node(bob.node), CommandCheckResult(attach.playerId, probe1.seq, false, present = false))
                val probe2 = bl.next { it is CommandCheck } as CommandCheck
                assertNotEquals(probe1.seq, probe2.seq)
                r.relay.handle(r.node(bob.node), CommandCheckResult(attach.playerId, probe2.seq, true))
                assertNotNull(bl.next { isCommand(it, "tp Bob") }, "relayed as the sender on the target backend, once it has the player")
                assertTrue(r.links.getValue(alice.node).none(200) { isCommand(it, "tp Bob") })
            } finally { alice.client.close(); bob.client.close() }
        }
    }

    @Test fun `a denied check, a missing answer and a same-backend target pass the command to the backend unchanged`() {
        val r = Rig()
        server(r, MasterSettings(switchMode = "login", cross = CrossCommandSettings(checkTimeoutMs = 400))).use { s ->
            val alice = connect(r, s, "Alice", 11); val bob = connect(r, s, "Bob", 22)
            try {
                val b1 = r.links.getValue(alice.node)
                alice.client.sendRaw(commandFrame("tp Bob"))
                val check = b1.next { it is CommandCheck } as CommandCheck
                r.relay.handle(r.node(alice.node), CommandCheckResult(alice.binding, check.seq, false))
                assertNotNull(b1.next { isCommand(it, "tp Bob") }, "denied: the backend gets the command and prints its own error")
                assertTrue(r.links.getValue(bob.node).none(200) { it is PlayerAttach && it.name == "Alice" }, "no switch")
                alice.client.sendRaw(commandFrame("tp Bob"))       // no answer this time
                b1.next { it is CommandCheck }
                assertNotNull(b1.next { isCommand(it, "tp Bob") }, "timeout: passed on")
                alice.client.sendRaw(commandFrame("tp Nobody"))
                assertNotNull(b1.next { isCommand(it, "tp Nobody") }); assertTrue(b1.none(200) { it is CommandCheck })
                alice.client.sendRaw(commandFrame("gamemode creative"))
                assertNotNull(b1.next { isCommand(it, "gamemode creative") })
            } finally { alice.client.close(); bob.client.close() }
        }
    }
}
