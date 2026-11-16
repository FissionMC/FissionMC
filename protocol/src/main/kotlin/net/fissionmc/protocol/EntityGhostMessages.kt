package net.fissionmc.protocol

const val MAX_ENTITY_GHOSTS = 512
const val MAX_ENTITY_GHOST_BLOB = 65536

/**
 * One entity near a region border as the owning backend sees it. [flags]: 1 = [add] is set (the vanilla add entity packet from its entity id on), 2 = [data] is set
 * (set entity data payload after the id), 4 = [equip] is set (set equipment payload after the id, possibly empty). Unset parts are unchanged since the last batch.
 */
class EntityGhost(
    val uuidHigh: Long, val uuidLow: Long, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val headYaw: Float, val onGround: Boolean,
    val flags: Int, val add: ByteArray, val data: ByteArray, val equip: ByteArray,
)

/** Backend to master (108): entities near a border that players of other backends may see, and the uuid pairs (high, low) of those that are gone. */
class EntityGhostBatch(val origin: String, val dimension: String, val upserts: List<EntityGhost>, val removed: List<Long>) : NodeMessage

fun MessageRegistry.registerEntityGhostMessages(): MessageRegistry = this
    .register<EntityGhostBatch>(108, { m, b ->
        b.writeString(m.origin, 64).writeString(m.dimension, 128).writeVarInt(m.upserts.size)
        m.upserts.forEach { e ->
            b.writeLong(e.uuidHigh).writeLong(e.uuidLow).writeDouble(e.x).writeDouble(e.y).writeDouble(e.z).writeFloat(e.yaw).writeFloat(e.pitch).writeFloat(e.headYaw).writeBoolean(e.onGround).writeByte(e.flags)
            if (e.flags and 1 != 0) b.writeByteArray(e.add, MAX_ENTITY_GHOST_BLOB)
            if (e.flags and 2 != 0) b.writeByteArray(e.data, MAX_ENTITY_GHOST_BLOB)
            if (e.flags and 4 != 0) b.writeByteArray(e.equip, MAX_ENTITY_GHOST_BLOB)
        }
        b.writeVarInt(m.removed.size); m.removed.forEach { b.writeLong(it) }
    }, { b ->
        val o = b.readString(64); val d = b.readString(128); val n = b.readVarInt(); require(n in 0..MAX_ENTITY_GHOSTS) { "too many entity ghosts" }
        val ups = List(n) {
            val uh = b.readLong(); val ul = b.readLong(); val x = b.readDouble(); val y = b.readDouble(); val z = b.readDouble(); val yaw = b.readFloat(); val pitch = b.readFloat(); val head = b.readFloat(); val g = b.readBoolean(); val f = b.readByte().toInt()
            val add = if (f and 1 != 0) b.readByteArray(MAX_ENTITY_GHOST_BLOB) else ByteArray(0)
            val data = if (f and 2 != 0) b.readByteArray(MAX_ENTITY_GHOST_BLOB) else ByteArray(0)
            val eq = if (f and 4 != 0) b.readByteArray(MAX_ENTITY_GHOST_BLOB) else ByteArray(0)
            EntityGhost(uh, ul, x, y, z, yaw, pitch, head, g, f, add, data, eq)
        }
        val rn = b.readVarInt(); require(rn in 0..MAX_ENTITY_GHOSTS * 2) { "too many removed" }
        EntityGhostBatch(o, d, ups, List(rn) { b.readLong() })
    })
