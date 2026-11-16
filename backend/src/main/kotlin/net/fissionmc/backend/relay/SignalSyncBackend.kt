package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.BlockEntitySignal
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.ComparatorBlockEntity

/** Keeps the comparator output of a ghost copy equal to the owner's, because the output lives in the block entity and is not part of the block state. */
object SignalSyncBackend {
    @JvmStatic fun onOutput(level: ServerLevel, pos: BlockPos, value: Int) {
        if (!RegionsBackend.enabled || !RegionsBackend.ownsBlockAt(level, pos)) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        val dim = level.dimension().identifier().toString()
        val targets = HashSet<String>()
        for (dx in -1..1) for (dz in -1..1) RegionsBackend.chunkOwner(dim, (pos.x shr 4) + dx, (pos.z shr 4) + dz)?.let { if (it != RegionsBackend.youId()) targets += it }
        for (t in targets) link.send(BlockEntitySignal(RegionsBackend.youId(), t, dim, pos.x, pos.y, pos.z, value))
    }

    fun onMessage(m: BlockEntitySignal, server: MinecraftServer?) {
        server?.execute {
            val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return@execute
            (level.getBlockEntity(BlockPos(m.x, m.y, m.z)) as? ComparatorBlockEntity)?.setOutputSignal(m.value)
        }
    }
}
