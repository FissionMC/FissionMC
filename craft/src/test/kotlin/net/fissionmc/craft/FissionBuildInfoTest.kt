package net.fissionmc.craft

import io.papermc.paper.ServerBuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FissionBuildInfoTest {
    private val info = FissionBuildInfo()

    @Test fun versionStringsDoNotCarryTheMinecraftSuffix() {
        // CraftServer#getVersion() = asString(VERSION_SIMPLE) + " (MC: " + minecraft version + ")": the build info must not add the suffix itself
        for (rep in ServerBuildInfo.StringRepresentation.entries) {
            assertFalse(info.asString(rep).contains("(MC:"), rep.name)
        }
        val getVersion = info.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE) + " (MC: " + info.minecraftVersionId() + ")"
        assertEquals(1, Regex("""\(MC: (\d+\.\d+(\.\d+)?)\)""").findAll(getVersion).count())
        assertEquals("26.3", Regex("""\(MC: ([^)]+)\)""").find(getVersion)!!.groupValues[1])
    }

    @Test fun startsWithTheMinecraftVersionLikePaper() {
        assertTrue(info.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE).startsWith(info.minecraftVersionId() + "-"))
    }

    @Test fun brandIsCompatibleWithPaper() {
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID))
        assertTrue(info.isBrandCompatible(FissionBuildInfo.BRAND_FISSION_ID))
    }
}
