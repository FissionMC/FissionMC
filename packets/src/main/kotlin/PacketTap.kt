package net.fissionmc.packets

import com.github.retrooper.packetevents.event.ProtocolPacketEvent
import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.util.EventCreationUtil
import io.netty.buffer.Unpooled
import java.util.UUID

/**
 * Feeds relayed packet frames (varint id + payload) through the [PacketBus] listener chain, in both directions.
 * Used on the master (client <-> node link) and on the backend (node link <-> virtual connection), so a listener sees
 * every packet of a relayed player exactly once per hop it is registered on.
 *
 * Fast path: with no listeners registered nothing is decoded; frames only update the [ClientStateTracker].
 * A listener may cancel (frame dropped) or modify (the frame is re-encoded from the last used wrapper).
 */
class PacketTap private constructor(private val channel: Any, val user: User, val tracker: ClientStateTracker) {
    /** A frame from the client heading to the server. Returns the frame to forward, or null if a listener cancelled it. */
    fun fromClient(frame: ByteArray): ByteArray? {
        val out = if (!PacketBus.hasListeners) frame else run {
            user.decoderState = tracker.clientState
            val e = EventCreationUtil.createReceiveEvent(channel, user, null, Unpooled.wrappedBuffer(frame), false)
            result(e, PacketBus.fireReceive(e), frame)
        }
        if (out != null) tracker.onClientFrame(out)
        return out
    }

    /** A frame from the server heading to the client. Returns the frame to forward, or null if cancelled. */
    fun toClient(frame: ByteArray): ByteArray? {
        val out = if (!PacketBus.hasListeners) frame else run {
            user.encoderState = tracker.serverState
            val e = EventCreationUtil.createSendEvent(channel, user, null, Unpooled.wrappedBuffer(frame), false)
            result(e, PacketBus.fireSend(e), frame)
        }
        if (out != null) tracker.onServerFrame(out)
        return out
    }

    private fun result(e: ProtocolPacketEvent, notCancelled: Boolean, original: ByteArray): ByteArray? {
        if (!notCancelled) return null
        val w = e.lastUsedWrapper
        if (!e.needsReEncode() || w == null) return original
        val b = Unpooled.buffer(); w.buffer = b
        w.writeVarInt(w.nativePacketId); w.write()
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }

    companion object {
        /** Null (and a log line) when PacketEvents cannot create users, e.g. its mapping resources are missing. */
        fun create(channel: Any, uuid: UUID?, name: String?, version: ClientVersion = ClientVersion.V_26_3, log: (String) -> Unit = {}, start: ConnectionState = ConnectionState.CONFIGURATION): PacketTap? = try {
            val user = User(channel, start, version, UserProfile(uuid, name))
            PacketTap(channel, user, ClientStateTracker(StateIds.forVersion(version), start))
        } catch (t: Throwable) { log("packet tap unavailable: ${t.javaClass.simpleName}: ${t.message}"); null }
    }
}
