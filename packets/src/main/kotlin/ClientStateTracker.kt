package net.fissionmc.packets

import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion

/** Packet ids (for one protocol version) that switch a relayed connection between configuration and play. */
data class StateIds(val configEnd: Int, val configEndAck: Int, val startConfiguration: Int, val configurationAck: Int) {
    companion object {
        fun forVersion(v: ClientVersion = ClientVersion.V_26_3) = StateIds(
            PacketType.Configuration.Server.CONFIGURATION_END.getId(v), PacketType.Configuration.Client.CONFIGURATION_END_ACK.getId(v),
            PacketType.Play.Server.CONFIGURATION_START.getId(v), PacketType.Play.Client.CONFIGURATION_ACK.getId(v),
        )
    }
}

/**
 * Follows which phase (configuration/play) a relayed connection is in by watching the frames in both directions, so the master
 * and the backend tap decode with the right packet table without parsing every packet. Pure logic.
 *
 * Phase changes: server sends configuration-end, client acks it -> PLAY; server sends start-configuration, client acks -> CONFIGURATION.
 */
class ClientStateTracker(private val ids: StateIds, start: ConnectionState = ConnectionState.CONFIGURATION) {
    /** State used to decode what the client sends. */
    var clientState: ConnectionState = start; private set
    /** State used to decode what the server sends. */
    var serverState: ConnectionState = start; private set
    private var pendingToPlay = false
    private var pendingToConfig = false

    /** The first varint of a frame is the packet id. */
    fun packetId(frame: ByteArray): Int {
        var id = 0; var shift = 0; var i = 0
        while (i < frame.size && shift < 35) { val b = frame[i++].toInt(); id = id or ((b and 0x7f) shl shift); shift += 7; if (b and 0x80 == 0) return id }
        return -1
    }

    fun onServerFrame(frame: ByteArray) {
        val id = packetId(frame)
        if (serverState == ConnectionState.CONFIGURATION && id == ids.configEnd) { pendingToPlay = true; serverState = ConnectionState.PLAY }
        else if (serverState == ConnectionState.PLAY && id == ids.startConfiguration) { pendingToConfig = true; serverState = ConnectionState.CONFIGURATION }
    }

    fun onClientFrame(frame: ByteArray) {
        val id = packetId(frame)
        if (pendingToPlay && clientState == ConnectionState.CONFIGURATION && id == ids.configEndAck) { clientState = ConnectionState.PLAY; pendingToPlay = false }
        else if (pendingToConfig && clientState == ConnectionState.PLAY && id == ids.configurationAck) { clientState = ConnectionState.CONFIGURATION; pendingToConfig = false }
    }
}
