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
    try {
        c.send(WrapperHandshakingClientHandshake(777, "localhost", port, ConnectionIntention.LOGIN)); c.setState(ConnectionState.LOGIN)
        c.send(WrapperLoginClientLoginStart(ClientVersion.V_26_3, "ProbeClient", null, UUID(0, 7)))
        c.setCompression(c.expect(PacketType.Login.Server.SET_COMPRESSION) { WrapperLoginServerSetCompression(it) }.threshold)
        c.skip(PacketType.Login.Server.LOGIN_SUCCESS)
        c.send(WrapperLoginClientLoginSuccessAck()); c.setState(ConnectionState.CONFIGURATION)
        var n = 0
        val deadline = System.nanoTime() + 40_000_000_000L   // a healthy session never goes silent (keepalives): stop after 40 s
        while (System.nanoTime() < deadline) {
            val (type, id) = c.readAny()
            println("[clientprobe] #${++n} id=$id ${type ?: "UNMAPPED"}")
            when (type) {
                PacketType.Configuration.Server.SELECT_KNOWN_PACKS -> c.send(com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSelectKnownPacks(listOf(com.github.retrooper.packetevents.util.KnownPack("minecraft", "core", net.fissionmc.protocol.MC_VERSION))))
                PacketType.Configuration.Server.CONFIGURATION_END -> { c.send(WrapperConfigClientConfigurationEndAck()); c.setState(ConnectionState.PLAY) }
                PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerLoaded())   // ClientPacketListener.notifyPlayerLoaded
                PacketType.Play.Server.CONFIGURATION_START -> { c.send(WrapperPlayClientConfigurationAck()); c.setState(ConnectionState.CONFIGURATION) }
                else -> {}
            }
        }
    } catch (t: Throwable) { println("[clientprobe] stopped: $t") }
    java.nio.file.Files.write(out, c.received.toByteArray())
    println("[clientprobe] wrote ${c.received.size()} bytes to $out")
    c.close()
    kotlin.system.exitProcess(0)
}
