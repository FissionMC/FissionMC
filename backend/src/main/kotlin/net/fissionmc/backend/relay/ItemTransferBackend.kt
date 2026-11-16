package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.ItemTransfer
import net.fissionmc.protocol.MAX_ITEM_BLOB
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.HopperBlockEntity
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.logging.Logger
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Sends items pushed into ghost containers to the owning backend and inserts received ones; what does not fit is dropped. */
object ItemTransferBackend {
    private val log = Logger.getLogger("FissionMC")
    @JvmField val sent = java.util.concurrent.atomic.AtomicLong()
    @JvmField val received = java.util.concurrent.atomic.AtomicLong()
    @JvmField val dropped = java.util.concurrent.atomic.AtomicLong()

    private fun encode(tag: CompoundTag): ByteArray { val o = ByteArrayOutputStream(); DataOutputStream(DeflaterOutputStream(o)).use { NbtIo.write(tag, it) }; return o.toByteArray() }
    private fun decode(b: ByteArray): CompoundTag = DataInputStream(InflaterInputStream(b.inputStream()).buffered()).use { NbtIo.read(it, NbtAccounter.unlimitedHeap()) }

    @JvmStatic fun ghostContainerAt(level: ServerLevel, pos: BlockPos): Boolean = RegionsBackend.enabled && !RegionsBackend.ownsBlockAt(level, pos) && level.getBlockEntity(pos) != null

    @JvmStatic fun send(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
        val owner = RegionsBackend.chunkOwner(level.dimension().identifier().toString(), pos.x shr 4, pos.z shr 4)
        val link = NodeBootstrap.link?.takeIf { it.isOpen }
        if (owner == null || owner == RegionsBackend.youId() || link == null) { drop(level, pos, stack); return }
        val tag = ItemStack.OPTIONAL_CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).result().orElse(null) as? CompoundTag
        val bytes = tag?.let { encode(it) }
        if (bytes == null || bytes.size > MAX_ITEM_BLOB) { drop(level, pos, stack); return }
        sent.incrementAndGet()
        link.send(ItemTransfer(RegionsBackend.youId(), owner, level.dimension().identifier().toString(), pos.x, pos.y, pos.z, listOf(bytes)))
    }

    fun onMessage(m: ItemTransfer, server: MinecraftServer?) { server?.execute { apply(server, m) } }

    private fun apply(server: MinecraftServer, m: ItemTransfer) {
        val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return
        val pos = BlockPos(m.x, m.y, m.z)
        for (bytes in m.stacks) {
            val stack = try { ItemStack.OPTIONAL_CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), decode(bytes)).result().orElse(ItemStack.EMPTY) } catch (t: Throwable) { log.warning("[fission] item transfer: bad stack from ${m.origin}: $t"); ItemStack.EMPTY }
            if (stack.isEmpty) continue
            received.incrementAndGet()
            val container = HopperBlockEntity.getContainerAt(level, pos)
            val rest = if (container != null) HopperBlockEntity.addItem(null, container, stack, null) else stack
            if (!rest.isEmpty) drop(level, pos, rest)
        }
    }

    private fun drop(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
        dropped.incrementAndGet()
        level.addFreshEntity(ItemEntity(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, stack))
    }
}
