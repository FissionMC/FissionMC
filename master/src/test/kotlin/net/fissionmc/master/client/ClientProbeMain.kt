package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientConfigurationEndAck
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake.ConnectionIntention
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginSuccessAck
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerSetCompression
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientConfigurationAck
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.util.UUID

/**
 * Dev tool (`gradlew :master:clientProbe -Pport=25577 -Pout=<file>`): a scripted offline client that behaves like a real one through
 * login, configuration, limbo and the handoff to a backend (acks start-configuration, answers known packs, acks finish), and records
 * every byte the master sends. Feed the file to a backend with `-PcheckStream=<file>` to replay it through vanilla's client decoders.
 * Ends when the master stays silent for 10 s.
 */
fun main(args: Array<String>) {
    FissionPacketEventsAPI.install(Side.MASTER)
    val port = args[0].toInt(); val out = java.nio.file.Path.of(args[1])
    val c = TestClient(port)
    var tLoaded = 0L; var tJoin = 0L; var chunks = 0; var tFirstChunk = 0L; var tLastChunk = 0L; var frames = 0; val rtts = ArrayList<Double>(); var sentAt = 0L; var movesSent = 0; var joinedAt = 0L; var lastMove = 0L
    try {
        c.send(WrapperHandshakingClientHandshake(777, "localhost", port, ConnectionIntention.LOGIN)); c.setState(ConnectionState.LOGIN)
        c.send(WrapperLoginClientLoginStart(ClientVersion.V_26_3, "ProbeClient", null, UUID(0, 7)))
        c.setCompression(c.expect(PacketType.Login.Server.SET_COMPRESSION) { WrapperLoginServerSetCompression(it) }.threshold)
        c.skip(PacketType.Login.Server.LOGIN_SUCCESS)
        c.send(WrapperLoginClientLoginSuccessAck()); c.setState(ConnectionState.CONFIGURATION)
        // like the real client: its options (view distance 12) go out right after login, in the first configuration only
        c.send(com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSettings("en_us", 12,
            com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSettings.ChatVisibility.FULL, true, 127.toByte(),
            com.github.retrooper.packetevents.protocol.player.HumanoidArm.RIGHT, false, true))
        var n = 0
        val deadline = System.nanoTime() + 40_000_000_000L   // a healthy session never goes silent (keepalives): stop after 40 s
        while (System.nanoTime() < deadline) {
            val (type, id) = c.readAny()
            val now = System.nanoTime(); frames++
            println("[clientprobe] #${++n} id=$id ${type ?: "UNMAPPED"}")
            when (type) {
                PacketType.Configuration.Server.SELECT_KNOWN_PACKS -> c.send(com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSelectKnownPacks(listOf(com.github.retrooper.packetevents.util.KnownPack("minecraft", "core", net.fissionmc.protocol.MC_VERSION))))
                PacketType.Configuration.Server.CONFIGURATION_END -> { c.send(WrapperConfigClientConfigurationEndAck()); c.setState(ConnectionState.PLAY) }
                PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> { if (sentAt != 0L && tJoin != 0L) { rtts += (now - sentAt) / 1e6; sentAt = 0 }
                    if (tLoaded == 0L) { tLoaded = now; c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerLoaded()) } }   // ClientPacketListener.notifyPlayerLoaded
                PacketType.Play.Server.CONFIGURATION_START -> { c.send(WrapperPlayClientConfigurationAck()); c.setState(ConnectionState.CONFIGURATION) }
                PacketType.Play.Server.UPDATE_VIEW_DISTANCE -> if (tJoin != 0L) println("[clientprobe] VIEWDIST radius=${com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewDistance(c.lastEvent!!).viewDistance}")
                PacketType.Play.Server.UPDATE_SIMULATION_DISTANCE -> if (tJoin != 0L) println("[clientprobe] SIMDIST ${com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateSimulationDistance(c.lastEvent!!).getSimulationDistance()}")
                PacketType.Configuration.Server.PLUGIN_MESSAGE -> if (tJoin != 0L || tLoaded != 0L) println("[clientprobe] BRAND/plugin message in configuration after handoff")
                PacketType.Play.Server.JOIN_GAME -> if (tJoin == 0L && tLoaded != 0L) { tJoin = now; joinedAt = now }
                PacketType.Play.Server.CHUNK_DATA -> if (tJoin != 0L) { chunks++; if (tFirstChunk == 0L) tFirstChunk = now; tLastChunk = now }
                else -> {}
            }
            // 4 s after joining the backend world, once per 700 ms: a chat command; the server answers with a system chat message
            // -> end to end round trip (client -> master -> link -> backend tick -> back)
            if (tJoin != 0L && sentAt == 0L && movesSent < 8 && now - tJoin > 4_000_000_000L && now - lastMove > 500_000_000L) {
                lastMove = now; sentAt = now; movesSent++
                c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommandUnsigned("zzunknown"))   // unknown command -> a system chat answer built on the server thread
            }
        }
    } catch (t: Throwable) { println("[clientprobe] stopped: $t") }
    println("[clientprobe] TIMING handoff->JOIN_GAME ${(tJoin - tLoaded) / 1_000_000} ms; chunks $chunks, first +${(tFirstChunk - tJoin) / 1_000_000} ms, last +${(tLastChunk - tJoin) / 1_000_000} ms; frames $frames")
    if (rtts.isNotEmpty()) println("[clientprobe] TIMING command->chat answer round trips ms: ${rtts.map { "%.1f".format(it) }} avg ${"%.1f".format(rtts.average())} min ${"%.1f".format(rtts.min())} max ${"%.1f".format(rtts.max())}")
    java.nio.file.Files.write(out, c.received.toByteArray())
    println("[clientprobe] wrote ${c.received.size()} bytes to $out")
    c.close()
    kotlin.system.exitProcess(0)
}
