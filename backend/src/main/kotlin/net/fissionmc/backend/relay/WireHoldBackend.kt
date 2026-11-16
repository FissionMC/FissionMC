package net.fissionmc.backend.relay

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RedstoneWireBlock
import java.util.function.IntSupplier

/** Stops dust next to a border from re-reading a neighbour copy's stale power right after the local side dropped. */
object WireHoldBackend {
    private const val HOLD_TICKS = 6L
    private class Hold(val level: ServerLevel, val pos: BlockPos, val until: Long)
    private val holds = HashMap<Long, Hold>()
    private val ignoreIn = ThreadLocal<String?>()

    @JvmStatic fun ignoringGhosts(pos: BlockPos): Boolean {
        val dim = ignoreIn.get() ?: return false
        return !RegionsBackend.ownsBlock(dim, pos.x, pos.z)
    }

    private fun touchesGhost(dim: String, pos: BlockPos): Boolean {
        for (dx in -1..1) for (dz in -1..1) if ((dx != 0 || dz != 0) && !RegionsBackend.ownsBlock(dim, pos.x + dx, pos.z + dz)) return true
        return false
    }

    /**
     * @param level the level of the wire
     * @param pos the wire being evaluated
     * @param withGhosts the strength computed with the neighbour copies included
     * @param withoutGhosts computes the strength with every ghost position read as unpowered
     * @return the strength to use
     */
    @JvmStatic fun adjust(level: ServerLevel, pos: BlockPos, withGhosts: Int, withoutGhosts: IntSupplier): Int {
        if (!RegionsBackend.enabled || ignoreIn.get() != null) return withGhosts
        val dim = level.dimension().identifier().toString()
        if (!RegionsBackend.ownsBlock(dim, pos.x, pos.z) || !touchesGhost(dim, pos)) return withGhosts
        val key = pos.asLong() xor (dim.hashCode().toLong() shl 40)
        val now = level.gameTime
        val local = {
            ignoreIn.set(dim)
            try { withoutGhosts.asInt } finally { ignoreIn.set(null) }
        }
        val h = holds[key]
        if (h != null && now < h.until) return local()
        val state = level.getBlockState(pos)
        if (!state.`is`(Blocks.REDSTONE_WIRE)) return withGhosts
        val current = state.getValue(RedstoneWireBlock.POWER)
        if (withGhosts < current) {
            val t0 = local()
            if (t0 < withGhosts) { holds[key] = Hold(level, pos.immutable(), now + HOLD_TICKS); return t0 }
        }
        return withGhosts
    }

    fun tick(server: MinecraftServer) {
        if (holds.isEmpty()) return
        val it = holds.entries.iterator()
        while (it.hasNext()) {
            val h = it.next().value
            if (h.level.gameTime >= h.until) { it.remove(); if (h.level.isLoaded(h.pos)) h.level.neighborChanged(h.pos, Blocks.REDSTONE_WIRE, null) }
        }
    }
}
