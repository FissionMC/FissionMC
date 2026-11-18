package net.fissionmc.net.pairing

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/** Crockford style base 32: no I, L, O, U (typed as 1, 1, 0, V), case insensitive, dashes and white space are ignored. */
object Base32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    fun encode(data: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (b in data) { buf = (buf shl 8) or (b.toInt() and 0xFF); bits += 8; while (bits >= 5) { sb.append(ALPHABET[(buf shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(ALPHABET[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
    /** Null on a character outside the alphabet. */
    fun decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (raw in text) {
            val c = when (val u = raw.uppercaseChar()) { 'O' -> '0'; 'I', 'L' -> '1'; 'U' -> 'V'; else -> u }
            val v = ALPHABET.indexOf(c); if (v < 0) return null
            buf = (buf shl 5) or v; bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}

/** What both consoles show so an admin can confirm by eye that the key on the master is the key of the backend: 60 bits of SHA-256 of the public key, `XXXX-XXXX-XXXX`. */
object Fingerprint {
    fun of(key: PublicKey): String = of(key.encoded)
    fun of(encoded: ByteArray): String = Base32.encode(MessageDigest.getInstance("SHA-256").digest(encoded)).take(12).chunked(4).joinToString("-")
    /** 8 bytes of the same hash: the `masterHint` of a Hello. */
    fun hint(key: PublicKey): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.encoded).copyOf(8)
}

/**
 * The pairing code a backend prints and the master console takes: one line, `FP1-XXXXX-XXXXX-...`.
 * Payload: version(1) | expiry in minutes since the epoch (4) | pairing secret (16) | Ed25519 public key (32 raw bytes) | id length (1) | node id | checksum (4 = SHA-256 prefix of all before).
 * The checksum catches typing and copy errors, it is NOT a security feature; the security of first contact rests on the 128 bit secret (see the design note).
 */
class PairingCode(val nodeId: String, val publicKey: PublicKey, val secret: ByteArray, val expiresAtMillis: Long) {
    val fingerprint: String get() = Fingerprint.of(publicKey)
    override fun toString() = "PairingCode(node=$nodeId, fingerprint=$fingerprint, secret=<hidden>)"

    fun encode(): String {
        val raw = publicKey.encoded.takeLast(32).toByteArray()
        require(PREFIX.size + raw.size == publicKey.encoded.size) { "not an Ed25519 key" }
        val id = nodeId.toByteArray(Charsets.UTF_8); require(id.size in 1..48)
        val minutes = expiresAtMillis / 60_000
        val body = java.io.ByteArrayOutputStream().also { o -> java.io.DataOutputStream(o).run { writeByte(VERSION); writeInt(minutes.toInt()); write(secret); write(raw); writeByte(id.size); write(id) } }.toByteArray()
        val text = Base32.encode(body + check(body))
        return "FP1-" + text.chunked(5).joinToString("-")
    }

    sealed interface Parsed {
        class Ok(val code: PairingCode) : Parsed
        class Bad(val reason: String) : Parsed
    }

    companion object {
        private const val VERSION = 1
        /** SPKI prefix of an Ed25519 public key (the 32 raw bytes follow). */
        private val PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        private fun check(body: ByteArray) = MessageDigest.getInstance("SHA-256").digest("fission-pair-check".toByteArray() + body).copyOf(4)

        fun parse(text: String, nowMillis: Long = System.currentTimeMillis()): Parsed {
            val t = text.filter { !it.isWhitespace() && it != '-' }
            if (!t.startsWith("FP1", ignoreCase = true)) return Parsed.Bad("this is not a FissionMC pairing code (it starts with FP1-)")
            val bytes = Base32.decode(t.substring(3)) ?: return Parsed.Bad("the code contains a character that cannot be in a pairing code (check for a copy error)")
            if (bytes.size < 1 + 4 + 16 + 32 + 1 + 1 + 4) return Parsed.Bad("the code is too short: the whole line has to be pasted")
            // base 32 pads the last byte with zero bits: anything beyond the data is ignored, so the checksum is read at the position the length implies
            val idLen = bytes.getOrNull(1 + 4 + 16 + 32)?.toInt()?.and(0xFF) ?: return Parsed.Bad("the code is damaged")
            val used = 1 + 4 + 16 + 32 + 1 + idLen
            if (idLen !in 1..48 || bytes.size < used + 4) return Parsed.Bad("the code is damaged or incomplete: the whole line has to be pasted")
            val data = bytes.copyOfRange(0, used)
            if (!MessageDigest.isEqual(check(data), bytes.copyOfRange(used, used + 4))) return Parsed.Bad("checksum mismatch: the code was changed or cut while copying, copy the whole line again")
            if (data[0].toInt() != VERSION) return Parsed.Bad("unsupported pairing code version ${data[0]}")
            val buf = java.nio.ByteBuffer.wrap(data); buf.get()
            val minutes = buf.getInt().toLong() and 0xFFFFFFFFL
            val secret = ByteArray(16).also { buf.get(it) }; val raw = ByteArray(32).also { buf.get(it) }
            val id = String(data, 1 + 4 + 16 + 32 + 1, idLen, Charsets.UTF_8)
            if (!NODE_ID.matches(id)) return Parsed.Bad("the node id in the code has characters that are not allowed")
            val key = try { KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(PREFIX + raw)) } catch (e: Exception) { return Parsed.Bad("the public key in the code is invalid") }
            val expires = minutes * 60_000
            if (nowMillis > expires) return Parsed.Bad("the code expired ${(nowMillis - expires) / 60_000} min ago: restart the backend (or wait for it to print a new code) and paste the new one")
            return Parsed.Ok(PairingCode(id, key, secret, expires))
        }

        val NODE_ID = Regex("[A-Za-z0-9._-]{1,48}")
    }
}
