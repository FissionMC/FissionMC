package net.fissionmc.net.pairing

import net.fissionmc.net.Keys
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Keyed hashes of the first-contact handshake (JDK HMAC-SHA256 only). */
internal object PairingCrypto {
    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) }; doFinal()
    }

    /** Two MAC keys, one per direction, derived from the one-time secret and the node id (HKDF-extract then expand, SHA-256). */
    class MacKeys(val acceptor: ByteArray, val initiator: ByteArray)
    fun macKeys(secret: ByteArray, nodeId: String): MacKeys {
        val prk = hmac(MessageDigest.getInstance("SHA-256").digest(("fission-pairing-v1|$nodeId").toByteArray()), secret)
        return MacKeys(hmac(prk, "acceptor mac\u0001".toByteArray()), hmac(prk, "initiator mac\u0001".toByteArray()))
    }

    fun mac(key: ByteArray, transcript: ByteArray, signature: ByteArray): ByteArray = hmac(key, java.nio.ByteBuffer.allocate(4).putInt(transcript.size).array(), transcript, signature)
    fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/** The backend's pinned master key (`.fission-master-key`, public data, written automatically after a successful pairing or rotation). */
class MasterPin(private val file: Path?, initial: PublicKey? = null) {
    @Volatile var key: PublicKey? = initial ?: file?.let { load(it) }
        private set
    val fingerprint: String? get() = key?.let(Fingerprint::of)

    @Synchronized fun save(k: PublicKey) {
        file?.let { Secrets.writeAtomic(it, "# FissionMC: the master's public key this backend trusts (written by pairing). Delete this file to pair again.\n${Keys.encode(k)}\n") }
        key = k
    }

    companion object {
        fun load(file: Path): PublicKey? = try {
            if (!Files.isRegularFile(file)) null else Keys.decodePublic(Files.readAllLines(file).map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("#") })
        } catch (e: Exception) { throw IllegalStateException("$file is not a valid pinned master key file (delete it to pair again): ${e.message}") }
    }
}

/**
 * Backend side of first contact. Holds the one-time pairing secret IN MEMORY ONLY (a restart makes a new one), renews it when it expires, and hands every new code to [announce]
 * (the console line). Pairing is wanted while no master key is pinned, or after the master said this node is not authorized (e.g. it was removed and is added again).
 */
class PairingClient(
    private val nodeId: String, private val identity: () -> PublicKey, val pin: MasterPin,
    private val lifetimeMillis: Long = 15 * 60_000L, private val clock: () -> Long = System::currentTimeMillis, private val rng: SecureRandom = SecureRandom(),
    private val announce: (code: String, expiresAtMillis: Long, nodeFingerprint: String, renewed: Boolean) -> Unit = { _, _, _, _ -> },
    private val onPaired: (master: PublicKey, firstPin: Boolean) -> Unit = { _, _ -> },
) {
    class Session(val secret: ByteArray, val expiresAt: Long) { override fun toString() = "Session(expiresAt=$expiresAt, secret=<hidden>)" }
    @Volatile private var current: Session? = null
    @Volatile private var requested = false

    /** True while the next connection has to use pairing mode. */
    fun wanted(): Boolean = pin.key == null || requested

    /** The pairing session to use now, a fresh one (announced) when there is none or it expired. */
    @Synchronized fun session(): Session {
        val s = current
        if (s != null && clock() < s.expiresAt) return s
        val n = Session(ByteArray(16).also(rng::nextBytes), clock() + lifetimeMillis)
        current = n
        announce(PairingCode(nodeId, identity(), n.secret, n.expiresAt).encode(), n.expiresAt, Fingerprint.of(identity()), s != null)
        return n
    }

    /** The master refused with "not authorized": from now on pair again (a pinned master key stays pinned and must match). */
    fun requestPairing() { requested = true }
    /** The master has no pending pairing for us although we asked: stop asking when a key is pinned. */
    fun dropRequest() { if (pin.key != null) { requested = false; current = null } }

    @Synchronized fun completed(master: PublicKey) {
        val first = pin.key == null
        if (first || !MessageDigest.isEqual(pin.key!!.encoded, master.encoded)) pin.save(master)
        current = null; requested = false
        onPaired(master, first)
    }
}

/** A pairing the admin pasted into the master: the node's key is authorized already, the secret waits for the node's first contact. */
class PendingPairing(val nodeId: String, val publicKey: PublicKey, internal val secret: ByteArray, val expiresAt: Long, val addedAt: Long) {
    override fun toString() = "PendingPairing(node=$nodeId, fingerprint=${Fingerprint.of(publicKey)}, secret=<hidden>)"
}

/**
 * Master side of pairing: the pending secrets (memory only, until first use or expiry) and the authorized keys file. [enabled] false = codes are refused and pairing handshakes are rejected;
 * at most [maxPending] pairings wait at the same time.
 */
class PairingStore(
    val authorized: AuthorizedKeys,
    @Volatile var enabled: Boolean = true,
    @Volatile var maxPending: Int = 8,
    private val maxLifetimeMillis: Long = 60 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pending = ConcurrentHashMap<String, PendingPairing>()
    /** Hashes of secrets that were used (until they would have expired): pasting a used code again is refused. Memory only. */
    private val used = ConcurrentHashMap<String, Long>()
    private fun secretId(secret: ByteArray) = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("fission-used|".toByteArray() + secret))

    sealed interface AddResult {
        class Added(val pending: PendingPairing, val replacedKey: Boolean) : AddResult
        class Refused(val reason: String) : AddResult
    }

    /** Validates [codeText] (checksum, expiry), authorizes the key at once and keeps the secret. A different key under an existing id needs [replace]. */
    fun add(codeText: String, replace: Boolean = false): AddResult {
        if (!enabled) return AddResult.Refused("pairing is switched off on this master (node.pairing: false, or `node pairing on`)")
        val code = when (val p = PairingCode.parse(codeText, clock())) { is PairingCode.Parsed.Ok -> p.code; is PairingCode.Parsed.Bad -> return AddResult.Refused(p.reason) }
        purge()
        if (used.containsKey(secretId(code.secret))) return AddResult.Refused("this code was already used for a pairing (a code works once): restart the backend to get a new one")
        if (code.nodeId == "master") return AddResult.Refused("the node id 'master' is reserved")
        val existing = authorized[code.nodeId]
        val sameKey = existing != null && MessageDigest.isEqual(existing.encoded, code.publicKey.encoded)
        if (existing != null && !sameKey && !replace) return AddResult.Refused("node ${code.nodeId} is already authorized with a different key (${Fingerprint.of(existing)}); if this backend really got a new identity use `node add <code> replace`")
        if (pending[code.nodeId] == null && pending.size >= maxPending) return AddResult.Refused("$maxPending pairings are already waiting (see `node pending`): wait for them to finish or expire")
        authorized.put(code.nodeId, code.publicKey)
        val p = PendingPairing(code.nodeId, code.publicKey, code.secret, minOf(code.expiresAtMillis, clock() + maxLifetimeMillis), clock())
        pending[code.nodeId] = p
        return AddResult.Added(p, existing != null && !sameKey)
    }

    fun pendingFor(nodeId: String): PendingPairing? { val p = pending[nodeId] ?: return null; if (clock() > p.expiresAt) { pending.remove(nodeId, p); return null }; return p }
    fun pendingList(): List<PendingPairing> { purge(); return pending.values.sortedBy { it.nodeId } }
    private fun purge() { val now = clock(); pending.entries.removeIf { now > it.value.expiresAt }; used.entries.removeIf { now > it.value } }

    /** Single use: true for exactly one caller per pending pairing. */
    fun consume(p: PendingPairing): Boolean = pending.remove(p.nodeId, p).also { if (it) used[secretId(p.secret)] = p.expiresAt }

    /** Removes the node's key and its pending pairing; true when there was a key. */
    fun remove(nodeId: String): Boolean { pending.remove(nodeId); return authorized.remove(nodeId) }
    fun dropPending(nodeId: String) { pending.remove(nodeId) }
}
