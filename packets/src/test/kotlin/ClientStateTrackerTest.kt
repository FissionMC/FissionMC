import com.github.retrooper.packetevents.protocol.ConnectionState
import net.fissionmc.packets.ClientStateTracker
import net.fissionmc.packets.StateIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ClientStateTrackerTest {
    private val ids = StateIds(configEnd = 3, configEndAck = 3, startConfiguration = 112, configurationAck = 15)
    private fun f(id: Int, vararg rest: Byte): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var v = id
        while (v and 0x7f.inv() != 0) { out.write((v and 0x7f) or 0x80); v = v ushr 7 }
        out.write(v); out.write(rest); return out.toByteArray()
    }

    @Test fun `configuration then play then reconfiguration`() {
        val t = ClientStateTracker(ids)
        t.onServerFrame(f(7, 1, 2)); t.onClientFrame(f(3))            // unrelated id, client "ack" without server end: ignored
        assertEquals(ConnectionState.CONFIGURATION, t.clientState)
        t.onServerFrame(f(3)); assertEquals(ConnectionState.PLAY, t.serverState)
        assertEquals(ConnectionState.CONFIGURATION, t.clientState)    // the client has not acked yet
        t.onClientFrame(f(3)); assertEquals(ConnectionState.PLAY, t.clientState)
        t.onServerFrame(f(112)); t.onClientFrame(f(15))
        assertEquals(ConnectionState.CONFIGURATION, t.serverState); assertEquals(ConnectionState.CONFIGURATION, t.clientState)
    }

    @Test fun `multi byte ids and garbage are handled`() {
        val t = ClientStateTracker(ids)
        assertEquals(300, t.packetId(f(300, 9))); assertEquals(-1, t.packetId(ByteArray(0)))
        assertNotEquals(-1, t.packetId(f(0)))
    }
}
