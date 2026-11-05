package net.fissionmc.master

import net.fissionmc.master.client.PlayerProfile
import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.PlayerPacketToClient
import java.util.UUID

/**
 * Dev tool (`debug.relay-probe=true` in fission.properties): attaches a fake client to every backend that joins and logs the
 * frames the backend sends back (varint packet id + size). Proves the virtual connection works without a real client.
 */
class RelayProbe(private val relay: PlayerRelay) : NodeRegistry.Listener {
    override fun joined(node: RegisteredNode) {
        val c = object : RelayClient {
            override val profile = PlayerProfile(UUID.nameUUIDFromBytes("probe".toByteArray()), "ProbePlayer")
            override val protocol = 777
            private var n = 0
            override fun deliver(frame: ByteArray) {
                var id = 0; var i = 0; var shift = 0
                while (i < frame.size) { val b = frame[i++].toInt(); id = id or ((b and 0x7f) shl shift); shift += 7; if (b and 0x80 == 0) break }
                println("[probe] frame #${++n} from ${node.info.nodeId}: packet id $id, ${frame.size} bytes")
            }
            override fun detached(reason: String, backendLost: Boolean) = println("[probe] detached: $reason")
        }
        // the backend registers its handlers only after the world pull and the first ticks; wait for that
        java.util.Timer(true).schedule(object : java.util.TimerTask() {
            override fun run() { println("[probe] attaching fake player to ${node.info.nodeId}: ${relay.attach(c)}") }
        }, 25_000)
    }
}
