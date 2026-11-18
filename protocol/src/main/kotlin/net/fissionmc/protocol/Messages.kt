package net.fissionmc.protocol

import io.netty.buffer.ByteBuf

/** A message exchanged between nodes (master <-> backend). Open so later modules add their own. */
interface NodeMessage

enum class NodeRole { MASTER, BACKEND }

/** First message, sent by the connecting side. */
/** [ephemeral]: the sender's X25519 public key for this link (empty when link security is off); the link is encrypted with keys derived from both. */
class Hello(
    val protocol: Int, val nodeId: String, val role: NodeRole, val mcVersion: String, val nonce: ByteArray, val ephemeral: ByteArray = ByteArray(0),
    /** [HELLO_NORMAL] (pinned keys) or [HELLO_PAIRING] (first contact: both sides prove the one-time pairing secret, see .dev/docs/design/link-security.md). */
    val mode: Int = HELLO_NORMAL,
    /** Fingerprint (first 8 bytes of SHA-256 of the public key) of the master key this backend has pinned; lets a master that rotated its key sign with the key the backend knows. Empty when nothing is pinned. */
    val masterHint: ByteArray = ByteArray(0),
) : NodeMessage

const val HELLO_NORMAL = 0
const val HELLO_PAIRING = 1

/** Acceptor's reply to [Hello]: its identity, a fresh nonce, and (if it has a key) a signature over the handshake transcript. */
class Challenge(val nodeId: String, val nonce: ByteArray, val signature: ByteArray, val ephemeral: ByteArray = ByteArray(0),
    /** The acceptor's public key as it claims it (NOT trusted: only compared with the pinned key to give a specific mismatch message; the signature is verified against the pinned key). */
    val publicKey: ByteArray = ByteArray(0)) : NodeMessage

/** Initiator's proof: signature over the handshake transcript (empty when the initiator has no key). */
class Auth(val signature: ByteArray) : NodeMessage

/** Sent by the accepting side once [Auth] is accepted; the link is ready after this. */
data class Welcome(val protocol: Int, val nodeId: String, val role: NodeRole, val sessionId: Long) : NodeMessage

data class Ping(val seq: Long, val sentNanos: Long) : NodeMessage
data class Pong(val seq: Long, val sentNanos: Long) : NodeMessage
data class Disconnect(val reason: String) : NodeMessage

/** Encodes/decodes one message type. */
class MessageCodec<T : NodeMessage>(
    val type: Class<T>,
    val encode: (T, ByteBuf) -> Unit,
    val decode: (ByteBuf) -> T,
)

/** Maps message types to numeric ids. Ids are stable wire format; never reuse one. */
class MessageRegistry {
    private val byId = HashMap<Int, MessageCodec<*>>()
    private val byType = HashMap<Class<*>, Pair<Int, MessageCodec<*>>>()

    fun <T : NodeMessage> register(id: Int, codec: MessageCodec<T>): MessageRegistry {
        require(byId.put(id, codec) == null) { "Duplicate message id $id" }
        byType[codec.type] = id to codec
        return this
    }

    inline fun <reified T : NodeMessage> register(
        id: Int,
        noinline encode: (T, ByteBuf) -> Unit,
        noinline decode: (ByteBuf) -> T,
    ) = register(id, MessageCodec(T::class.java, encode, decode))

    @Suppress("UNCHECKED_CAST")
    fun encode(msg: NodeMessage, out: ByteBuf) {
        val (id, codec) = byType[msg.javaClass] ?: error("Unregistered message ${msg.javaClass.name}")
        out.writeVarInt(id)
        (codec as MessageCodec<NodeMessage>).encode(msg, out)
    }

    /** The wire id of [msg]'s type, or -1 when it is not registered (used by the flight recorder). */
    fun idOf(msg: NodeMessage): Int = byType[msg.javaClass]?.first ?: -1

    fun decode(buf: ByteBuf): NodeMessage {
        val id = buf.readVarInt()
        val codec = byId[id] ?: error("Unknown message id $id")
        return codec.decode(buf)
    }

    companion object {
        /** Registry with the core control messages (ids 0-15 are reserved for them). */
        fun core(): MessageRegistry = MessageRegistry()
            .register<Hello>(0,
                { m, b -> b.writeVarInt(m.protocol).writeString(m.nodeId, 64).writeVarInt(m.role.ordinal).writeString(m.mcVersion, 32).writeByteArray(m.nonce, 64).writeByteArray(m.ephemeral, 128).writeByte(m.mode).writeByteArray(m.masterHint, 16) },
                { b ->
                    val protocol = b.readVarInt(); val id = b.readString(64); val role = b.readVarInt().also { require(it in 0 until NodeRole.entries.size) { "bad role" } }.let { NodeRole.entries[it] }
                    val mc = b.readString(32); val nonce = b.readByteArray(64); val eph = if (b.isReadable) b.readByteArray(128) else ByteArray(0)
                    val mode = if (b.isReadable) b.readUnsignedByte().toInt() else HELLO_NORMAL; val hint = if (b.isReadable) b.readByteArray(16) else ByteArray(0)
                    Hello(protocol, id, role, mc, nonce, eph, mode, hint) })
            .register<Challenge>(5,
                { m, b -> b.writeString(m.nodeId, 64).writeByteArray(m.nonce, 64).writeByteArray(m.signature, 256).writeByteArray(m.ephemeral, 128).writeByteArray(m.publicKey, 64) },
                { b -> Challenge(b.readString(64), b.readByteArray(64), b.readByteArray(256), if (b.isReadable) b.readByteArray(128) else ByteArray(0), if (b.isReadable) b.readByteArray(64) else ByteArray(0)) })
            .register<Auth>(6, { m, b -> b.writeByteArray(m.signature, 256) }, { b -> Auth(b.readByteArray(256)) })
            .register<Welcome>(1,
                { m, b -> b.writeVarInt(m.protocol).writeString(m.nodeId, 64).writeVarInt(m.role.ordinal).writeLong(m.sessionId) },
                { b -> Welcome(b.readVarInt(), b.readString(64), NodeRole.entries[b.readVarInt().also { require(it in 0 until NodeRole.entries.size) { "bad role" } }], b.readLong()) })
            .register<Ping>(2, { m, b -> b.writeLong(m.seq).writeLong(m.sentNanos) }, { b -> Ping(b.readLong(), b.readLong()) })
            .register<Pong>(3, { m, b -> b.writeLong(m.seq).writeLong(m.sentNanos) }, { b -> Pong(b.readLong(), b.readLong()) })
            .register<Disconnect>(4, { m, b -> b.writeString(m.reason, 256) }, { b -> Disconnect(b.readString(256)) })
            .registerPairingMessages()   // 131-136: first-contact pairing and key rotation (PairingMessages.kt)
    }
}
