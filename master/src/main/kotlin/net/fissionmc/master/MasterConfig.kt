package net.fissionmc.master

import net.fissionmc.master.client.ClientConfig
import net.fissionmc.master.client.MasterSettings
import net.fissionmc.master.region.RegionMap
import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection
import net.fissionmc.net.NodeConfig
import java.nio.file.Path

/**
 * Everything the master reads from `fission.yml`, in one place (the typed layer over [ConfigSection]; every key is documented in `fission.default.yml`). [read] fails with a
 * [ConfigException] / IllegalArgumentException naming the key path for a wrong type, an unknown key, an unusable value or an invalid region map.
 */
data class MasterConfig(
    val node: NodeConfig, val client: ClientConfig, val settings: MasterSettings, val regions: RegionMap, val ghostZone: Int, val borderStrip: Int,
    val chunkAuthority: Boolean, val chunkDeterministic: Set<String>, val leaseSeconds: Long, val chunkStatsSeconds: Long, val chunkStoreDir: String, val playerDataDir: String,
    val debugPackets: Boolean, val debugDumpClientStream: String?, val debugRelayProbe: Boolean, val debugRecordLimbo: String?,
    val worldSettings: net.fissionmc.master.worldsettings.WorldSettingsConfig = net.fissionmc.master.worldsettings.WorldSettingsConfig(),
) {
    companion object {
        fun read(root: ConfigSection, dir: Path, fileName: String = "fission.yml", log: (String) -> Unit = {}): MasterConfig {
            val node = NodeConfig.from(root, dir)
            if (!node.masterProcess) throw ConfigException("node.mode: $fileName must set mode master for the master process")
            val regions = RegionMap.parse(root)
            val r = root.section("regions")
            val ghost = r.int("ghost-zone", 12).also { if (it !in 1..64) throw ConfigException("regions.ghost-zone: expected 1-64, got $it") }
            val strip = r.int("border-strip", 1).also { if (it < 0) throw ConfigException("regions.border-strip: expected 0 or more, got $it") }
            val client = ClientConfig.from(root)
            val settings = MasterSettings.from(root, dir.resolve("admins.txt"), log)
            val c = root.section("chunk")
            val det: Set<String> = c.stringList("deterministic").toSet()   // `true` (all) is the text "true"
            val chunkAuthority = c.bool("authority", true); val lease = c.long("lease-seconds", 60L); val stats = c.long("stats-interval", 30L)
            val storeDir = c.string("store-dir", "chunkstore").ifBlank { "chunkstore" }
            val playerDir = root.section("playerdata").string("dir", "playerdata").ifBlank { "playerdata" }
            val d = root.section("debug")
            val cfg = MasterConfig(node, client, settings, regions, ghost, strip, chunkAuthority, det, lease, stats, storeDir, playerDir,
                d.bool("packets", false), d.optString("dump-client-stream")?.takeIf { it.isNotBlank() }, d.bool("relay-probe", false), d.optString("record-limbo")?.takeIf { it.isNotBlank() },
                net.fissionmc.master.worldsettings.WorldSettingsConfig.from(root))
            root.finish()
            return cfg
        }
    }
}
