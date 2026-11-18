package net.fissionmc.protocol

import io.netty.buffer.Unpooled
import kotlin.test.*

class TickSyncMessagesTest {
    private val reg = MessageRegistry.core().registerTickSyncMessages()
    private fun rt(m: NodeMessage): NodeMessage { val b = Unpooled.buffer(); reg.encode(m, b); val d = reg.decode(b); assertEquals(0, b.readableBytes()); return d }

    @Test fun `every tick sync message round trips`() {
        assertEquals(TickSyncHello(5.5f, true), rt(TickSyncHello(5.5f, true)))
        assertEquals(TickSyncChange(TickAction.RATE, 1f), rt(TickSyncChange(TickAction.RATE, 1f)))
        assertEquals(TickSyncApply(TickAction.STEP, 20f, "backend-b"), rt(TickSyncApply(TickAction.STEP, 20f, "backend-b")))
        assertEquals(TickSyncState(true, 10000f, false), rt(TickSyncState(true, 10000f, false)))
    }

    @Test fun `the ids 231-234 do not clash with any other registered message`() {
        // register() fails on a duplicate id: the whole chain the transport uses must build
        MessageRegistry.core().registerWorldMessages().registerReplicationMessages().registerPlayerMessages().registerChunkMessages().registerRegionMessages().registerChatFormatMessages()
            .registerWorldSettingsMessages().registerPositionMessages().registerPluginMessages().registerGhostMessages().registerEntityGhostMessages().registerTraceMessages().registerLimitsMessages().registerTickSyncMessages().registerReadyMessages()
    }
}
