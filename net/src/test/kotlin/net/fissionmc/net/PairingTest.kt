package net.fissionmc.net

import net.fissionmc.net.pairing.*
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Pairing (first contact with a one-time secret), pinned key mutual auth, downgrade and impersonation attempts. */
class PairingTest {
    private val dir: Path = Files.createTempDirectory("pairing-test")
    private var now = 1_800_000_000_000L
    private val masterKeys = Keys.generate()
    private val beKeys = Keys.generate()
    private val store = PairingStore(AuthorizedKeys(dir.resolve("authorized")), clock = { now })
    private val codes = ArrayList<String>()
    private val opened = ArrayList<AutoCloseable>()
    init { ConsoleOut.override = { } }
    @AfterTest fun cleanup() { opened.forEach { runCatching { it.close() } }; ConsoleOut.override = null; Audit.sink = { println(it) } }

    private fun masterSec(keys: KeyPair = masterKeys, st: PairingStore = store) =
        NodeSecurity(keys.private, publicKey = keys.public) { st.authorized[it] }.also { it.pairingStore = st }
    private fun backendSec(id: String = "be-1", keys: KeyPair = beKeys, pin: MasterPin = MasterPin(dir.resolve(".fission-master-key"))): NodeSecurity {
        val sec = NodeSecurity(keys.private, publicKey = keys.public) { if (it == "master") pin.key else null }
        sec.pairingClient = PairingClient(id, { keys.public }, pin, clock = { now }, announce = { code, _, _, _ -> codes += code })
        return sec
    }
    private fun addr() = NodeAddress.Local("pair-" + System.nanoTime())
    private fun transport(id: String, role: NodeRole, sec: NodeSecurity?) = NettyTransport(LocalNode(id, role, "26.3"), security = sec).also { opened += it }
    private fun bindMaster(sec: NodeSecurity): Pair<NodeAddress, NettyTransport> { val a = addr(); val t = transport("master", NodeRole.MASTER, sec); opened += t.bind(a) {}; return a to t }
    private fun connect(be: NettyTransport, a: NodeAddress): PeerInfo = be.connect(a).get(5, TimeUnit.SECONDS).ready.get(5, TimeUnit.SECONDS)
    private fun failure(be: NettyTransport, a: NodeAddress): String { val e = assertFailsWith<ExecutionException> { connect(be, a) }; return e.cause!!.message ?: "" }

    @Test fun `pairing succeeds, pins the master key, consumes the secret and normal pinned auth works afterwards`() {
        val (a, _) = bindMaster(masterSec())
        val pin = MasterPin(dir.resolve(".fission-master-key")); val sec = backendSec(pin = pin)
        sec.pairingClient!!.session()
        assertEquals(1, codes.size)
        val r = store.add(codes[0]); assertIs<PairingStore.AddResult.Added>(r)
        assertEquals(beKeys.public, store.authorized["be-1"], "the key is authorized at once, before the node ever connected")
        val peer = connect(transport("be-1", NodeRole.BACKEND, sec), a)
        assertEquals("master", peer.nodeId)
        assertEquals(masterKeys.public, pin.key); assertEquals(masterKeys.public, MasterPin.load(dir.resolve(".fission-master-key")), "pinned and written to the file")
        assertNull(store.pendingFor("be-1"), "single use")
        // after pairing: plain pinned mutual authentication, no pairing mode any more
        val sec2 = backendSec(pin = MasterPin(dir.resolve(".fission-master-key")))
        assertFalse(sec2.pairingClient!!.wanted())
        assertEquals("master", connect(transport("be-1", NodeRole.BACKEND, sec2), a).nodeId)
        assertTrue((store.add(codes[0]) as PairingStore.AddResult.Refused).reason.contains("already used"), "the same code cannot be pasted again")
    }

    @Test fun `a master that does not know the secret cannot be paired with (man in the middle without the code)`() {
        // the attacker has a key of its own, knows the node id and the public values, but not the secret
        val evilKeys = Keys.generate(); val evilStore = PairingStore(AuthorizedKeys(dir.resolve("evil-auth")), clock = { now })
        val (a, _) = bindMaster(masterSec(evilKeys, evilStore))
        val pin = MasterPin(dir.resolve(".fission-master-key")); val sec = backendSec(pin = pin); sec.pairingClient!!.session()
        // the attacker pastes a code with ITS guess of the secret
        val guess = PairingCode("be-1", beKeys.public, ByteArray(16) { 7 }, now + 600_000).encode()
        assertIs<PairingStore.AddResult.Added>(evilStore.add(guess))
        val msg = failure(transport("be-1", NodeRole.BACKEND, sec), a)
        assertTrue(msg.contains("does not prove the pairing secret"), msg)
        assertNull(pin.key, "nothing is pinned after a failed pairing"); assertFalse(Files.exists(dir.resolve(".fission-master-key")))
    }

    @Test fun `a master without a pending pairing refuses and the backend learns nothing`() {
        val (a, _) = bindMaster(masterSec())
        val sec = backendSec(); sec.pairingClient!!.session()
        val msg = failure(transport("be-1", NodeRole.BACKEND, sec), a)
        assertTrue(msg.contains("no pending pairing"), msg)
        assertNull(sec.trustedKey("master"))
    }

    @Test fun `a wrong or tampered code is refused by the checksum, an expired code is refused, the secret is not in toString`() {
        val sec = backendSec(); sec.pairingClient!!.session()
        val code = codes[0]
        val bad = code.replaceFirst(code[10], if (code[10] == 'A') 'B' else 'A')
        assertTrue((store.add(bad) as PairingStore.AddResult.Refused).reason.contains("checksum"))
        assertTrue((store.add("hello world") as PairingStore.AddResult.Refused).reason.contains("not a FissionMC pairing code"))
        assertIs<PairingStore.AddResult.Added>(store.add(code.lowercase().replace("-", " ")), "case, spaces and dashes do not matter")
        now += 16 * 60_000
        assertTrue((store.add(code) as PairingStore.AddResult.Refused).reason.contains("expired"))
        val parsed = (PairingCode.parse(code, now - 16 * 60_000) as PairingCode.Parsed.Ok).code
        assertFalse(parsed.toString().contains(Base32.encode(parsed.secret)))
        assertEquals(12 + 2, parsed.fingerprint.length)
    }

    @Test fun `a pairing that expires before first contact is refused, and a used one cannot be used again`() {
        val (a, _) = bindMaster(masterSec())
        val sec = backendSec(); sec.pairingClient!!.session()
        val added = (store.add(codes[0]) as PairingStore.AddResult.Added).pending
        now += 61 * 60_000
        assertNull(store.pendingFor("be-1"), "expired")
        assertTrue(failure(transport("be-1", NodeRole.BACKEND, backendSec()), a).contains("no pending pairing"))
        // single use
        now -= 61 * 60_000
        val again = (store.add(codes[0]) as PairingStore.AddResult.Added).pending
        assertTrue(store.consume(again)); assertFalse(store.consume(again)); assertFalse(store.consume(added))
        assertTrue((store.add(codes[0]) as PairingStore.AddResult.Refused).reason.contains("already used"))
    }

    @Test fun `pairing switched off refuses codes and handshakes`() {
        val (a, _) = bindMaster(masterSec())
        val sec = backendSec(); sec.pairingClient!!.session()
        store.enabled = false
        assertTrue((store.add(codes[0]) as PairingStore.AddResult.Refused).reason.contains("switched off"))
        assertTrue(failure(transport("be-1", NodeRole.BACKEND, sec), a).contains("disabled"))
    }

    @Test fun `the number of pending pairings is limited and a different key under an existing id needs replace`() {
        val small = PairingStore(AuthorizedKeys(dir.resolve("a2")), maxPending = 2, clock = { now })
        fun code(id: String, k: KeyPair = Keys.generate()) = PairingCode(id, k.public, ByteArray(16) { 1 }, now + 600_000).encode()
        assertIs<PairingStore.AddResult.Added>(small.add(code("n1"))); assertIs<PairingStore.AddResult.Added>(small.add(code("n2")))
        assertTrue((small.add(code("n3")) as PairingStore.AddResult.Refused).reason.contains("already waiting"))
        assertTrue((small.add(code("n1")) as PairingStore.AddResult.Refused).reason.contains("different key"))
        val r = small.add(code("n1") + " ", replace = true); assertIs<PairingStore.AddResult.Added>(r); assertTrue(r.replacedKey)
        assertTrue((small.add(code("master")) as PairingStore.AddResult.Refused).reason.contains("reserved"))
    }

    @Test fun `a pinned key that does not match fails closed with a specific message`() {
        val st = PairingStore(AuthorizedKeys(dir.resolve("a3")), clock = { now }); st.authorized.put("be-1", beKeys.public)
        val (a, _) = bindMaster(masterSec(masterKeys, st))
        val other = Keys.generate()
        val sec = backendSec(pin = MasterPin(null, other.public))
        val msg = failure(transport("be-1", NodeRole.BACKEND, sec), a)
        assertTrue(msg.contains("MASTER KEY MISMATCH"), msg)
        assertTrue(msg.contains(Fingerprint.of(other.public)) && msg.contains(Fingerprint.of(masterKeys.public)), "both fingerprints are named: $msg")
    }

    @Test fun `unknown node and wrong signature get the same answer and no key file path or id list`() {
        val st = PairingStore(AuthorizedKeys(dir.resolve("secret-authorized-path")), clock = { now }); st.authorized.put("other-node", Keys.generate().public)
        val sec = NodeSecurity(masterKeys.private, { "the master reads ${dir.resolve("secret-authorized-path")} (ids: other-node)" }, masterKeys.public) { st.authorized[it] }
        val (a, _) = bindMaster(sec)
        st.authorized.put("be-1", beKeys.public)
        val wrongKey = failure(transport("be-1", NodeRole.BACKEND, backendSec(keys = Keys.generate(), pin = MasterPin(null, masterKeys.public))), a)
        val unknown = failure(transport("be-9", NodeRole.BACKEND, backendSec(id = "be-9", pin = MasterPin(null, masterKeys.public))), a)
        assertTrue(wrongKey.startsWith("not authorized") && unknown.startsWith("not authorized"), "$wrongKey / $unknown")
        assertEquals(wrongKey, unknown, "no oracle for which node ids exist")
        assertFalse(unknown.contains("secret-authorized-path") || unknown.contains("other-node"))
    }

    @Test fun `a node without link security cannot connect to a secure master and a secure node not to an insecure master`() {
        val st = PairingStore(AuthorizedKeys(dir.resolve("a4")), clock = { now }); st.authorized.put("be-1", beKeys.public)
        val (a, _) = bindMaster(masterSec(masterKeys, st))
        val msg = failure(transport("be-1", NodeRole.BACKEND, null), a)
        assertTrue(msg.contains("link security") || msg.contains("lost") || msg.contains("link key"), msg)
        val open = addr(); opened += transport("master", NodeRole.MASTER, null).bind(open) {}
        val m2 = failure(transport("be-1", NodeRole.BACKEND, backendSec(pin = MasterPin(null, masterKeys.public))), open)
        assertTrue(m2.contains("failed authentication") || m2.contains("no link key"), m2)
    }

    @Test fun `pairing works over real TCP with the pre-auth guard in front, and the guard releases the slot`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val guard = PreAuthGuard(maxHalfOpen = 2, maxPerMinute = 100)
        val mt = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"), security = masterSec(), guard = guard).also { opened += it }
        opened += mt.bind(NodeAddress.Tcp("127.0.0.1", port)) {}
        val pin = MasterPin(dir.resolve(".tcp-pin")); val sec = backendSec(pin = pin); sec.pairingClient!!.session()
        assertIs<PairingStore.AddResult.Added>(store.add(codes[0]))
        val be = transport("be-1", NodeRole.BACKEND, sec)
        assertEquals("master", be.connect(NodeAddress.Tcp("127.0.0.1", port)).get(5, TimeUnit.SECONDS).ready.get(5, TimeUnit.SECONDS).nodeId)
        assertEquals(masterKeys.public, pin.key)
        // two silent connections fill the half-open slots of this address; a third is closed at once
        val socks = List(3) { java.net.Socket("127.0.0.1", port).also { it.soTimeout = 1500 } }
        val closed = socks.count { sk -> try { sk.getInputStream().read() == -1 } catch (_: java.net.SocketTimeoutException) { false } catch (_: java.io.IOException) { true } }
        assertEquals(1, closed, "of three silent connections from one address exactly the third is refused")
        socks.forEach { it.close() }
    }

    @Test fun `the transcript changes when any bound field changes`() {
        fun p(protocol: Int = 3, mode: Int = 0, mc: String = "26.3", hint: ByteArray = ByteArray(0), c: String = "a", s: String = "b", cn: Byte = 1, sn: Byte = 2, ce: Byte = 3, se: Byte = 4) =
            HandshakeParams(protocol, mode, mc, hint, c, s, byteArrayOf(cn), byteArrayOf(sn), byteArrayOf(ce), byteArrayOf(se))
        val base = handshakeTranscript(p(), "initiator")
        val variants = listOf(handshakeTranscript(p(), "acceptor"), handshakeTranscript(p(protocol = 2), "initiator"), handshakeTranscript(p(mode = 1), "initiator"), handshakeTranscript(p(mc = "26.4"), "initiator"),
            handshakeTranscript(p(hint = byteArrayOf(1)), "initiator"), handshakeTranscript(p(c = "x"), "initiator"), handshakeTranscript(p(s = "x"), "initiator"), handshakeTranscript(p(cn = 9), "initiator"),
            handshakeTranscript(p(sn = 9), "initiator"), handshakeTranscript(p(ce = 9), "initiator"), handshakeTranscript(p(se = 9), "initiator"), handshakeTranscript(p(), "initiator", byteArrayOf(1)))
        variants.forEach { assertFalse(base.contentEquals(it)) }
        assertEquals(variants.size, variants.map { java.util.Base64.getEncoder().encodeToString(it) }.toSet().size)
    }

    @Test fun `a signature from one direction or one handshake does not verify in another (reflection and replay)`() {
        val sec = NodeSecurity(beKeys.private) { null }
        fun p(sn: Byte) = HandshakeParams(3, 0, "26.3", ByteArray(0), "be-1", "master", byteArrayOf(1), byteArrayOf(sn), byteArrayOf(3), byteArrayOf(4))
        val sig = sec.sign(handshakeTranscript(p(2), "initiator"))
        assertTrue(sec.verify(beKeys.public, handshakeTranscript(p(2), "initiator"), sig))
        assertFalse(sec.verify(beKeys.public, handshakeTranscript(p(2), "acceptor"), sig), "reflected into the other direction")
        assertFalse(sec.verify(beKeys.public, handshakeTranscript(p(5), "initiator"), sig), "replayed with another nonce of the acceptor")
    }

    @Test fun `the frame counter never wraps and a failed decryption is an error, not an answer`() {
        val (i2a, _) = LinkCrypto.deriveKeys(LinkCrypto.newEphemeral().private, LinkCrypto.newEphemeral().public.encoded, "x".toByteArray())
        val seal = LinkCrypto.Sealer(i2a, javax.crypto.Cipher.ENCRYPT_MODE)
        seal.counter = LinkCrypto.MAX_FRAMES
        assertFailsWith<IllegalStateException> { seal.apply(ByteArray(1)) }
        val open = LinkCrypto.Sealer(i2a, javax.crypto.Cipher.DECRYPT_MODE)
        assertFails { open.apply(ByteArray(40)) }
    }

    @Test fun `master key rotation signed with the old key is accepted, anything else is not`() {
        val old = masterKeys; val fresh = Keys.generate(); val evil = Keys.generate()
        val good = Rotation.masterRotation("be-1", now, old.private, fresh)
        assertEquals(fresh.public, Rotation.verifyMasterRotation("be-1", old.public, good))
        assertNull(Rotation.verifyMasterRotation("be-2", old.public, good), "bound to the node id")
        assertNull(Rotation.verifyMasterRotation("be-1", evil.public, good), "not signed by the pinned key")
        assertNull(Rotation.verifyMasterRotation("be-1", old.public, Rotation.masterRotation("be-1", now, evil.private, fresh)))
        val nodeRot = Rotation.nodeRotation("be-1", 5, beKeys.private, fresh)
        assertEquals(fresh.public, Rotation.verifyNodeRotation("be-1", 5, beKeys.public, nodeRot))
        assertNull(Rotation.verifyNodeRotation("be-1", 6, beKeys.public, nodeRot), "request id")
        assertNull(Rotation.verifyNodeRotation("be-2", 5, beKeys.public, nodeRot), "node id")
    }

    @Test fun `a backend that still pins the previous master key is answered with it and flagged for rotation`() {
        val fresh = Keys.generate()
        val st = PairingStore(AuthorizedKeys(dir.resolve("a5")), clock = { now }); st.authorized.put("be-1", beKeys.public)
        val sec = masterSec(fresh, st); sec.previous = masterKeys
        val a = addr(); val seen = java.util.concurrent.LinkedBlockingQueue<PeerInfo>()
        val mt = transport("master", NodeRole.MASTER, sec); opened += mt.bind(a) { l -> l.ready.thenAccept { seen += it } }
        val peer = connect(transport("be-1", NodeRole.BACKEND, backendSec(pin = MasterPin(null, masterKeys.public))), a)
        assertEquals("master", peer.nodeId)
        assertTrue(seen.poll(5, TimeUnit.SECONDS)!!.usedPreviousKey)
        val seen2 = connect(transport("be-1", NodeRole.BACKEND, backendSec(pin = MasterPin(null, fresh.public))), a)
        assertEquals("master", seen2.nodeId)
        assertFalse(seen.poll(5, TimeUnit.SECONDS)!!.usedPreviousKey)
    }

    @Test fun `key files are owner only, loose ones are tightened, the authorized file is written atomically owner only`() {
        val f = dir.resolve("fission-node.key")
        Keys.loadOrCreate(f)
        if (Files.getFileStore(f).supportsFileAttributeView("posix")) {
            assertTrue(Secrets.isOwnerOnly(f))
            Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            val warnings = ArrayList<String>(); Keys.loadOrCreate(f) { warnings += it }
            assertTrue(Secrets.isOwnerOnly(f) && warnings.size == 1 && warnings[0].contains("readable by other users"))
            val keys = AuthorizedKeys(dir.resolve("auth2")); keys.put("n1", beKeys.public)
            assertTrue(Secrets.isOwnerOnly(dir.resolve("auth2")))
            val pin = MasterPin(dir.resolve("pin2")); pin.save(masterKeys.public); assertTrue(Secrets.isOwnerOnly(dir.resolve("pin2")))
        }
        assertEquals(setOf("fission-node.key"), Files.list(dir).map { it.fileName.toString() }.filter { it.startsWith("fission-node") }.toList().toSet(), "no temp file is left behind")
    }

    @Test fun `authorized keys edit rename remove, a malformed line does not lock the others out`() {
        val f = dir.resolve("auth3"); val warnings = ArrayList<String>()
        Files.writeString(f, "# comment\nbroken line here\nn1 ${Keys.encode(beKeys.public)}\nn2 notbase64!!\n")
        val k = AuthorizedKeys(f) { warnings += it }
        assertEquals(setOf("n1"), k.load().keys); assertEquals(2, warnings.size)
        k.put("n3", masterKeys.public); assertTrue(k.rename("n3", "n4")); assertNull(k["n3"]); assertNotNull(k["n4"]); assertFalse(k.rename("n4", "n1"))
        assertTrue(k.remove("n1")); assertFalse(k.remove("n1")); assertNull(k["n1"])
        assertTrue(Files.readString(f).contains("# comment"), "comments are kept")
    }

    @Test fun `pre-auth guard limits half open connections, the rate and bans repeated failures`() {
        var t = 0L; val g = PreAuthGuard(maxHalfOpen = 2, maxPerMinute = 4, failLimit = 3, banMs = 10_000, clock = { t })
        Audit.sink = { }
        assertTrue(g.admit("1.1.1.1")); assertTrue(g.admit("1.1.1.1")); assertFalse(g.admit("1.1.1.1"), "third half open")
        assertTrue(g.admit("2.2.2.2"), "other addresses are not affected")
        g.released("1.1.1.1"); assertTrue(g.admit("1.1.1.1")); g.released("1.1.1.1"); assertTrue(g.admit("1.1.1.1")); g.released("1.1.1.1"); g.released("1.1.1.1")
        assertFalse(g.admit("1.1.1.1"), "per minute limit (4 attempts)")
        t += 61_000; assertTrue(g.admit("1.1.1.1")); g.released("1.1.1.1")
        repeat(3) { g.failed("3.3.3.3") }; assertFalse(g.admit("3.3.3.3"), "banned"); t += 10_001; assertTrue(g.admit("3.3.3.3"))
    }

    @Test fun `audit lines are throttled and cleaned`() {
        val lines = ArrayList<String>(); Audit.sink = { lines += it }; var t = 100_000L; Audit.clock = { t }
        try {
            repeat(50) { Audit.failure("src", "bad login\nforged line") }
            assertEquals(1, lines.size); assertFalse(lines[0].contains("\n"), "control characters are removed from peer text")
            assertEquals("a?b", Audit.clean("a\nb"))
            t += 11_000; Audit.failure("src", "bad"); assertTrue(lines[1].contains("49 similar"))
        } finally { Audit.clock = System::currentTimeMillis }
    }
}
