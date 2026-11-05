package net.fissionmc.backend.relay

import io.netty.buffer.Unpooled
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.server.MinecraftServer
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Dev check (`-Dfission.check-limbo=<limbo-play.bin>`): decodes the master limbo's play frames (built with PacketEvents wrappers)
 * with vanilla's OWN clientbound play codec and this server's registries, once, at the first tick. Logs one line per frame:
 * the decoded vanilla packet class and unread bytes (must be 0), or the decoding error. This is what makes PacketEvents' encoders
 * checkable against vanilla instead of against themselves.
 */
object LimboCheck {
    private val log = Logger.getLogger("FissionMC")
    @Volatile private var done = false

    @JvmStatic fun once(server: MinecraftServer) {
        if (done) return
        val file = System.getProperty("fission.check-limbo") ?: run { done = true; return }
        done = true
        try {   // what the recorder's fake client sends: select-known-packs [minecraft:core:26.3], decoded by vanilla's serverbound configuration codec
            val kp = byteArrayOf(0x07, 0x01, 0x09) + "minecraft".toByteArray() + byteArrayOf(0x04) + "core".toByteArray() + byteArrayOf(0x04) + "26.3".toByteArray()
            val b = Unpooled.wrappedBuffer(kp)
            val pkt = net.minecraft.network.protocol.configuration.ConfigurationProtocols.SERVERBOUND.codec().decode(b)
            log.info("[fission] limbo check: serverbound configuration frame 07... decoded as ${pkt.javaClass.simpleName}, unread ${b.readableBytes()}")
        } catch (t: Throwable) { log.warning("[fission] limbo check: serverbound configuration frame 07 FAILED: $t") }
        try {
            val d = DataInputStream(Files.newInputStream(Path.of(file)))
            val frames = List(d.readInt()) { ByteArray(d.readInt()).also { b -> d.readFully(b) } }
            val codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec()
            frames.forEachIndexed { i, f ->
                val buf = Unpooled.wrappedBuffer(f)
                try {
                    val p = codec.decode(buf)
                    log.info("[fission] limbo check #$i: vanilla decoded ${p.javaClass.simpleName}, unread bytes ${buf.readableBytes()}")
                } catch (t: Throwable) { log.warning("[fission] limbo check #$i: vanilla FAILED to decode (${f.size} bytes): $t") }
            }
            log.info("[fission] limbo check done: ${frames.size} frames")
        } catch (t: Throwable) { log.warning("[fission] limbo check failed: $t") }
    }
}
