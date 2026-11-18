package net.fissionmc.net

import kotlin.test.*

class PluginQuotaTest {
    @Test fun `rate window and in flight bytes`() {
        var now = 0L; val q = PluginQuota(1000, 3) { now }
        assertNull(q.acquire("p", 100)); assertNull(q.acquire("p", 100)); assertNull(q.acquire("p", 100))
        assertNotNull(q.acquire("p", 1)).also { assertTrue("per second" in it) }
        now += 1_000_000_000L; assertNull(q.acquire("p", 100))
        assertNull(q.acquire("other", 900), "another plugin has its own allowance")
        assertTrue("in flight" in q.acquire("other", 200)!!)
        q.release("other", 900); assertNull(q.acquire("other", 200)); assertEquals(200, q.inflight("other"))
    }
}
