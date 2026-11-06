package net.fissionmc.master.client

import kotlin.test.*

class NbtDiffTest {
    private fun flat(s: String) = Snbt.flatten(Snbt(s).parse())

    @Test fun `the SNBT of a data get line parses into paths`() {
        val m = Snbt.fromConsole("[04:00:00] [Server thread/INFO]: System chat: \"Rex\" has the following entity data: {CustomName: \"Rex\", Owner: [I; 1, -2, 3, 4], Health: 5.0f, Pos: [1.5d, 72.0d, -3.5d], active_effects: [{id: \"minecraft:speed\", duration: 99999, amplifier: 1b}], Tags: [\"rex\"], nested: {a: 'x', b: {}}}")!!
        assertEquals("Rex", m["CustomName"]); assertEquals("5.0f", m["Health"]); assertEquals("-2", m["Owner[2]"]); assertEquals("array:I", m["Owner[0]"])
        assertEquals("99999", m["active_effects[minecraft:speed].duration"]); assertEquals("rex", m["Tags[0]"]); assertEquals("x", m["nested.a"]); assertEquals("{}", m["nested.b"])
    }

    @Test fun `identical data and allowlisted moves are clean, anything else is a difference`() {
        val before = flat("{UUID: [I; 1,2,3,4], Pos: [1.0d,2.0d,3.0d], Motion: [0.0d,0.0d,0.0d], Health: 5.0f, CollarColor: 11b, Passengers: [{id: \"minecraft:pig\", Rotation: [270.0f, 0.0f], Age: 0}]}")
        val moved = flat("{UUID: [I; 1,2,3,4], Pos: [9.0d,2.0d,9.0d], Motion: [0.1d,0.0d,0.0d], Health: 5.0f, CollarColor: 11b, Passengers: [{id: \"minecraft:pig\", Rotation: [90.0f, 0.0f], Age: 40}]}")
        assertTrue(NbtDiff.diff(before, moved).clean, NbtDiff.diff(before, moved).describe())
        val lost = flat("{UUID: [I; 1,2,3,9], Pos: [9.0d,2.0d,9.0d], Health: 7.0f, Passengers: [{id: \"minecraft:pig\", Rotation: [90.0f, 0.0f], Age: 40}]}")
        val d = NbtDiff.diff(before, lost)
        assertFalse(d.clean); assertTrue(d.changed.any { it.startsWith("UUID[4]") }); assertTrue(d.changed.any { it.startsWith("Health") }); assertTrue("CollarColor" in d.missing)
    }

    @Test fun `clock driven counters may drift by the elapsed ticks only`() {
        val a = flat("{Age: -24000, active_effects: [{id: \"minecraft:speed\", duration: 99999}]}")
        assertTrue(NbtDiff.diff(a, flat("{Age: -23900, active_effects: [{id: \"minecraft:speed\", duration: 99900}]}")).clean)
        assertFalse(NbtDiff.diff(a, flat("{Age: 0, active_effects: [{id: \"minecraft:speed\", duration: 99999}]}")).clean, "a baby that grew up is a loss")
    }

    @Test fun `lists of effects or attributes compare as sets`() {
        val a = flat("{attributes: [{id: \"minecraft:a\", base: 1.0d}, {id: \"minecraft:b\", base: 2.0d}]}")
        val b = flat("{attributes: [{id: \"minecraft:b\", base: 2.0d}, {id: \"minecraft:a\", base: 1.0d}]}")
        assertTrue(NbtDiff.diff(a, b).clean)
    }
}
