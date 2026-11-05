package net.fissionmc.master

import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import net.fissionmc.master.client.Limbo
import net.fissionmc.master.client.PlayerProfile
import net.fissionmc.protocol.PlayerPacketToClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Dev tool (`debug.relay-probe=true` in fission.properties): attaches a fake client to every backend that joins and logs the
 * frames the backend sends back (varint packet id + size). Proves the virtual connection works without a real client.
 *
 * With `debug.record-limbo=<dir>` the fake client also answers the known-packs request like a vanilla client and RECORDS the
 * configuration frames the real backend sends (feature flags, known packs, registries, tags, end) into `<dir>/limbo-config.bin`,
 * the resource the limbo replays (copy it to `master/src/main/resources/`). It also writes `<dir>/limbo-play.bin`, the limbo's
 * play-phase frames, which a backend started with `-Dfission.check-limbo=<file>` decodes with vanilla's own codecs.
 */
class RelayProbe(private val relay: PlayerRelay, private val recordDir: Path? = null) : NodeRegistry.Listener {
    private val v = ClientVersion.V_26_3
    private val recorded = ArrayList<ByteArray>()

    override fun joined(node: RegisteredNode) {
        var pid = 0L
        val keep = listOf(
            PacketType.Configuration.Server.UPDATE_ENABLED_FEATURES, PacketType.Configuration.Server.SELECT_KNOWN_PACKS,
            PacketType.Configuration.Server.REGISTRY_DATA, PacketType.Configuration.Server.UPDATE_TAGS,
            PacketType.Configuration.Server.CONFIGURATION_END,
        ).map { it.getId(v) }.toSet()
        val known = PacketType.Configuration.Server.SELECT_KNOWN_PACKS.getId(v)
        val end = PacketType.Configuration.Server.CONFIGURATION_END.getId(v)
        val c = object : RelayClient {
            override val profile = PlayerProfile(UUID.nameUUIDFromBytes("probe".toByteArray()), "ProbePlayer")
            override val protocol = 777
            private var n = 0
            override fun deliver(frame: ByteArray) {
                val id = Limbo.idOf(frame)
                println("[probe] frame #${++n} from ${node.info.nodeId}: packet id $id, ${frame.size} bytes")
                if (recordDir == null) return
                if (id in keep) recorded += frame
                if (id == known) relay.toBackend(pid, Limbo.encode(Limbo.knownPacksAnswer()))
                if (id == end) finishRecording()
            }
            override fun detached(reason: String, backendLost: Boolean) = println("[probe] detached: $reason")
        }
        // the backend registers its handlers only after the world pull and the first ticks; wait for that
        java.util.Timer(true).schedule(object : java.util.TimerTask() {
            override fun run() { pid = relay.attach(c) ?: 0; println("[probe] attaching fake player to ${node.info.nodeId}: $pid") }
        }, 25_000)
    }

    private fun finishRecording() {
        val dir = recordDir ?: return
        Files.createDirectories(dir)
        Files.write(dir.resolve("limbo-config.bin"), Limbo.writeFrames(recorded))
        val cfg = Limbo.parse(recorded)
        val play = Limbo.playFrames(100, cfg.overworldDimensionId)
        Files.write(dir.resolve("limbo-play.bin"), Limbo.writeFrames(play))
        println("[probe] recorded ${recorded.size} configuration frames (overworld dimension id ${cfg.overworldDimensionId}) and ${play.size} play frames into $dir")
    }
}
