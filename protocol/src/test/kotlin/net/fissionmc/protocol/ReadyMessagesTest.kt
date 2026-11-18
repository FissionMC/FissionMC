package net.fissionmc.protocol

import io.netty.buffer.Unpooled
import kotlin.test.*

class ReadyMessagesTest {
    private val reg = MessageRegistry.core().registerReadyMessages()

    @Test fun `NodeReady round trips and keeps its wire id`() {
        val b = Unpooled.buffer(); reg.encode(NodeReady(5750), b)
        assertEquals(NodeReady(5750), reg.decode(b)); assertEquals(0, b.readableBytes())
        assertEquals(251, reg.idOf(NodeReady(1)), "the wire id is format: never reused or renumbered")
    }

    @Test fun `a negative boot time is clamped`() {
        val b = Unpooled.buffer(); reg.encode(NodeReady(-5), b); assertEquals(NodeReady(0), reg.decode(b))
    }

    @Test fun `id 251 does not clash with any other registered message`() {
        MessageRegistry.core().registerWorldMessages().registerReplicationMessages().registerPlayerMessages().registerChunkMessages().registerRegionMessages().registerChatFormatMessages()
            .registerWorldSettingsMessages().registerPositionMessages().registerPluginMessages().registerGhostMessages().registerEntityGhostMessages().registerTraceMessages().registerLimitsMessages().registerTickSyncMessages().registerReadyMessages()
    }
}
