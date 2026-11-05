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
    private var input = sock.getInputStream(); private var output = sock.getOutputStream()
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
    fun setState(s: ConnectionState) { user.decoderState = s; user.encoderState = s }
    fun setCompression(t: Int) { threshold = t }

    fun send(w: PacketWrapper<*>) {
        val b = Unpooled.buffer(); w.buffer = b
        w.writeVarInt(w.nativePacketId); w.write()
        var body = ByteArray(b.readableBytes()); b.readBytes(body)
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
        c.skip(PacketType.Configuration.Server.SELECT_KNOWN_PACKS)
        c.send(WrapperConfigClientSelectKnownPacks(listOf(KnownPack("minecraft", "core", MC_VERSION))))
        repeat(Limbo.registries.size) { c.skip(PacketType.Configuration.Server.REGISTRY_DATA) }
        c.skip(PacketType.Configuration.Server.CONFIGURATION_END)
        c.send(WrapperConfigClientConfigurationEndAck()); c.setState(ConnectionState.PLAY)
        c.skip(PacketType.Play.Server.JOIN_GAME); c.skip(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)
        c.skip(PacketType.Play.Server.CHANGE_GAME_STATE); c.skip(PacketType.Play.Server.UPDATE_VIEW_POSITION)
        c.skip(PacketType.Play.Server.CHUNK_DATA)
        val chat = c.expect(PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) { WrapperPlayServerSystemChatMessage(it) }
        assertTrue(chat.message.toString().contains("Waiting for a backend"))
        return ok.uuid
    }

    @Test fun `offline login reaches limbo through configuration and play`() {
        server(false).use { s ->
            TestClient(s.port).use { c ->
                val id = joinLimbo(c, s.port, "Steve")
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
