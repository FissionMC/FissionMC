package net.fissionmc.protocol

import io.netty.buffer.ByteBuf

/** A message exchanged between nodes (master <-> backend). Open so later modules add their own. */
interface NodeMessage

enum class NodeRole { MASTER, BACKEND }

/** First message, sent by the connecting side. */
class Hello(val protocol: Int, val nodeId: String, val role: NodeRole, val mcVersion: String, val nonce: ByteArray) : NodeMessage

/** Acceptor's reply to [Hello]: its identity, a fresh nonce, and (if it has a key) a signature over the handshake transcript. */
class Challenge(val nodeId: String, val nonce: ByteArray, val signature: ByteArray) : NodeMessage

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

    fun decode(buf: ByteBuf): NodeMessage {
        val id = buf.readVarInt()
        val codec = byId[id] ?: error("Unknown message id $id")
        return codec.decode(buf)
    }

    companion object {
        /** Registry with the core control messages (ids 0-15 are reserved for them). */
        fun core(): MessageRegistry = MessageRegistry()
            .register<Hello>(0,
                { m, b -> b.writeVarInt(m.protocol).writeString(m.nodeId, 64).writeVarInt(m.role.ordinal).writeString(m.mcVersion, 32).writeByteArray(m.nonce, 64) },
                { b -> Hello(b.readVarInt(), b.readString(64), NodeRole.entries[b.readVarInt()], b.readString(32), b.readByteArray(64)) })
            .register<Challenge>(5,
                { m, b -> b.writeString(m.nodeId, 64).writeByteArray(m.nonce, 64).writeByteArray(m.signature, 256) },
                { b -> Challenge(b.readString(64), b.readByteArray(64), b.readByteArray(256)) })
            .register<Auth>(6, { m, b -> b.writeByteArray(m.signature, 256) }, { b -> Auth(b.readByteArray(256)) })
            .register<Welcome>(1,
                { m, b -> b.writeVarInt(m.protocol).writeString(m.nodeId, 64).writeVarInt(m.role.ordinal).writeLong(m.sessionId) },
                { b -> Welcome(b.readVarInt(), b.readString(64), NodeRole.entries[b.readVarInt()], b.readLong()) })
            .register<Ping>(2, { m, b -> b.writeLong(m.seq).writeLong(m.sentNanos) }, { b -> Ping(b.readLong(), b.readLong()) })
            .register<Pong>(3, { m, b -> b.writeLong(m.seq).writeLong(m.sentNanos) }, { b -> Pong(b.readLong(), b.readLong()) })
            .register<Disconnect>(4, { m, b -> b.writeString(m.reason, 256) }, { b -> Disconnect(b.readString(256)) })
    }
}
