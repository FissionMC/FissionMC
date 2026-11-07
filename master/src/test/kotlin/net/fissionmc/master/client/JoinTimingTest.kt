package net.fissionmc.master.client

import kotlin.test.*

class JoinTimingTest {
    private var now = 1_000_000_000L
    private fun step(ms: Long) { now += ms * 1_000_000 }

    @Test fun `the phase line adds up`() {
        val j = JoinTiming { now }
        step(50); j.limboJoined(); j.attached(); step(250); j.limboLoaded(false); step(150); j.configDone(); step(350); j.backendLogin(); step(20); j.loginForwarded()
        step(120); j.positionSent(); step(280); j.chunkBatch(); step(200); j.playerLoaded()
        assertEquals("[join] R4T_2 phases: limbo 0.05s, config 0.40s, join 0.35s, held 0.02s, chunks 0.60s (first batch 0.40s, position 0.12s), total 1.42s -> backend-a; limbo client loaded after 0.30s", j.line("R4T_2", "backend-a"))
        assertTrue(j.isDone)
    }

    @Test fun `a join that never finishes and a grace release are marked`() {
        val j = JoinTiming { now }
        j.attached(); step(5_000); j.limboLoaded(true); j.configDone(); j.backendLogin(); j.loginForwarded(); step(20_000)
        val l = j.line("X", null)
        assertTrue("limbo client loaded after 5.00s (grace)" in l, l); assertTrue("chunks never (20.00s so far)" in l, l)
    }
}
