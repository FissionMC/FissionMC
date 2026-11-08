package net.fissionmc.backend.relay

import net.fissionmc.backend.replication.Replication
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.logging.Logger

/**
 * `/fission debug ...` on a backend console (only with -Dfission.debug / FISSION_DEBUG=1):
 *  - `entities [all]`: who owns every non-player entity of every level by the rule "owner = owner of the chunk it stands in": counts and a list of the foreign ones (a foreign entity is waiting for its hand-over);
 *  - `ghost-set <x> <y> <z> <block>`: writes a block into THIS backend's copy of a ghost chunk without replicating it (test hook that makes a ghost block), `ghost-sync`: counters of the ghost chunk checks.
 */
object BorderDebug {
    private val log = Logger.getLogger("FissionMC")
    @Volatile var server: MinecraftServer? = null

    fun run(args: Array<String>): List<String> {
        if (!net.fissionmc.net.FissionDebug.on) return listOf("debug commands need -Dfission.debug (or FISSION_DEBUG=1)")
        val srv = server ?: return listOf("server not ready")
        return when (args.firstOrNull()?.lowercase()) {
            "entities" -> entities(srv, args.getOrNull(1) == "all")
            "ghost-set" -> ghostSet(srv, args.drop(1))
            "ghost-sync" -> listOf("ghost sync: ${GhostSyncBackend.syncsSent.get()} checks sent, ${GhostSyncBackend.syncsMismatched.get()} answered with differences (as owner), ${GhostSyncBackend.blocksFixed.get()} ghost blocks repaired (as viewer), ${GhostSyncBackend.demandTicketsHeld.get()} demanded chunks kept ticking, refused placements ${Replication.refused}, pistons blocked ${RegionsBackend.pistonsBlocked.get()}, foreign entities not loaded ${RegionsBackend.foreignEntitiesSkipped.get()}")
            else -> listOf("Usage: /fission debug entities [all] | ghost-set <x> <y> <z> <block> | ghost-sync")
        }
    }

    /** Entity counts per level and owner status; the foreign ones (and with `all` every one) are listed. */
    fun entities(srv: MinecraftServer, all: Boolean): List<String> {
        val out = ArrayList<String>()
        out += "entity ownership on ${RegionsBackend.youId().ifEmpty { "(regions off)" }}:"
        for (level in srv.allLevels) {
            val dim = level.dimension().identifier().toString()
            var owned = 0; var foreign = 0; var removed = 0
            val lines = ArrayList<String>()
            for (e in level.allEntities) {
                if (e is ServerPlayer) continue
                val own = RegionsBackend.ownsEntity(e)
                if (e.isRemoved) removed++ else if (own) owned++ else foreign++
                if (all || !own) lines += "  ${if (own) "owned  " else "FOREIGN"} ${e.type.descriptionId} ${e.uuid} ${e.blockX},${e.blockY},${e.blockZ} tick=${e.tickCount}${if (e.isRemoved) " removed" else ""} owner=${RegionsBackend.chunkOwner(dim, e.blockX shr 4, e.blockZ shr 4) ?: "-"}"
            }
            out += " $dim: owned=$owned foreign=$foreign removed=$removed"
            out += lines.take(200)
        }
        out += " not loaded because they belong to another backend: ${RegionsBackend.foreignEntitiesSkipped.get()}"
        return out
    }

    private fun ghostSet(srv: MinecraftServer, a: List<String>): List<String> {
        if (a.size != 4) return listOf("Usage: /fission debug ghost-set <x> <y> <z> <block>")
        val level: ServerLevel = srv.overworld()
        val pos = BlockPos(a[0].toInt(), a[1].toInt(), a[2].toInt())
        val id = Identifier.tryParse(a[3]) ?: return listOf("bad block id")
        val block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null) ?: return listOf("unknown block ${a[3]}")
        Replication.applyRemote(level, pos, block.defaultBlockState())
        return listOf("ghost-set ${pos.x},${pos.y},${pos.z} = ${a[3]} (not replicated)")
    }

    fun install(srv: MinecraftServer) {
        server = srv
        net.fissionmc.craft.builtin.FissionStatus.debug = java.util.function.Function { args ->
            if (srv.isSameThread) return@Function try { run(args) } catch (t: Throwable) { listOf("failed: $t") }
            val f = java.util.concurrent.CompletableFuture<List<String>>()
            srv.execute { f.complete(try { run(args) } catch (t: Throwable) { listOf("failed: $t") }) }
            try { f.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (t: Throwable) { listOf("timeout: $t") }
        }
    }
}
