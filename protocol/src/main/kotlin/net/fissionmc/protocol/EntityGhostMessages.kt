package net.fissionmc.protocol


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

/**
 * Backend to master (171): the leashes of the entities of [dimension] that [origin] shows as ghosts, as a COMPLETE snapshot: [links] are uuid quadruples (entity high, low, holder high, low).
 * The master shows a link (ClientboundSetEntityLink) to a viewer when both ends are ghosts for it; a pair missing from a newer snapshot is unlinked.
 */
class EntityGhostLinks(val origin: String, val dimension: String, val links: List<Long>) : NodeMessage

/** Master to owner (109): the player is touching this item entity of yours (its backend is [playerBackend]); take it. Answered by [ItemGive] when the item was still there. */
class ItemPickup(val toBackend: String, val entityHigh: Long, val entityLow: Long, val playerHigh: Long, val playerLow: Long, val playerBackend: String) : NodeMessage

/** Owner to master to the player's backend (110): the item stack the player picked up across the border. */
class ItemGive(val toBackend: String, val playerHigh: Long, val playerLow: Long, val stack: ByteArray) : NodeMessage

/**
 * Owner to master to the player's backend (212, replaces [ItemGive] for new senders): like [ItemGive] but names the owner ([origin], checked by the master) and the item entity
 * ([entityHigh]/[entityLow]) so the player's backend can answer with an [ItemGiveAck]. The owner removes the item entity only after it was acknowledged.
 */
class ItemGiveTracked(val toBackend: String, val origin: String, val entityHigh: Long, val entityLow: Long, val playerHigh: Long, val playerLow: Long, val stack: ByteArray) : NodeMessage

/**
 * Player's backend to master to the owner (211), also sent by the master itself when it refuses an [ItemGiveTracked]: the give of item entity ([entityHigh],[entityLow]) was
 * [accepted] (the stack is in the player's inventory or dropped at her feet) or not (the player is gone / the stack could not be read: the owner keeps or re-spawns the item).
 */
class ItemGiveAck(val toBackend: String, val entityHigh: Long, val entityLow: Long, val accepted: Boolean) : NodeMessage

/** [EntityGhostBatch.removed] holds 2 longs per uuid and the decoder takes at most `MAX_ENTITY_GHOSTS` uuids: a longer list goes out in several batches. */
fun splitRemoved(removed: List<Long>): List<List<Long>> = if (removed.size <= MAX_ENTITY_GHOSTS * 2) listOf(removed) else removed.chunked(MAX_ENTITY_GHOSTS * 2)

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
    .register<EntityGhostLinks>(171, { m, b -> b.writeString(m.origin, 64).writeString(m.dimension, 128).writeVarInt(m.links.size); m.links.forEach { b.writeLong(it) } },
        { b -> val o = b.readString(64); val d = b.readString(128); val n = b.readVarInt(); require(n in 0..MAX_ENTITY_GHOSTS * 4 && n % 4 == 0) { "too many links" }; EntityGhostLinks(o, d, List(n) { b.readLong() }) })
    .register<ItemPickup>(109, { m, b -> b.writeString(m.toBackend, 64).writeLong(m.entityHigh).writeLong(m.entityLow).writeLong(m.playerHigh).writeLong(m.playerLow).writeString(m.playerBackend, 64) },
        { b -> ItemPickup(b.readString(64), b.readLong(), b.readLong(), b.readLong(), b.readLong(), b.readString(64)) })
    .register<ItemGive>(110, { m, b -> b.writeString(m.toBackend, 64).writeLong(m.playerHigh).writeLong(m.playerLow).writeByteArray(m.stack, MAX_ENTITY_GHOST_BLOB) },
        { b -> ItemGive(b.readString(64), b.readLong(), b.readLong(), b.readByteArray(MAX_ENTITY_GHOST_BLOB)) })
    .register<ItemGiveAck>(211, { m, b -> b.writeString(m.toBackend, 64).writeLong(m.entityHigh).writeLong(m.entityLow).writeBoolean(m.accepted) },
        { b -> ItemGiveAck(b.readString(64), b.readLong(), b.readLong(), b.readBoolean()) })
    .register<ItemGiveTracked>(212, { m, b -> b.writeString(m.toBackend, 64).writeString(m.origin, 64).writeLong(m.entityHigh).writeLong(m.entityLow).writeLong(m.playerHigh).writeLong(m.playerLow).writeByteArray(m.stack, MAX_ENTITY_GHOST_BLOB) },
        { b -> ItemGiveTracked(b.readString(64), b.readString(64), b.readLong(), b.readLong(), b.readLong(), b.readLong(), b.readByteArray(MAX_ENTITY_GHOST_BLOB)) })
    .register<NodeTickRate>(213, { m, b -> b.writeVarInt(m.msPerTick) }, { b -> NodeTickRate(b.readVarInt().coerceIn(1, 3_600_000)) })
