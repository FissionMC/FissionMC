package net.fissionmc.master.ticksync

import net.fissionmc.protocol.TickAction
import kotlin.test.*

class TickSyncModelTest {
    private fun ok(o: TickSyncModel.Outcome) = assertIs<TickSyncModel.Outcome.Apply>(o)
    private fun bad(o: TickSyncModel.Outcome) = assertIs<TickSyncModel.Outcome.Reject>(o)

    @Test fun `the rate is stored and must be between 1 and 10000`() {
        val m = TickSyncModel()
        ok(m.handle(TickAction.RATE, 1f)); assertEquals(1f, m.rate)
        ok(m.handle(TickAction.RATE, 10_000f)); assertEquals(10_000f, m.rate)
        for (v in listOf(0.99f, 0f, -5f, 10_000.5f, Float.NaN, Float.POSITIVE_INFINITY)) { bad(m.handle(TickAction.RATE, v)); assertEquals(10_000f, m.rate, "a rejected rate changes nothing ($v)") }
        ok(m.handle(TickAction.RATE, 12.5f)); assertEquals(12.5f, m.rate)
    }

    @Test fun `freeze and unfreeze are stored and keep the rate`() {
        val m = TickSyncModel(); m.handle(TickAction.RATE, 5f)
        ok(m.handle(TickAction.FREEZE, 0f)); assertTrue(m.frozen); assertEquals(5f, m.rate)
        ok(m.handle(TickAction.UNFREEZE, 0f)); assertFalse(m.frozen)
    }

    @Test fun `step needs a frozen network and a bounded whole count`() {
        val m = TickSyncModel()
        bad(m.handle(TickAction.STEP, 1f))
        m.handle(TickAction.FREEZE, 0f)
        ok(m.handle(TickAction.STEP, 1f)); ok(m.handle(TickAction.STEP, TickSyncModel.MAX_STEP.toFloat()))
        for (v in listOf(0f, -1f, 1.5f, Float.NaN, TickSyncModel.MAX_STEP + 1000f)) bad(m.handle(TickAction.STEP, v))
        assertTrue(m.frozen, "stepping is not stored")
        ok(m.handle(TickAction.STEP_STOP, 0f))
    }

    @Test fun `sprint is bounded, forwarded and not stored`() {
        val m = TickSyncModel(); m.handle(TickAction.FREEZE, 0f)
        ok(m.handle(TickAction.SPRINT, 100f)); bad(m.handle(TickAction.SPRINT, 0f)); bad(m.handle(TickAction.SPRINT, TickSyncModel.MAX_SPRINT + 1000f))
        ok(m.handle(TickAction.SPRINT_STOP, 0f)); assertEquals(20f, m.rate); assertTrue(m.frozen)
    }

    @Test fun `unknown actions are rejected`() { bad(TickSyncModel().handle(99, 1f)); bad(TickSyncModel().handle(-1, 1f)) }

    @Test fun `the first backend seeds an empty master, later hellos do not overwrite`() {
        val m = TickSyncModel()
        assertTrue(m.adopt(5f, true)); assertEquals(5f, m.rate); assertTrue(m.frozen)
        assertFalse(m.adopt(20f, false)); assertEquals(5f, m.rate); assertTrue(m.frozen, "a restarted backend takes the master's state, not the other way round")
    }

    @Test fun `a hello with a nonsense rate seeds nothing and a command also counts as the state being set`() {
        val m = TickSyncModel(); assertFalse(m.adopt(Float.NaN, false)); assertFalse(m.initialized)
        m.handle(TickAction.RATE, 8f); assertFalse(m.adopt(20f, false)); assertEquals(8f, m.rate)
    }

    @Test fun `the config defaults to sync on`() { assertTrue(TickSyncConfig().sync) }
}
