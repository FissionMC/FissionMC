package net.fissionmc.net

import java.nio.file.Files
import java.nio.file.Path
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Node identity for the mutual handshake: our Ed25519 private key plus a lookup of the public key we
 * trust for each node id. Both sides prove possession of their key over the same transcript.
 *
 * Optional parts (see .dev/docs/design/link-security.md): [publicKey] (sent in the Challenge for a specific mismatch message), [previous] (the master's key before a rotation, used
 * for backends that still pin it), [pairingStore] (master: pending first contacts), [pairingClient] (backend: first contact / re-pairing).
 */
class NodeSecurity(privateKey: PrivateKey, val authorizedHint: () -> String = { "" }, publicKey: PublicKey? = null, private val trusted: (String) -> PublicKey?) {
    @Volatile private var key: PrivateKey = privateKey
    @Volatile var publicKey: PublicKey? = publicKey; private set
    @Volatile var previous: KeyPair? = null
    @Volatile var pairingStore: net.fissionmc.net.pairing.PairingStore? = null
    @Volatile var pairingClient: net.fissionmc.net.pairing.PairingClient? = null
    /** Master: where a closed link to a removed node is reported to (set by the node admin). */
    class Signer(val priv: PrivateKey, val pub: PublicKey?, val isPrevious: Boolean)

    /** Swaps our identity (key rotation): handshakes from now on use [pair]; [keepPrevious] stays usable for peers that pin it. */
    fun replaceKey(pair: KeyPair, keepPrevious: KeyPair? = null) { key = pair.private; publicKey = pair.public; previous = keepPrevious }

    fun sign(transcript: ByteArray): ByteArray = sign(transcript, key)
    fun sign(transcript: ByteArray, with: PrivateKey): ByteArray = Signature.getInstance("Ed25519").run { initSign(with); update(transcript); sign() }

    /** The key to answer a Hello that carries [hint] (the fingerprint of the master key the backend pinned): the previous key while a backend still pins it, else the current one. */
    fun signerFor(hint: ByteArray): Signer {
        val prev = previous
        if (hint.isNotEmpty() && prev != null && java.security.MessageDigest.isEqual(hint, net.fissionmc.net.pairing.Fingerprint.hint(prev.public))) return Signer(prev.private, prev.public, true)
        return Signer(key, publicKey, false)
    }

    /** True only if [nodeId] is a known node and [sig] is its signature over [transcript]. */
    fun verify(nodeId: String, transcript: ByteArray, sig: ByteArray): Boolean {
        val key = trusted(nodeId) ?: return false
        return verify(key, transcript, sig)
    }
    fun verify(key: PublicKey, transcript: ByteArray, sig: ByteArray): Boolean = runCatching {
        Signature.getInstance("Ed25519").run { initVerify(key); update(transcript); verify(sig) }
    }.getOrDefault(false)

    fun knows(nodeId: String) = trusted(nodeId) != null
    fun trustedKey(nodeId: String): PublicKey? = trusted(nodeId)
}

object Keys {
    private val kf get() = KeyFactory.getInstance("Ed25519")

    fun generate(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun encode(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)
    fun encode(key: PrivateKey): String = Base64.getEncoder().encodeToString(key.encoded)
    fun decodePublic(s: String): PublicKey = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(s.trim())))
    fun decodePublicRaw(encoded: ByteArray): PublicKey = kf.generatePublic(X509EncodedKeySpec(encoded))
    fun decodePrivate(s: String): PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(s.trim())))

    /** Load a key file (line 1 private, line 2 public), creating a new keypair (owner-only, atomically) if missing. An existing file that other users can read is tightened to owner-only ([warn] says so). */
    fun loadOrCreate(file: Path, warn: (String) -> Unit = {}): KeyPair {
        if (Files.exists(file)) {
            net.fissionmc.net.pairing.Secrets.enforceOwnerOnly(file, warn)
            return load(file)
        }
        val pair = generate()
        net.fissionmc.net.pairing.Secrets.writeAtomic(file, encode(pair.private) + "\n" + encode(pair.public) + "\n")
        return pair
    }

    fun load(file: Path): KeyPair {
        val lines = Files.readAllLines(file).filter { it.isNotBlank() }
        require(lines.size >= 2) { "$file is not a FissionMC key file (expected 2 lines: private key, public key); delete it to have a new key made" }
        val (priv, pub) = lines
        return KeyPair(decodePublic(pub), decodePrivate(priv))
    }

    fun write(file: Path, pair: KeyPair) = net.fissionmc.net.pairing.Secrets.writeAtomic(file, encode(pair.private) + "\n" + encode(pair.public) + "\n")

    /** Parse an authorized-keys file: one `nodeId base64PublicKey` per line, `#` comments (a malformed line is skipped, not fatal). */
    fun loadAuthorized(file: Path): Map<String, PublicKey> = net.fissionmc.net.pairing.AuthorizedKeys(file).load()
}

/** Everything both sides agreed on so far; each signature and MAC covers all of it, so no field can be changed, dropped or replayed in another handshake. */
internal class HandshakeParams(
    val protocol: Int, val mode: Int, val mcVersion: String, val hint: ByteArray,
    val clientId: String, val serverId: String, val clientNonce: ByteArray, val serverNonce: ByteArray, val clientEph: ByteArray, val serverEph: ByteArray,
)

/**
 * The signed transcript: version tag, direction [label], protocol version, handshake mode, MC version, the pinned-key hint, both node ids, both nonces and both X25519 keys, then [extra]
 * (pairing: the master's key and MACs). Length prefixes everywhere: no two different inputs give the same bytes.
 */
internal fun handshakeTranscript(p: HandshakeParams, label: String, extra: ByteArray = ByteArray(0)): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(out).run {
        writeUTF("fission-handshake-v3"); writeUTF(label); writeInt(p.protocol); writeInt(p.mode); writeUTF(p.mcVersion)
        writeInt(p.hint.size); write(p.hint); writeUTF(p.clientId); writeUTF(p.serverId)
        writeInt(p.clientNonce.size); write(p.clientNonce); writeInt(p.serverNonce.size); write(p.serverNonce)
        writeInt(p.clientEph.size); write(p.clientEph); writeInt(p.serverEph.size); write(p.serverEph)   // the X25519 keys are signed too: nobody in the middle can swap them
        writeInt(extra.size); write(extra)
    }
    return out.toByteArray()
}

/**
 * Encryption of a node link after the handshake: X25519 key agreement between the two ephemeral keys of the handshake (both covered by the Ed25519 signatures),
 * HKDF-SHA256 into one AES-256-GCM key per direction, a 64 bit frame counter as the nonce. Every frame is encrypted and authenticated; a changed, replayed, dropped
 * or reordered frame fails the tag check and closes the link.
 */
internal object LinkCrypto {
    fun newEphemeral(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    /** (initiator -> acceptor key, acceptor -> initiator key) */
    fun deriveKeys(mine: PrivateKey, peerEncoded: ByteArray, context: ByteArray): Pair<ByteArray, ByteArray> {
        val peer = KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(peerEncoded))
        val secret = javax.crypto.KeyAgreement.getInstance("X25519").run { init(mine); doPhase(peer, true); generateSecret() }
        val prk = hmac(MessageDigest.getInstance("SHA-256").digest(context), secret)
        return hmac(prk, "fission-link i2a\u0001".toByteArray()) to hmac(prk, "fission-link a2i\u0001".toByteArray())
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    internal const val MAX_FRAMES = 1L shl 62

    class Sealer(key: ByteArray, private val mode: Int) {
        private val k = javax.crypto.spec.SecretKeySpec(key, "AES")
        private val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        internal var counter = 0L
        fun apply(data: ByteArray): ByteArray {
            // a key is never used past 2^62 frames (the counter is the nonce: it must never repeat or wrap); the link is closed and reconnects with fresh keys
            check(counter < MAX_FRAMES) { "frame counter exhausted: the link must be re-keyed" }
            val iv = java.nio.ByteBuffer.allocate(12).putInt(0).putLong(counter++).array()
            cipher.init(mode, k, javax.crypto.spec.GCMParameterSpec(128, iv))
            return cipher.doFinal(data)
        }
    }
}
