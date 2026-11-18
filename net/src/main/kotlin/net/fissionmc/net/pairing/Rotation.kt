package net.fissionmc.net.pairing

import net.fissionmc.net.Keys
import net.fissionmc.net.NodeLink
import net.fissionmc.net.NodeSecurity
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.PublicKey
import java.security.Signature

/** Signed key rotation messages. Signatures are Ed25519; the signed bytes name the purpose and the node so a signature cannot be reused for anything else. */
object Rotation {
    private fun bytes(vararg parts: Any): ByteArray = java.io.ByteArrayOutputStream().also { o -> java.io.DataOutputStream(o).run { parts.forEach { when (it) { is String -> writeUTF(it); is Long -> writeLong(it); is ByteArray -> { writeInt(it.size); write(it) } } } } }.toByteArray()
    private fun sign(k: java.security.PrivateKey, data: ByteArray) = Signature.getInstance("Ed25519").run { initSign(k); update(data); sign() }
    private fun verify(k: PublicKey, data: ByteArray, sig: ByteArray) = runCatching { Signature.getInstance("Ed25519").run { initVerify(k); update(data); verify(sig) } }.getOrDefault(false)

    fun nodeData(nodeId: String, reqId: Long, newPublic: ByteArray) = bytes("fission-rotate-node", nodeId, reqId, newPublic)
    fun masterData(nodeId: String, issuedAt: Long, newPublic: ByteArray) = bytes("fission-rotate-master", nodeId, issuedAt, newPublic)

    /** Backend: the rotation request answer, signed with the old and the new key. */
    fun nodeRotation(nodeId: String, reqId: Long, old: java.security.PrivateKey, new: KeyPair): KeyRotation {
        val d = nodeData(nodeId, reqId, new.public.encoded)
        return KeyRotation(reqId, new.public.encoded, sign(old, d), sign(new.private, d))
    }
    /** Master: true when [m] is a valid rotation of [nodeId] from [oldKey] (the authorized one) for request [reqId]. */
    fun verifyNodeRotation(nodeId: String, reqId: Long, oldKey: PublicKey, m: KeyRotation): PublicKey? {
        if (m.reqId != reqId) return null
        val new = runCatching { Keys.decodePublicRaw(m.newPublic) }.getOrNull() ?: return null
        val d = nodeData(nodeId, reqId, m.newPublic)
        return if (verify(oldKey, d, m.signatureOld) && verify(new, d, m.signatureNew)) new else null
    }

    fun masterRotation(nodeId: String, issuedAt: Long, old: java.security.PrivateKey, new: KeyPair): MasterKeyRotation {
        val d = masterData(nodeId, issuedAt, new.public.encoded)
        return MasterKeyRotation(issuedAt, new.public.encoded, sign(old, d), sign(new.private, d))
    }
    /** Backend: the new master key if [m] is signed by the pinned [oldKey] and by the new key itself. */
    fun verifyMasterRotation(nodeId: String, oldKey: PublicKey, m: MasterKeyRotation): PublicKey? {
        val new = runCatching { Keys.decodePublicRaw(m.newPublic) }.getOrNull() ?: return null
        val d = masterData(nodeId, m.issuedAt, m.newPublic)
        return if (verify(oldKey, d, m.signatureOld) && verify(new, d, m.signatureNew)) new else null
    }
}

/** The master's identity rotation: the old key stays in `<key>.previous` and keeps answering backends that still pin it, until [finish]. */
object MasterKeyring {
    fun previousFile(keyFile: Path): Path = keyFile.resolveSibling(keyFile.fileName.toString() + ".previous")

    /** New identity; returns the new pair. The previous key file is written first, then the key file replaced, both atomically. */
    fun rotate(keyFile: Path, sec: NodeSecurity): KeyPair {
        val old = Keys.load(keyFile)
        val fresh = Keys.generate()
        Keys.write(previousFile(keyFile), old)
        Keys.write(keyFile, fresh)
        sec.replaceKey(fresh, old)
        return fresh
    }

    fun finish(keyFile: Path, sec: NodeSecurity) { sec.previous = null; Files.deleteIfExists(previousFile(keyFile)) }
}

/**
 * Backend side of key rotation: [handle] gets the messages of the (authenticated) master link. `RotateKeyRequest` makes a new identity (signed with the old key, sent to the master, stored
 * once the master acknowledged), `MasterKeyRotation` re-pins the master's new key (checked against the pinned one). The link is closed afterwards so it reconnects with the new keys.
 */
class BackendIdentity(private val nodeId: String, private val keyFile: Path, private val sec: NodeSecurity, private val pin: MasterPin, private val log: (String) -> Unit) {
    private class Candidate(val reqId: Long, val pair: KeyPair)
    @Volatile private var candidate: Candidate? = null

    fun handle(link: NodeLink, msg: NodeMessage): Boolean {
        when (msg) {
            is RotateKeyRequest -> {
                val fresh = Keys.generate(); candidate = Candidate(msg.reqId, fresh)
                val old = Keys.load(keyFile)
                link.send(Rotation.nodeRotation(nodeId, msg.reqId, old.private, fresh))
                log("key rotation requested by the master: new identity ${Fingerprint.of(fresh.public)} sent, waiting for the master's acknowledgement")
            }
            is KeyRotationAck -> {
                val c = candidate ?: return true
                if (c.reqId != msg.reqId) return true
                candidate = null
                if (!msg.ok) { log("key rotation refused by the master: ${msg.message}"); return true }
                Keys.write(keyFile.resolveSibling(keyFile.fileName.toString() + ".previous"), Keys.load(keyFile))
                Keys.write(keyFile, c.pair)
                sec.replaceKey(c.pair)
                log("new identity ${Fingerprint.of(c.pair.public)} stored; reconnecting")
                link.close("key rotated")
            }
            is MasterKeyRotation -> {
                val old = pin.key
                val new = if (old == null) null else Rotation.verifyMasterRotation(nodeId, old, msg)
                if (new == null) { log("IGNORED a master key rotation that is not signed by the pinned master key ${pin.fingerprint}"); return true }
                pin.save(new)
                log("the master rotated its key: pinned the new key ${Fingerprint.of(new)} (signed by the old key ${Fingerprint.of(old!!)}); reconnecting")
                link.close("master key rotated")
            }
            else -> return false
        }
        return true
    }
}
