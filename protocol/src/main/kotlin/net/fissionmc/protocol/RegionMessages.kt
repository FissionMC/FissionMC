package net.fissionmc.protocol

/**
 * Static regions (phase 2), wire ids 77-81.
 * Coordinates are block coordinates, rectangles are half open `[x1, x2) x [z1, z2)`; `Int.MIN_VALUE` / `Int.MAX_VALUE` stand for "no limit".
 */
data class RegionRule(val dimension: String, val x1: Int, val z1: Int, val x2: Int, val z2: Int, val backend: String)

/** Master -> backend (77), sent when the backend joins and whenever the map changes: [you] is the backend's own node id. An empty rule list = regions off, every backend owns everything. */
data class RegionMap(val you: String, val rules: List<RegionRule>) : NodeMessage

/** Master -> backend (78): generate this chunk (the backend is its generation owner): load it to FULL through the normal path, which leases, generates and pushes it. */
data class GenerateChunk(val dimension: String, val x: Int, val z: Int) : NodeMessage

/** Backend -> master (79): where a relayed player is now (block x/z), sent when its chunk changes. The master compares it with the region map. */
data class PlayerPosition(val uuidHigh: Long, val uuidLow: Long, val dimension: String, val x: Int, val z: Int) : NodeMessage

/**
 * Backend -> master (80), master -> backend (80): entities that left their owner's region and now belong to [toBackend] ([blobs] as in [EntityTransfer],
 * their own position inside). The target spawns them and answers [EntityHandoverAck]; then the origin discards its copies.
 */
class EntityHandover(val origin: String, val toBackend: String, val dimension: String, val blobs: List<EntityBlob>) : NodeMessage

/** Target backend -> master (81): the handed over entities (uuid pairs high/low) are spawned; the master tells [origin] to remove them ([EntityRemove]). */
class EntityHandoverAck(val origin: String, val spawned: List<Long>) : NodeMessage

fun MessageRegistry.registerRegionMessages(): MessageRegistry = this
    .register<RegionMap>(77, { m, b ->
        b.writeString(m.you, 64).writeVarInt(m.rules.size)
        m.rules.forEach { r -> b.writeString(r.dimension, 128).writeInt(r.x1).writeInt(r.z1).writeInt(r.x2).writeInt(r.z2).writeString(r.backend, 64) }
    }, { b ->
        val you = b.readString(64); val n = b.readVarInt(); require(n in 0..4096) { "too many region rules" }
        RegionMap(you, List(n) { RegionRule(b.readString(128), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readString(64)) })
    })
    .register<GenerateChunk>(78, { m, b -> b.writeString(m.dimension, 128).writeInt(m.x).writeInt(m.z) }, { b -> GenerateChunk(b.readString(128), b.readInt(), b.readInt()) })
    .register<PlayerPosition>(79, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeString(m.dimension, 128).writeInt(m.x).writeInt(m.z) },
        { b -> PlayerPosition(b.readLong(), b.readLong(), b.readString(128), b.readInt(), b.readInt()) })
    .register<EntityHandover>(80, { m, b ->
        b.writeString(m.origin, 64).writeString(m.toBackend, 64).writeString(m.dimension, 128).writeVarInt(m.blobs.size)
        m.blobs.forEach { e -> b.writeVarInt(e.kind).writeLong(e.uuidHigh).writeLong(e.uuidLow).writeByteArray(e.bytes, MAX_ENTITY_BLOB) }
    }, { b ->
        val o = b.readString(64); val t = b.readString(64); val d = b.readString(128); val n = b.readVarInt(); require(n in 0..MAX_ENTITY_BLOBS) { "too many entities" }
        EntityHandover(o, t, d, List(n) { EntityBlob(b.readVarInt(), b.readLong(), b.readLong(), b.readByteArray(MAX_ENTITY_BLOB)) })
    })
    .register<EntityHandoverAck>(81, { m, b -> b.writeString(m.origin, 64).writeVarInt(m.spawned.size); m.spawned.forEach { b.writeLong(it) } },
        { b -> val o = b.readString(64); val n = b.readVarInt(); require(n in 0..MAX_ENTITY_BLOBS * 2) { "too many" }; EntityHandoverAck(o, List(n) { b.readLong() }) })
