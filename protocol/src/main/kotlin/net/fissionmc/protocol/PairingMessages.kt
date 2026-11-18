package net.fissionmc.protocol

/**
 * Pairing and key rotation messages, wire ids 131-136 (131-150 are reserved for the node link security).
 *
 * First contact (see .dev/docs/design/link-security.md): the backend sends a [Hello] with mode [HELLO_PAIRING]; the master answers with [PairChallenge] instead of
 * [Challenge]; the backend answers with [PairAuth] instead of [Auth]. The rotation messages travel over an authenticated, encrypted link only.
 */

/** Master -> backend in pairing mode: its identity key, a signature over the transcript with that key, and a MAC over it with the one-time pairing secret. */
class PairChallenge(val nodeId: String, val nonce: ByteArray, val ephemeral: ByteArray, val masterPublic: ByteArray, val signature: ByteArray, val mac: ByteArray) : NodeMessage

/** Backend -> master in pairing mode: Ed25519 signature with its identity key over the transcript (which contains the master's answer) and a MAC with the pairing secret. */
class PairAuth(val signature: ByteArray, val mac: ByteArray) : NodeMessage

/** Master -> backend: generate a new identity key and send [KeyRotation]. */
class RotateKeyRequest(val reqId: Long) : NodeMessage

/** Backend -> master: the new public key, signed by the OLD key and by the NEW key (proof of possession) over `fission-rotate-node|nodeId|reqId|newPublic`. */
class KeyRotation(val reqId: Long, val newPublic: ByteArray, val signatureOld: ByteArray, val signatureNew: ByteArray) : NodeMessage

/** Master -> backend: the rotation was stored (or not). */
class KeyRotationAck(val reqId: Long, val ok: Boolean, val message: String) : NodeMessage

/** Master -> backend: the master has a new identity key; signed by the OLD master key and by the NEW one over `fission-rotate-master|nodeId|issuedAtMillis|newPublic`. */
class MasterKeyRotation(val issuedAt: Long, val newPublic: ByteArray, val signatureOld: ByteArray, val signatureNew: ByteArray) : NodeMessage

fun MessageRegistry.registerPairingMessages(): MessageRegistry = this
    .register<PairChallenge>(131, { m, b -> b.writeString(m.nodeId, 64).writeByteArray(m.nonce, 64).writeByteArray(m.ephemeral, 128).writeByteArray(m.masterPublic, 64).writeByteArray(m.signature, 128).writeByteArray(m.mac, 64) },
        { b -> PairChallenge(b.readString(64), b.readByteArray(64), b.readByteArray(128), b.readByteArray(64), b.readByteArray(128), b.readByteArray(64)) })
    .register<PairAuth>(132, { m, b -> b.writeByteArray(m.signature, 128).writeByteArray(m.mac, 64) }, { b -> PairAuth(b.readByteArray(128), b.readByteArray(64)) })
    .register<RotateKeyRequest>(133, { m, b -> b.writeLong(m.reqId) }, { b -> RotateKeyRequest(b.readLong()) })
    .register<KeyRotation>(134, { m, b -> b.writeLong(m.reqId).writeByteArray(m.newPublic, 64).writeByteArray(m.signatureOld, 128).writeByteArray(m.signatureNew, 128) },
        { b -> KeyRotation(b.readLong(), b.readByteArray(64), b.readByteArray(128), b.readByteArray(128)) })
    .register<KeyRotationAck>(135, { m, b -> b.writeLong(m.reqId).writeBoolean(m.ok).writeString(m.message, 256) }, { b -> KeyRotationAck(b.readLong(), b.readBoolean(), b.readString(256)) })
    .register<MasterKeyRotation>(136, { m, b -> b.writeLong(m.issuedAt).writeByteArray(m.newPublic, 64).writeByteArray(m.signatureOld, 128).writeByteArray(m.signatureNew, 128) },
        { b -> MasterKeyRotation(b.readLong(), b.readByteArray(64), b.readByteArray(128), b.readByteArray(128)) })
