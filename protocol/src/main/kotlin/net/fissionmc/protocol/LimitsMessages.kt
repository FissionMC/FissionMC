package net.fissionmc.protocol

/*
 * Wire ids 111-130 are this feature's block (node data limits). Ids are wire format: never reuse one.
 *   111 LinkLimits           both directions, the first message after the handshake (post-auth): the caps this node accepts
 *   112 Fragment             one piece of a message that is too big for one frame (link layer, never reaches a handler)
 *   113 FragmentAbort        either side stops a fragmented message (sender gave up / receiver refuses it)
 *   114 NetPluginMessage     plugin to plugin message (backend -> master -> backend, or to the master)
 *   115 NetPluginUndeliverable  master -> sender: the target of a NetPluginMessage does not exist / is gone
 *   116-130 free
 */

/** 111: the sender's accepted caps, sent right after the handshake. A node sends nothing but handshake messages before it has the peer's. */
class LinkLimits(val limits: Limits) : NodeMessage

/** Messages that must keep their order with each other (per [orderKey]) even when one of them is fragmented and the others are not. */
interface OrderedMessage : NodeMessage { val orderKey: String }

/**
 * 112: piece [index] of fragmented message [streamId] (ids are per sending direction). Piece 0 has flag [FIRST] and [total] = the byte size of the whole encoded
 * message; the last has [LAST]. Pieces of one message arrive in order; pieces of different messages and ordinary messages interleave.
 */
class Fragment(val streamId: Long, val index: Int, val flags: Int, val total: Int, val data: ByteArray) : NodeMessage {
    companion object { const val FIRST = 1; const val LAST = 2 }
}

/** 113: the stream [streamId] is dropped. [byReceiver] = true: the receiver refuses it (the sender stops sending it); false: the sender gives up (the receiver frees what it has). */
class FragmentAbort(val streamId: Long, val byReceiver: Boolean, val reason: String) : NodeMessage

/**
 * 114: a plugin message. [target]: a backend node id, `*` (every backend but the sender) or `master`. [origin] is filled in by the master from the authenticated link
 * (what a backend writes there is ignored). [plugin] names the sending plugin for quotas. Messages of one (origin, channel) arrive in the order they were sent.
 */
class NetPluginMessage(val origin: String, val target: String, val plugin: String, val channel: String, val data: ByteArray) : OrderedMessage {
    override val orderKey: String get() = "$origin|$channel"
}

/** 115: the master could not deliver [channel] to [target] (unknown or offline node, over quota). */
class NetPluginUndeliverable(val target: String, val channel: String, val reason: String) : NodeMessage

const val PLUGIN_CHANNEL_MAX = 128
const val PLUGIN_NAME_MAX = 64

fun MessageRegistry.registerLimitsMessages(): MessageRegistry = this
    .register<LinkLimits>(111, { m, b -> m.limits.write(b) }, { b -> LinkLimits(Limits.read(b)) })
    .register<Fragment>(112, { m, b -> b.writeLong(m.streamId).writeVarInt(m.index).writeByte(m.flags); if (m.flags and Fragment.FIRST != 0) b.writeVarInt(m.total); b.writeByteArray(m.data) },
        { b -> val id = b.readLong(); val i = b.readVarInt(); val f = b.readByte().toInt(); val t = if (f and Fragment.FIRST != 0) b.readVarInt() else 0; Fragment(id, i, f, t, b.readByteArray(Limits.cur().frame)) })
    .register<FragmentAbort>(113, { m, b -> b.writeLong(m.streamId).writeBoolean(m.byReceiver).writeString(m.reason, 256) }, { b -> FragmentAbort(b.readLong(), b.readBoolean(), b.readString(256)) })
    .register<NetPluginMessage>(114, { m, b -> b.writeString(m.origin, 64).writeString(m.target, 64).writeString(m.plugin, PLUGIN_NAME_MAX).writeString(m.channel, PLUGIN_CHANNEL_MAX).writeByteArray(m.data, Limits.cur().maxPluginMessage) },
        { b -> NetPluginMessage(b.readString(64), b.readString(64), b.readString(PLUGIN_NAME_MAX), b.readString(PLUGIN_CHANNEL_MAX), b.readByteArray(Limits.cur().maxPluginMessage)) })
    .register<NetPluginUndeliverable>(115, { m, b -> b.writeString(m.target, 64).writeString(m.channel, PLUGIN_CHANNEL_MAX).writeString(m.reason, 256) }, { b -> NetPluginUndeliverable(b.readString(64), b.readString(PLUGIN_CHANNEL_MAX), b.readString(256)) })
