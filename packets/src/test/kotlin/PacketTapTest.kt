import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.PacketBus
import net.fissionmc.packets.PacketTap
import net.fissionmc.packets.Side
import net.fissionmc.packets.StateIds
import java.util.UUID
import kotlin.test.*

class PacketTapTest {
    private fun frame(w: PacketWrapper<*>): ByteArray {
        val b = Unpooled.buffer(); w.buffer = b; w.writeVarInt(w.nativePacketId); w.write()
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }
    /** A tap whose tracker is already in PLAY (as after configuration). */
    private fun playTap(): PacketTap {
        FissionPacketEventsAPI.install(Side.MASTER)
        val tap = assertNotNull(PacketTap.create(EmbeddedChannel(), UUID(0, 1), "Tester"))
        val ids = StateIds.forVersion()
        tap.toClient(byteArrayOf(ids.configEnd.toByte())); tap.fromClient(byteArrayOf(ids.configEndAck.toByte()))
        assertEquals(ConnectionState.PLAY, tap.tracker.clientState)
        return tap
    }

    @Test fun `without listeners frames pass untouched`() {
        val tap = playTap(); val f = frame(WrapperPlayClientKeepAlive(5))
        assertContentEquals(f, tap.fromClient(f))
    }

    @Test fun `listener sees both directions, cancels and modifies`() {
        val tap = playTap(); val seen = mutableListOf<String>()
        val l = object : PacketListenerAbstract() {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                if (e.packetType == PacketType.Play.Client.KEEP_ALIVE) { seen += "in:${WrapperPlayClientKeepAlive(e).id}"; if (WrapperPlayClientKeepAlive(e).id == 666L) e.isCancelled = true }
            }
            override fun onPacketSend(e: PacketSendEvent) {
                if (e.packetType == PacketType.Play.Server.KEEP_ALIVE) {
                    val w = WrapperPlayServerKeepAlive(e); seen += "out:${w.id}"; w.id = 99; e.markForReEncode(true)
                }
            }
        }
        PacketBus.register(l)
        try {
            assertNull(tap.fromClient(frame(WrapperPlayClientKeepAlive(666))))          // cancelled
            assertNotNull(tap.fromClient(frame(WrapperPlayClientKeepAlive(7))))         // passes
            val out = assertNotNull(tap.toClient(frame(WrapperPlayServerKeepAlive(42))))
            // the modified frame carries id 99: decode it again without listeners
            PacketBus.unregister(l)
            val buf = Unpooled.wrappedBuffer(out)
            val ev = com.github.retrooper.packetevents.util.EventCreationUtil.createSendEvent(EmbeddedChannel(), tap.user, null, buf, false)
            assertEquals(99L, WrapperPlayServerKeepAlive(ev).id)
            assertEquals(listOf("in:666", "in:7", "out:42"), seen)
        } finally { runCatching { PacketBus.unregister(l) } }
    }
}
