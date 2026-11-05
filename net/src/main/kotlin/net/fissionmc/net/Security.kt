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
class NodeSecurity(private val privateKey: PrivateKey, private val trusted: (String) -> PublicKey?) {
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
            val (priv, pub) = Files.readAllLines(file).filter { it.isNotBlank() }
            return KeyPair(decodePublic(pub), decodePrivate(priv))
        }
        val pair = generate()
        file.parent?.let(Files::createDirectories)
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

internal fun handshakeTranscript(clientId: String, serverId: String, clientNonce: ByteArray, serverNonce: ByteArray, label: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(out).run {
        writeUTF("fission-handshake-v1"); writeUTF(label); writeUTF(clientId); writeUTF(serverId)
        writeInt(clientNonce.size); write(clientNonce); writeInt(serverNonce.size); write(serverNonce)
    }
    return out.toByteArray()
}
