package net.fissionmc.net

import kotlin.test.*

class GroupPackingTest {
    @Test fun `messages stay under the byte and count caps and groups are never split`() {
        val groups = listOf(listOf(4, 4), listOf(5), listOf(3, 3, 3), listOf(100), listOf(1))
        val big = mutableListOf<List<Int>>()
        val out = packGroupsSized(groups, 4, 10L, { it }, { big += it })
        assertEquals(listOf(listOf(100)), big)
        assertEquals(listOf(listOf(4, 4), listOf(5), listOf(3, 3, 3, 1)), out)
        for (m in out) { assertTrue(m.sum() <= 10); assertTrue(m.size <= 4) }
        assertEquals(groups.flatten().filter { it != 100 }.sorted(), out.flatten().sorted())
    }
}
