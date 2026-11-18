package net.fissionmc.net

import net.fissionmc.protocol.Limits
import kotlin.test.*

class LimitsConfigTest {
    private fun cfg(y: String) = LimitsConfig.from(FissionConfig.parse(y))
    private fun fail(y: String): String = assertFailsWith<ConfigException> { cfg(y) }.message!!

    @Test fun `missing section is the defaults`() { assertEquals(LimitsConfig(), cfg("")); assertEquals(Limits(), cfg("limits: {}").limits) }

    @Test fun `sizes take suffixes and counts are plain`() {
        val c = cfg("limits: { frame: 4M, max-message: 128MiB, chunk-blob: 1024K, item-blob: 2097152, item-stacks: 8, fragment-size: 64K, reassembly-timeout: 30, max-plugin-message: 1M }")
        assertEquals(4 shl 20, c.limits.frame); assertEquals(128 shl 20, c.limits.maxMessage); assertEquals(1 shl 20, c.limits.chunkBlob); assertEquals(2 shl 20, c.limits.itemBlob)
        assertEquals(8, c.limits.itemStacks); assertEquals(64 shl 10, c.fragmentSize); assertEquals(30_000, c.reassemblyTimeoutMs); assertEquals(1 shl 20, c.limits.maxPluginMessage)
    }

    @Test fun `a size that overflows a long is refused`() {
        assertFailsWith<ConfigException> { LimitsConfig.parseSize("max-message", "9999999999G") }
        assertEquals(1L shl 30, LimitsConfig.parseSize("max-message", "1G"))
    }

    @Test fun `strict validation names the key`() {
        assertTrue("limits.frame" in fail("limits: { frame: 0 }"))
        assertTrue("limits.frame" in fail("limits: { frame: -5 }"))
        assertTrue("limits.frame" in fail("limits: { frame: 2G, max-message: 1G }"))
        assertTrue("limits.max-message" in fail("limits: { frame: 32M, max-message: 16M }"))
        assertTrue("limits.item-stacks" in fail("limits: { item-stacks: 0 }"))
        assertTrue("limits.chunk-blob" in fail("limits: { chunk-blob: sixteen }"))
        assertTrue("limits.fragment-above" in fail("limits: { fragment-above: 100M }"))
        assertTrue("limits.reassembly-memory" in fail("limits: { reassembly-memory: 1M }"))
        assertTrue("limits.plugin-rate" in fail("limits: { plugin-rate: 0 }"))
        assertTrue("limits.plugin-inflight" in fail("limits: { plugin-inflight: 1M }"))
    }

    @Test fun `unknown keys are errors`() {
        val root = FissionConfig.parse("limits: { frames: 4M }"); LimitsConfig.from(root)
        assertTrue("limits.frames" in assertFailsWith<ConfigException> { root.finish() }.message!!)
    }
}
