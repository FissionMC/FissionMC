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
 */
class NodeSecurity(private val privateKey: PrivateKey, val authorizedHint: () -> String = { "" }, private val trusted: (String) -> PublicKey?) {
    fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(privateKey); update(transcript); sign()
    }

    /** True only if [nodeId] is a known node and [sig] is its signature over [transcript]. */
    fun verify(nodeId: String, transcript: ByteArray, sig: ByteArray): Boolean {
        val key = trusted(nodeId) ?: return false
        return runCatching {
            Signature.getInstance("Ed25519").run { initVerify(key); update(transcript); verify(sig) }
        }.getOrDefault(false)
    }

    fun knows(nodeId: String) = trusted(nodeId) != null
}

object Keys {
    private val kf get() = KeyFactory.getInstance("Ed25519")

    fun generate(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun encode(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)
    fun encode(key: PrivateKey): String = Base64.getEncoder().encodeToString(key.encoded)
    fun decodePublic(s: String): PublicKey = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(s.trim())))
    fun decodePrivate(s: String): PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(s.trim())))

    /** Load a key file (line 1 private, line 2 public), creating a new keypair (owner-only) if missing. */
    fun loadOrCreate(file: Path): KeyPair {
        if (Files.exists(file)) {
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            require(lines.size >= 2) { "$file is not a FissionMC key file (expected 2 lines: private key, public key); delete it to have a new key made" }
            val (priv, pub) = lines
            return KeyPair(decodePublic(pub), decodePrivate(priv))
        }
        val pair = generate()
        file.parent?.let(Files::createDirectories)
        // created owner-only from the start (where the file system knows permissions), not written first and locked afterwards
        runCatching { Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))) }
        Files.writeString(file, encode(pair.private) + "\n" + encode(pair.public) + "\n")
        runCatching { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        return pair
    }

    /** Parse an authorized-keys file: one `nodeId base64PublicKey` per line, `#` comments. */
    fun loadAuthorized(file: Path): Map<String, PublicKey> =
        Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate {
            val (id, key) = it.split(Regex("\\s+"), limit = 2)
            id to decodePublic(key)
        }
}

internal fun handshakeTranscript(clientId: String, serverId: String, clientNonce: ByteArray, serverNonce: ByteArray, clientEph: ByteArray, serverEph: ByteArray, label: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(out).run {
        writeUTF("fission-handshake-v2"); writeUTF(label); writeUTF(clientId); writeUTF(serverId)
        writeInt(clientNonce.size); write(clientNonce); writeInt(serverNonce.size); write(serverNonce)
        writeInt(clientEph.size); write(clientEph); writeInt(serverEph.size); write(serverEph)   // the X25519 keys are signed too: nobody in the middle can swap them
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

    class Sealer(key: ByteArray, private val mode: Int) {
        private val k = javax.crypto.spec.SecretKeySpec(key, "AES")
        private val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        private var counter = 0L
        fun apply(data: ByteArray): ByteArray {
            val iv = java.nio.ByteBuffer.allocate(12).putInt(0).putLong(counter++).array()
            cipher.init(mode, k, javax.crypto.spec.GCMParameterSpec(128, iv))
            return cipher.doFinal(data)
        }
    }
}
