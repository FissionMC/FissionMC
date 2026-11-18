package net.fissionmc.protocol

import io.netty.buffer.Unpooled
import kotlin.test.*

class TickScaleTest {
    @Test fun `a normal or faster tick rate leaves a timeout alone`() {
        assertEquals(3000L, TickScale.scale(3000, 50)); assertEquals(3000L, TickScale.scale(3000, 10)); assertEquals(3000L, TickScale.scale(3000, 0))
    }
    @Test fun `one tick per second gives twenty times as long and a pause is capped`() {
        assertEquals(60_000L, TickScale.scale(3000, 1000)); assertEquals(150L, TickScale.scale(100, 75))
        assertEquals(TickScale.scale(3000, 1000), TickScale.scale(3000, 600_000), "capped at 20 x")
    }
    @Test fun `the tick rate message round trips`() {
        val reg = MessageRegistry.core().registerEntityGhostMessages()
        val b = Unpooled.buffer(); reg.encode(NodeTickRate(1000), b)
        assertEquals(1000, (reg.decode(b) as NodeTickRate).msPerTick)
    }
    @Test fun `ghost sync with a wall clock unit checks every chunk once per interval and takes a bigger budget when asked`() {
        val s = GhostSyncScheduler(15_000, 2)
        val c = (1L..5L).map { GhostSyncScheduler.Candidate(it, 1) }
        assertEquals(2, s.pick(0, c).size); assertEquals(3, s.pick(1000, c, 10).size, "the 3 never seen ones, nothing else is due yet")
        assertTrue(s.pick(5000, c, 10).isEmpty(), "all seen less than 15 s ago")
        assertEquals(5, s.pick(16_001, c, 10).size, "every chunk is due once the interval passed, even if the server only ticked once")
    }
}
