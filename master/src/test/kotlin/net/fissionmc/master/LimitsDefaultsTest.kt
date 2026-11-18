package net.fissionmc.master

import net.fissionmc.net.FissionConfig
import net.fissionmc.net.LimitsConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LimitsDefaultsTest {
    @Test fun `the documented defaults of master and backend files are the built in defaults`() {
        for (f in listOf("src/main/resources/fission.default.yml", "../backend/src/main/resources/fission.backend.default.yml")) {
            val p = Path.of(f); if (!Files.exists(p)) continue
            val root = FissionConfig.parse(Files.readString(p))
            assertEquals(LimitsConfig(), LimitsConfig.from(root), f)
        }
    }

    @Test fun `master config reads the limits section and rejects bad ones`() {
        val dir = Path.of(".")
        val yml = "node: { mode: master, listen: \"127.0.0.1:1\", security: false }\nlimits: { max-plugin-message: 2M, plugin-rate: 5 }\n"
        val c = MasterConfig.read(FissionConfig.parse(yml), dir)
        assertEquals(2 shl 20, c.limits.limits.maxPluginMessage); assertEquals(5, c.limits.pluginRate)
        assertFailsWith<IllegalArgumentException> { MasterConfig.read(FissionConfig.parse(yml.replace("plugin-rate: 5", "plugin-rate: 0")), dir) }
        assertFailsWith<IllegalArgumentException> { MasterConfig.read(FissionConfig.parse(yml.replace("plugin-rate: 5", "bogus: 1")), dir) }
    }
}
