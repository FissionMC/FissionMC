package net.fissionmc.net

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigWatcherTest {
    @Test fun changedKeysAreDottedPathsOfDifferingLeaves() {
        val a = "client:\n  listen: \"0.0.0.0:25565\"\n  motd: hi\nnode:\n  listen: \"127.0.0.1:25600\"\n"
        val b = "client:\n  listen: \"0.0.0.0:25585\"\n  motd: hi\nnode:\n  listen: \"127.0.0.1:25600\"\n  extra: 1\n"
        assertEquals(setOf("client.listen", "node.extra"), ConfigWatcher.changedKeys(a, b))
        assertTrue(ConfigWatcher.changedKeys(a, a + "# only a comment\n").isEmpty())
    }

    @Test fun listsCountAsOneValue() {
        val a = "regions:\n  dimensions:\n    overworld:\n      - { area: [min, min, 0, max], owner: a }\n"
        val b = "regions:\n  dimensions:\n    overworld:\n      - { area: [min, min, 8, max], owner: a }\n"
        assertEquals(setOf("regions.dimensions.overworld"), ConfigWatcher.changedKeys(a, b))
    }

    @Test fun writingTheFileFiresOnceWithOldAndNewText() {
        val dir = Files.createTempDirectory("cfgwatch")
        val f = dir.resolve("fission.yml")
        Files.writeString(f, "a: 1\n")
        val fired = CountDownLatch(1)
        val seen = ArrayList<Pair<String, String>>()
        ConfigWatcher(f, { }) { o, n -> seen += o to n; fired.countDown() }.start().use {
            Files.writeString(f, "a: 2\n")
            assertTrue(fired.await(10, TimeUnit.SECONDS), "no change event")
        }
        assertEquals("a: 1\n" to "a: 2\n", seen.single())
    }
}
