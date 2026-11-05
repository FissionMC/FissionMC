import com.github.retrooper.packetevents.protocol.player.ClientVersion
import net.fissionmc.packets.StateIds
import kotlin.test.Test
import kotlin.test.assertTrue

class PacketIdsTest {
    @Test fun `state ids resolve from packetevents tables for 26_3`() {
        val ids = StateIds.forVersion(ClientVersion.V_26_3)
        assertTrue(listOf(ids.configEnd, ids.configEndAck, ids.startConfiguration, ids.configurationAck).all { it >= 0 }, ids.toString())
    }
}
