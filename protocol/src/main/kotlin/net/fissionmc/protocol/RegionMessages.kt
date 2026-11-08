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

/** A scheduled block tick ([fluid] false, [type] = block id like `minecraft:frosted_ice`) or fluid tick ([fluid] true) at a position the sender does not own; [delay] in ticks, [priority] = TickPriority value. */
data class BorderTick(val x: Int, val y: Int, val z: Int, val fluid: Boolean, val type: String, val delay: Int, val priority: Int)

/** A neighbour update ("[block] next to this position changed") for a block the sender does not own. */
data class BorderNeighbor(val x: Int, val y: Int, val z: Int, val block: String)

/**
 * Backend -> master -> backend (101): work a backend produced in a chunk another backend owns (its copy there is a ghost: never ticked). Scheduled ticks and neighbour updates
 * of ghost positions are not run on the ghost, they are forwarded here and the owner runs them on its authoritative copy (so water keeps flowing, frosted ice keeps melting, a
 * torch next to a border notices its support gone). The sender sets [origin], the master delivers the message to [toBackend] as is.
 */
class BorderForward(val origin: String, val toBackend: String, val dimension: String, val ticks: List<BorderTick>, val neighbors: List<BorderNeighbor>) : NodeMessage

const val MAX_BORDER_ITEMS = 4096

/**
 * Backend -> master -> owner (102), about once a second: "players of [origin] look at these chunks of yours (a ghost there), keep them loaded and ticking". [chunks] = packed chunk coordinates
 * (x in the low 32 bits, z in the high 32). Without it the owner has no chunk loaded at a border nobody on its side stands at, and water, redstone and plants there would not run at all.
 */
class GhostDemand(val origin: String, val toBackend: String, val dimension: String, val chunks: LongArray) : NodeMessage

/** Backend -> master -> owner (103): [origin] holds a ghost copy of the owner's chunk (cx, cz); [hashes] = [SectionCodec.hash] of every section's block states, lowest section first. */
class GhostSync(val origin: String, val toBackend: String, val dimension: String, val cx: Int, val cz: Int, val hashes: IntArray) : NodeMessage

/** One section's block state ids (global `Block.getId`), run length encoded: pairs (stateId, count) over the 4096 positions in y, z, x order. */
class GhostSection(val index: Int, val runs: IntArray)

/** Owner -> master -> backend (104): the sections of chunk (cx, cz) whose hash differed from the ghost's, with the owner's states; the ghost copies them. [origin] = the owner. */
class GhostFix(val origin: String, val toBackend: String, val dimension: String, val cx: Int, val cz: Int, val sections: List<GhostSection>) : NodeMessage

/** Hash and run length coding of a section's block state ids (4096 entries, y, z, x order). Pure, shared by both ends. */
object SectionCodec {
    const val SIZE = 4096
    fun hash(ids: IntArray): Int { var h = -0x7ee3623b; for (v in ids) h = (h xor v) * 0x01000193; return h }
    fun encode(ids: IntArray): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < ids.size) { var j = i + 1; while (j < ids.size && ids[j] == ids[i]) j++; out += ids[i]; out += j - i; i = j }
        return out.toIntArray()
    }
    fun decode(runs: IntArray): IntArray {
        require(runs.size % 2 == 0) { "odd run list" }
        val out = IntArray(SIZE); var n = 0
        for (k in runs.indices step 2) { val c = runs[k + 1]; require(c > 0 && n + c <= SIZE) { "bad run" }; java.util.Arrays.fill(out, n, n + c, runs[k]); n += c }
        require(n == SIZE) { "runs cover $n of $SIZE" }
        return out
    }
}

const val MAX_GHOST_CHUNKS = 4096
const val MAX_GHOST_SECTIONS = 64

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
    .register<BorderForward>(101, { m, b ->
        b.writeString(m.origin, 64).writeString(m.toBackend, 64).writeString(m.dimension, 128)
        b.writeVarInt(m.ticks.size); m.ticks.forEach { t -> b.writeInt(t.x).writeInt(t.y).writeInt(t.z).writeBoolean(t.fluid).writeString(t.type, 128).writeVarInt(t.delay).writeVarInt(t.priority + 3) }
        b.writeVarInt(m.neighbors.size); m.neighbors.forEach { n -> b.writeInt(n.x).writeInt(n.y).writeInt(n.z).writeString(n.block, 128) }
    }, { b ->
        val o = b.readString(64); val t = b.readString(64); val d = b.readString(128)
        val nt = b.readVarInt(); require(nt in 0..MAX_BORDER_ITEMS) { "too many border ticks" }
        val ticks = List(nt) { BorderTick(b.readInt(), b.readInt(), b.readInt(), b.readBoolean(), b.readString(128), b.readVarInt(), b.readVarInt() - 3) }
        val nn = b.readVarInt(); require(nn in 0..MAX_BORDER_ITEMS) { "too many border neighbours" }
        BorderForward(o, t, d, ticks, List(nn) { BorderNeighbor(b.readInt(), b.readInt(), b.readInt(), b.readString(128)) })
    })
    .register<GhostDemand>(102, { m, b ->
        b.writeString(m.origin, 64).writeString(m.toBackend, 64).writeString(m.dimension, 128).writeVarInt(m.chunks.size); m.chunks.forEach { b.writeLong(it) }
    }, { b ->
        val o = b.readString(64); val t = b.readString(64); val d = b.readString(128); val n = b.readVarInt(); require(n in 0..MAX_GHOST_CHUNKS) { "too many ghost chunks" }
        GhostDemand(o, t, d, LongArray(n) { b.readLong() })
    })
    .register<GhostSync>(103, { m, b ->
        b.writeString(m.origin, 64).writeString(m.toBackend, 64).writeString(m.dimension, 128).writeInt(m.cx).writeInt(m.cz).writeVarInt(m.hashes.size); m.hashes.forEach { b.writeInt(it) }
    }, { b ->
        val o = b.readString(64); val t = b.readString(64); val d = b.readString(128); val x = b.readInt(); val z = b.readInt(); val n = b.readVarInt(); require(n in 0..MAX_GHOST_SECTIONS) { "too many sections" }
        GhostSync(o, t, d, x, z, IntArray(n) { b.readInt() })
    })
    .register<GhostFix>(104, { m, b ->
        b.writeString(m.origin, 64).writeString(m.toBackend, 64).writeString(m.dimension, 128).writeInt(m.cx).writeInt(m.cz).writeVarInt(m.sections.size)
        m.sections.forEach { s -> b.writeVarInt(s.index).writeVarInt(s.runs.size); s.runs.forEach { b.writeVarInt(it) } }
    }, { b ->
        val o = b.readString(64); val t = b.readString(64); val d = b.readString(128); val x = b.readInt(); val z = b.readInt(); val n = b.readVarInt(); require(n in 0..MAX_GHOST_SECTIONS) { "too many sections" }
        GhostFix(o, t, d, x, z, List(n) { val i = b.readVarInt(); val r = b.readVarInt(); require(r in 0..SectionCodec.SIZE * 2) { "too many runs" }; GhostSection(i, IntArray(r) { b.readVarInt() }) })
    })
