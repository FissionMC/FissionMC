package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.util.EventCreationUtil
import com.github.retrooper.packetevents.util.KnownPack
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientConfigurationEndAck
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSelectKnownPacks
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake.ConnectionIntention
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginSuccessAck
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerLoginSuccess
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerSetCompression
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientConfigurationAck
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerLoaded
import com.github.retrooper.packetevents.wrapper.status.client.WrapperStatusClientPing
import com.github.retrooper.packetevents.wrapper.status.client.WrapperStatusClientRequest
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerPong
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerResponse
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import net.fissionmc.protocol.MC_VERSION
import java.io.DataInputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Blocking test client. Encoding/decoding uses PacketEvents wrappers only (single source of truth, not hand-written). */
class TestClient(port: Int) : AutoCloseable {
    private val sock = Socket("127.0.0.1", port).also { it.soTimeout = 10_000 }
    /** Every byte the server wrote to this connection (plain text connections only): the exact stream a real client would decode. */
    val received = java.io.ByteArrayOutputStream()
    private var input: java.io.InputStream = object : java.io.InputStream() {
        private val s = sock.getInputStream()
        override fun read(): Int { val b = s.read(); if (b >= 0) received.write(b); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = s.read(b, off, len); if (n > 0) received.write(b, off, n); return n }
    }
    private var output = sock.getOutputStream()
    private val channel = EmbeddedChannel()
    val user = User(channel, ConnectionState.HANDSHAKING, ClientVersion.V_26_3, UserProfile(null, null))
    private var threshold = -1

    fun enableEncryption(secret: ByteArray) {
        val dec = LoginCrypto.aes(secret, Cipher.DECRYPT_MODE); val enc = LoginCrypto.aes(secret, Cipher.ENCRYPT_MODE)
        val rawIn = input; val rawOut = output
        // byte-at-a-time CFB8 (the JDK's CipherInputStream/OutputStream can hold bytes back)
        input = object : java.io.InputStream() {
            override fun read(): Int { val b = rawIn.read(); return if (b < 0) -1 else dec.update(byteArrayOf(b.toByte()))[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val n = rawIn.read(b, off, len); if (n <= 0) return n; val d = dec.update(b, off, n); System.arraycopy(d, 0, b, off, n); return n }
        }
        output = object : java.io.OutputStream() {
            override fun write(b: Int) { rawOut.write(enc.update(byteArrayOf(b.toByte()))) }
            override fun write(b: ByteArray, off: Int, len: Int) { rawOut.write(enc.update(b, off, len)) }
            override fun flush() { rawOut.flush() }
        }
    }
    fun readTimeout(ms: Int) { sock.soTimeout = ms }
    fun setState(s: ConnectionState) { user.decoderState = s; user.encoderState = s }
    fun setCompression(t: Int) { threshold = t }

    @Synchronized fun send(w: PacketWrapper<*>) {
        val b = Unpooled.buffer(); w.buffer = b
        w.writeVarInt(w.nativePacketId); w.write()
        sendRaw(ByteArray(b.readableBytes()).also { b.readBytes(it) })
    }

    /** Sends one packet frame (id + payload): compression and length prefix are added. */
    @Synchronized fun sendRaw(frame: ByteArray) {
        var body = frame
        if (threshold >= 0) {
            val o = Unpooled.buffer()
            if (body.size < threshold) { VarInts.write(o, 0); o.writeBytes(body) }
            else {
                VarInts.write(o, body.size)
                val d = Deflater(); d.setInput(body); d.finish(); val tmp = ByteArray(65536)
                while (!d.finished()) o.writeBytes(tmp, 0, d.deflate(tmp))
            }
            body = ByteArray(o.readableBytes()).also { o.readBytes(it) }
        }
        val f = Unpooled.buffer(); VarInts.write(f, body.size); f.writeBytes(body)
        output.write(ByteArray(f.readableBytes()).also { f.readBytes(it) }); output.flush()
    }

    private fun readVarInt(): Int { var v = 0; var i = 0; while (true) { val b = input.read(); require(b >= 0) { "eof" }; v = v or ((b and 0x7f) shl (7 * i++)); if (b and 0x80 == 0) return v } }

    private fun readBody(): ByteArray {
        val len = readVarInt(); var body = DataInputStream(input).readNBytes(len)
        if (threshold >= 0) {
            val bb = Unpooled.wrappedBuffer(body); val size = VarInts.tryRead(bb)!!
            val rest = ByteArray(bb.readableBytes()).also { bb.readBytes(it) }
            body = if (size == 0) rest else { val inf = Inflater(); inf.setInput(rest); ByteArray(size).also { inf.inflate(it) } }
        }
        return body
    }
    private fun readEvent() = EventCreationUtil.createSendEvent(channel, user, null, Unpooled.wrappedBuffer(readBody()), false)
    fun <W : PacketWrapper<W>> expect(type: PacketTypeCommon, make: (com.github.retrooper.packetevents.event.PacketSendEvent) -> W): W {
        val ev = readEvent(); assertEquals(type, ev.packetType, "unexpected packet ${ev.packetType}"); return make(ev)
    }
    /** Reads frames until one of [type] arrives (the recorded configuration contains frames the test does not care about). */
    fun skipUntil(type: PacketTypeCommon): Int { var n = 0; while (true) { val ev = readEvent(); n++; if (ev.packetType == type) return n } }
    /** The event of the last [readAny] (null if PacketEvents could not map the id). */
    var lastEvent: com.github.retrooper.packetevents.event.PacketSendEvent? = null
    /** Reads one frame with PacketEvents; returns its type (null if PacketEvents cannot map the id) and id. */
    /** The raw body (packet id + payload) of the last [readAny]. */
    @Volatile var lastBody: ByteArray = ByteArray(0)
    fun readAny(): Pair<PacketTypeCommon?, Int> {
        val body = readBody(); lastBody = body; val id = VarInts.tryRead(Unpooled.wrappedBuffer(body))!!
        return try { val ev = EventCreationUtil.createSendEvent(channel, user, null, Unpooled.wrappedBuffer(body), false); lastEvent = ev; ev.packetType to id } catch (t: Throwable) { lastEvent = null; null to id }
    }
    /** True if the server sends nothing for [ms] milliseconds. */
    fun silentFor(ms: Int): Boolean { sock.soTimeout = ms; return try { readVarInt(); false } catch (_: java.net.SocketTimeoutException) { true } finally { sock.soTimeout = 10_000 } }
    /** Reads one frame and returns its packet id (no decoding). */
    fun readFrameForTest(): Int = VarInts.tryRead(Unpooled.wrappedBuffer(readBody()))!!
    fun skip(type: PacketTypeCommon) { val ev = readEvent(); assertEquals(type, ev.packetType, "unexpected packet ${ev.packetType}") }
    override fun close() { sock.close(); channel.finishAndReleaseAll() }
}

class ClientServerTest {
    private fun server(online: Boolean, auth: Authenticator = Authenticator { _, _ -> CompletableFuture.completedFuture(null) }) =
        ClientServer(ClientConfig(host = "127.0.0.1", port = 0, onlineMode = online, motd = "test motd", compressionThreshold = 64), auth, log = {}).start()

    @Test fun `status reports motd, version 26_3 and answers ping`() {
        server(false).use { s ->
            TestClient(s.port).use { c ->
                c.send(WrapperHandshakingClientHandshake(777, "localhost", s.port, ConnectionIntention.STATUS)); c.setState(ConnectionState.STATUS)
                c.send(WrapperStatusClientRequest())
                val json = c.expect(PacketType.Status.Server.RESPONSE) { WrapperStatusServerResponse(it) }.componentJson
                assertTrue(json.contains("test motd") && json.contains("\"protocol\":777") && json.contains(MC_VERSION), json)
                c.send(WrapperStatusClientPing(1234L))
                assertEquals(1234L, c.expect(PacketType.Status.Server.PONG) { WrapperStatusServerPong(it) }.time)
            }
        }
    }

    private fun joinLimbo(c: TestClient, port: Int, name: String, afterLoginStart: () -> Unit = {}): UUID {
        c.send(WrapperHandshakingClientHandshake(777, "localhost", port, ConnectionIntention.LOGIN)); c.setState(ConnectionState.LOGIN)
        c.send(WrapperLoginClientLoginStart(ClientVersion.V_26_3, name, null, UUID(0, 1)))
        afterLoginStart()
        val t = c.expect(PacketType.Login.Server.SET_COMPRESSION) { WrapperLoginServerSetCompression(it) }.threshold
        c.setCompression(t)
        val ok = c.expect(PacketType.Login.Server.LOGIN_SUCCESS) { WrapperLoginServerLoginSuccess(it) }.userProfile
        assertEquals(name, ok.name)
        c.send(WrapperLoginClientLoginSuccessAck()); c.setState(ConnectionState.CONFIGURATION)
        c.skipUntil(PacketType.Configuration.Server.SELECT_KNOWN_PACKS)
        c.send(WrapperConfigClientSelectKnownPacks(listOf(KnownPack("minecraft", "core", MC_VERSION))))
        c.skipUntil(PacketType.Configuration.Server.UPDATE_TAGS)       // the tags packet must be sent before the end
        c.skipUntil(PacketType.Configuration.Server.CONFIGURATION_END)
        c.send(WrapperConfigClientConfigurationEndAck()); c.setState(ConnectionState.PLAY)
        c.skip(PacketType.Play.Server.JOIN_GAME); c.skip(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)
        c.skip(PacketType.Play.Server.CHANGE_GAME_STATE); c.skip(PacketType.Play.Server.UPDATE_VIEW_POSITION)
        c.skip(PacketType.Play.Server.CHUNK_DATA)
        assertTrue(c.silentFor(300), "the limbo sends nothing after its Login burst (no chat line)")
        return ok.uuid
    }

    @Test fun `offline login reaches limbo through configuration and play`() {
        server(false).use { s ->
            TestClient(s.port).use { c ->
                val id = joinLimbo(c, s.port, "Steve")
                // keep the exact server->client stream: the backend's vanilla client-side decoders replay it (LimboCheck, see worklog)
                java.nio.file.Files.write(java.nio.file.Path.of("build/limbo-stream.bin"), c.received.toByteArray())
                assertEquals(LoginCrypto.offlineUuid("Steve"), id)
                assertEquals(1, s.onlinePlayers)
            }
        }
    }

    @Test fun `online login encrypts, authenticates with the vanilla server hash and reaches limbo`() {
        var seenName = ""; var seenHash = ""
        val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val auth = Authenticator { n, h -> seenName = n; seenHash = h; CompletableFuture.completedFuture(PlayerProfile(uuid, n, listOf(ProfileProperty("textures", "v", "sig")))) }
        server(true, auth).use { s ->
            TestClient(s.port).use { c ->
                val secret = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                var pub: ByteArray? = null
                val got = joinLimbo(c, s.port, "Alex") {
                    val req = c.expect(PacketType.Login.Server.ENCRYPTION_REQUEST) { WrapperLoginServerEncryptionRequest(it) }
                    pub = req.publicKeyBytes
                    fun rsa(d: ByteArray) = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, req.publicKey) }.doFinal(d)
                    c.send(WrapperLoginClientEncryptionResponse(ClientVersion.V_26_3, rsa(secret), rsa(req.verifyToken)))
                    c.enableEncryption(secret)
                }
                assertEquals(uuid, got); assertEquals("Alex", seenName)
                assertEquals(LoginCrypto.serverHash("", secret, pub!!), seenHash)
            }
        }
    }

    @Test fun `online login with failed authentication is refused`() {
        server(true).use { s ->
            TestClient(s.port).use { c ->
                c.send(WrapperHandshakingClientHandshake(777, "localhost", s.port, ConnectionIntention.LOGIN)); c.setState(ConnectionState.LOGIN)
                c.send(WrapperLoginClientLoginStart(ClientVersion.V_26_3, "Mallory", null, UUID(0, 1)))
                val req = c.expect(PacketType.Login.Server.ENCRYPTION_REQUEST) { WrapperLoginServerEncryptionRequest(it) }
                val secret = ByteArray(16) { 1 }
                fun rsa(d: ByteArray) = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, req.publicKey) }.doFinal(d)
                c.send(WrapperLoginClientEncryptionResponse(ClientVersion.V_26_3, rsa(secret), rsa(req.verifyToken)))
                c.enableEncryption(secret)
                c.skip(PacketType.Login.Server.DISCONNECT)
            }
        }
    }

    @Test fun `wrong protocol version is disconnected`() {
        server(false).use { s ->
            TestClient(s.port).use { c ->
                c.send(WrapperHandshakingClientHandshake(700, "localhost", s.port, ConnectionIntention.LOGIN)); c.setState(ConnectionState.LOGIN)
                c.send(WrapperLoginClientLoginStart(ClientVersion.V_26_3, "Old", null, UUID(0, 1)))
                c.skip(PacketType.Login.Server.DISCONNECT)
            }
        }
    }

    /** A fake node link that records what the master sends to the backend. */
    private class FakeBackendLink : net.fissionmc.net.NodeLink {
        val sent = java.util.concurrent.LinkedBlockingQueue<net.fissionmc.protocol.NodeMessage>()
        override val ready = CompletableFuture<net.fissionmc.net.PeerInfo>()
        override val closed = CompletableFuture<String>()
        override val latencyNanos = -1L
        @Volatile override var isOpen = true
        override val isWritable = true
        override var handler: (net.fissionmc.protocol.NodeMessage) -> Unit = {}
        override fun send(msg: net.fissionmc.protocol.NodeMessage) { sent.add(msg) }
        override fun close(reason: String) { isOpen = false; closed.complete(reason) }
    }

    private class Rig(val registry: net.fissionmc.master.NodeRegistry, val links: Map<String, FakeBackendLink>, val relay: net.fissionmc.master.PlayerRelay)
    private fun rig(vararg ids: String): Rig {
        val registry = net.fissionmc.master.NodeRegistry(); val links = ids.associateWith { FakeBackendLink() }
        links.forEach { (id, l) -> registry.register(net.fissionmc.net.PeerInfo(id, net.fissionmc.protocol.NodeRole.BACKEND, 1, MC_VERSION), l) }
        return Rig(registry, links, net.fissionmc.master.PlayerRelay(registry))
    }
    private fun FakeBackendLink.next(pred: (net.fissionmc.protocol.NodeMessage) -> Boolean): net.fissionmc.protocol.NodeMessage =
        generateSequence { sent.poll(10, java.util.concurrent.TimeUnit.SECONDS) }.first(pred)
    private fun frameId(m: net.fissionmc.protocol.NodeMessage) = Limbo.idOf((m as net.fissionmc.protocol.PlayerPacketToBackend).bytes)
    /** A backend's Login frame with a chosen entity id (the limbo's own Login frame, entity id patched). */
    private fun loginFrame(entityId: Int): ByteArray {
        val f = Limbo.encode(Limbo.joinGame(100, Limbo.config.overworldDimensionId)); val o = VarInts.size(Limbo.idOf(f))
        java.nio.ByteBuffer.wrap(f).putInt(o, entityId); return f
    }

    @Test fun `limbo to backend switch completes the backend configuration itself and never sends StartConfiguration`() {
        val r = rig("b1"); val link = r.links.getValue("b1")
        ClientServer(ClientConfig(host = "127.0.0.1", port = 0, onlineMode = false, compressionThreshold = 256), relay = r.relay, log = {}).start().use { s ->
            TestClient(s.port).use { c ->
                joinLimbo(c, s.port, "Handoff")
                // vanilla's client accepts a new level only when it finished the first one: nothing may follow the limbo burst until PlayerLoaded
                assertTrue(c.silentFor(700), "nothing before the client reported its level loaded")
                c.send(WrapperPlayClientPlayerLoaded())
                val attach = link.next { it is net.fissionmc.protocol.PlayerAttach } as net.fissionmc.protocol.PlayerAttach
                val cfg = Limbo.config.let { it.beforeKnownPacks + it.afterKnownPacks }
                // the backend starts its configuration at once; the master (its virtual client) answers known packs and the end
                cfg.forEach { f -> r.relay.handle(r.registry["b1"]!!, net.fissionmc.protocol.PlayerPacketToClient(attach.playerId, f)) }
                val known = link.next { it is net.fissionmc.protocol.PlayerPacketToBackend && frameId(it) == PacketType.Configuration.Client.SELECT_KNOWN_PACKS.getId(ClientVersion.V_26_3) }
                assertTrue(known is net.fissionmc.protocol.PlayerPacketToBackend)
                link.next { it is net.fissionmc.protocol.PlayerPacketToBackend && frameId(it) == PacketType.Configuration.Client.CONFIGURATION_END_ACK.getId(ClientVersion.V_26_3) }
                assertTrue(c.silentFor(300), "the client sees none of the backend's configuration")
                // the backend's play side: Login (entity id 77), then a keepalive
                r.relay.handle(r.registry["b1"]!!, net.fissionmc.protocol.PlayerPacketToClient(attach.playerId, loginFrame(77)))
                val ka = Limbo.encode(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive(4242L))
                r.relay.handle(r.registry["b1"]!!, net.fissionmc.protocol.PlayerPacketToClient(attach.playerId, ka))
                // the client stays in PLAY: cleanup frames, the Login with the backend's id, the keepalive; never StartConfiguration
                val types = ArrayList<Pair<com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon?, Int>>()
                while (types.none { it.first == PacketType.Play.Server.KEEP_ALIVE }) types += c.readAny()
                assertTrue(types.none { it.first == PacketType.Play.Server.CONFIGURATION_START }, "no StartConfiguration: $types")
                assertTrue(types.any { it.first == PacketType.Play.Server.JOIN_GAME }, "the backend's Login reaches the client: $types")
                val login = types.indexOfFirst { it.first == PacketType.Play.Server.JOIN_GAME }
                assertEquals(PacketType.Play.Server.RESPAWN, types[login + 1].first, "the Respawn right after the second Login makes the client build a new LocalPlayer")
                assertEquals(PacketType.Play.Server.KEEP_ALIVE, types[login + 2].first, "backend frames follow")
                // keepalive answers: a stale id (previous backend) must not reach the backend, the current one must
                c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive(999L))
                c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive(4242L))
                val got = link.next { it is net.fissionmc.protocol.PlayerPacketToBackend && frameId(it) == PacketType.Play.Client.KEEP_ALIVE.getId(ClientVersion.V_26_3) } as net.fissionmc.protocol.PlayerPacketToBackend
                assertEquals(4242L, java.nio.ByteBuffer.wrap(PlayIds.payload(got.bytes)).long, "only the current backend's challenge is forwarded")
                java.nio.file.Files.write(java.nio.file.Path.of("build/switch-stream.bin"), c.received.toByteArray())
            }
        }
    }

    @Test fun `a backend whose configuration differs falls back to StartConfiguration with a reason`() {
        val r = rig("b1"); val link = r.links.getValue("b1")
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        ClientServer(ClientConfig(host = "127.0.0.1", port = 0, onlineMode = false, compressionThreshold = 256), relay = r.relay, log = { logs += it }).start().use { s ->
            TestClient(s.port).use { c ->
                joinLimbo(c, s.port, "Differs")
                c.send(WrapperPlayClientPlayerLoaded())
                val attach = link.next { it is net.fissionmc.protocol.PlayerAttach } as net.fissionmc.protocol.PlayerAttach
                val tags = PacketType.Configuration.Server.UPDATE_TAGS.getId(ClientVersion.V_26_3)
                val cfg = Limbo.config.let { it.beforeKnownPacks + it.afterKnownPacks }.map { if (Limbo.idOf(it) == tags) it.copyOf().also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } else it }
                cfg.forEach { f -> r.relay.handle(r.registry["b1"]!!, net.fissionmc.protocol.PlayerPacketToClient(attach.playerId, f)) }
                c.skip(PacketType.Play.Server.CONFIGURATION_START)
                assertTrue(logs.any { it.contains("configuration differs") } && logs.any { it.contains("falling back") }, logs.toString())
            }
        }
    }

    @Test fun `backend loss in play returns the client to the limbo without StartConfiguration`() {
        val r = rig("b1"); val link = r.links.getValue("b1")
        ClientServer(ClientConfig(host = "127.0.0.1", port = 0, onlineMode = false, compressionThreshold = 256), relay = r.relay, log = {}).start().use { s ->
            r.registry.addListener(r.relay.listener)
            TestClient(s.port).use { c ->
                joinLimbo(c, s.port, "Lost")
                c.send(WrapperPlayClientPlayerLoaded())
                val attach = link.next { it is net.fissionmc.protocol.PlayerAttach } as net.fissionmc.protocol.PlayerAttach
                (Limbo.config.let { it.beforeKnownPacks + it.afterKnownPacks }).forEach { f -> r.relay.handle(r.registry["b1"]!!, net.fissionmc.protocol.PlayerPacketToClient(attach.playerId, f)) }
                r.relay.handle(r.registry["b1"]!!, net.fissionmc.protocol.PlayerPacketToClient(attach.playerId, loginFrame(5)))
                c.skipUntil(PacketType.Play.Server.JOIN_GAME)
                link.close("test: backend died")
                // cleanup frames, then the limbo's Login again; the connection stays in PLAY
                val seen = ArrayList<com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon?>()
                while (PacketType.Play.Server.JOIN_GAME !in seen) seen += c.readAny().first
                assertTrue(PacketType.Play.Server.CONFIGURATION_START !in seen, seen.toString())
                c.skip(PacketType.Play.Server.RESPAWN)   // the limbo's Login is followed by a Respawn too (new LocalPlayer)
                c.skip(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)
            }
        }
    }
}

class CodecTest {
    @Test fun `compression round trips small and large frames`() {
        val ch = io.netty.channel.embedded.EmbeddedChannel(DecompressionDecoder(), CompressionEncoder(64))
        for (n in listOf(10, 63, 64, 100, 5000)) {
            val data = ByteArray(n) { (it % 7).toByte() }
            ch.writeOutbound(Unpooled.wrappedBuffer(data))
            val wire = ch.readOutbound<io.netty.buffer.ByteBuf>()
            ch.writeInbound(wire)
            val back = ch.readInbound<io.netty.buffer.ByteBuf>()
            assertEquals(data.toList(), ByteArray(back.readableBytes()).also { back.readBytes(it) }.toList(), "size $n")
        }
    }

    @Test fun `varint round trip and size`() {
        for (v in listOf(0, 1, 127, 128, 255, 25565, 2097151, Int.MAX_VALUE, -1)) {
            val b = Unpooled.buffer(); VarInts.write(b, v)
            assertEquals(VarInts.size(v), b.readableBytes()); assertEquals(v, VarInts.tryRead(b))
        }
        assertEquals(null, VarInts.tryRead(Unpooled.wrappedBuffer(byteArrayOf(0x80.toByte()))))
    }
    @Test fun `server hash matches the published vanilla examples`() {
        // wiki.vg examples: sha1 of the name, signed hex
        fun h(s: String) = LoginCrypto.serverHash(s, ByteArray(0), ByteArray(0))
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", h("Notch"))
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", h("jeb_"))
        assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", h("simon"))
    }
    @Test fun `offline uuid is the vanilla one`() {
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Steve".toByteArray()), LoginCrypto.offlineUuid("Steve"))
    }
    @Test fun `mojang hasJoined response parses`() {
        val p = MojangAuthenticator.parse("""{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","properties":[{"name":"textures","value":"abc","signature":"sig"}]}""")!!
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), p.uuid); assertEquals("Notch", p.name)
        assertEquals(listOf(ProfileProperty("textures", "abc", "sig")), p.properties)
    }
}
