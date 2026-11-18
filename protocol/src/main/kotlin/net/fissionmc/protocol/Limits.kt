package net.fissionmc.protocol

import io.netty.buffer.ByteBuf

/**
 * The size caps of the node link, as one value. Each node has an OWN set (from `limits:` in fission.yml, set once at start with [Limits.setOwn]); what a node
 * ACCEPTS is always its own set. At the end of the handshake both sides send theirs ([LinkLimits]) and a sender encodes with the minimum of both
 * ([Limits.min]), so it never produces what the peer would refuse. All values are bytes except the `*s` counts (entityBlobs, itemStacks, entityGhosts, ghostChunks).
 */
data class Limits(
    /** Largest single frame on the wire. Bigger messages are split by the link (see Fragment) up to [maxMessage]. */
    val frame: Int = 16 shl 20,
    /** Largest encoded message (after reassembly). */
    val maxMessage: Int = 64 shl 20,
    val chunkBlob: Int = 16 shl 20,
    val playerData: Int = 16 shl 20,
    val entityBlob: Int = 8 shl 20,
    val entityBlobs: Int = 256,
    val itemBlob: Int = 4 shl 20,
    val itemStacks: Int = 256,
    val entityGhostBlob: Int = 1 shl 20,
    val entityGhosts: Int = 1024,
    val playerFrame: Int = 16 shl 20,
    val ghostChunks: Int = 16384,
    /** The cap of every string that has no cap of its own (vanilla: 32767). */
    val string: Int = 1 shl 20,
    val maxPluginMessage: Int = 8 shl 20,
) {
    /** Field by field minimum: what a sender may use towards a peer that accepts [o]. */
    fun min(o: Limits) = Limits(
        minOf(frame, o.frame), minOf(maxMessage, o.maxMessage), minOf(chunkBlob, o.chunkBlob), minOf(playerData, o.playerData), minOf(entityBlob, o.entityBlob), minOf(entityBlobs, o.entityBlobs),
        minOf(itemBlob, o.itemBlob), minOf(itemStacks, o.itemStacks), minOf(entityGhostBlob, o.entityGhostBlob), minOf(entityGhosts, o.entityGhosts), minOf(playerFrame, o.playerFrame),
        minOf(ghostChunks, o.ghostChunks), minOf(string, o.string), minOf(maxPluginMessage, o.maxPluginMessage),
    )

    /** `[name, value]` per field in wire order; the ids are wire format (append only). */
    private fun values(): IntArray = intArrayOf(frame, maxMessage, chunkBlob, playerData, entityBlob, entityBlobs, itemBlob, itemStacks, entityGhostBlob, entityGhosts, playerFrame, ghostChunks, string, maxPluginMessage)

    fun write(b: ByteBuf) { val v = values(); b.writeVarInt(v.size); v.forEachIndexed { i, x -> b.writeVarInt(i); b.writeInt(x) } }

    companion object {
        /** Config key (kebab case) of each field, in the same order as [values]: the documented names. */
        val NAMES = listOf("frame", "max-message", "chunk-blob", "player-data", "entity-blob", "entity-blobs", "item-blob", "item-stacks", "entity-ghost-blob", "entity-ghosts", "player-frame", "ghost-chunks", "string", "max-plugin-message")

        /** What nodes before this feature used (fixed constants); also what a field a peer did not send is assumed to be. */
        val LEGACY = Limits(frame = 8 shl 20, maxMessage = 8 shl 20, chunkBlob = 4 shl 20, playerData = 4 shl 20, entityBlob = 1 shl 20, entityBlobs = 64, itemBlob = 65536, itemStacks = 64,
            entityGhostBlob = 65536, entityGhosts = 512, playerFrame = 8 shl 20, ghostChunks = 4096, string = 32767, maxPluginMessage = 8 shl 20)

        fun from(values: IntArray, base: Limits = LEGACY): Limits {
            fun v(i: Int, d: Int) = if (i < values.size && values[i] > 0) values[i] else d
            return Limits(v(0, base.frame), v(1, base.maxMessage), v(2, base.chunkBlob), v(3, base.playerData), v(4, base.entityBlob), v(5, base.entityBlobs), v(6, base.itemBlob), v(7, base.itemStacks),
                v(8, base.entityGhostBlob), v(9, base.entityGhosts), v(10, base.playerFrame), v(11, base.ghostChunks), v(12, base.string), v(13, base.maxPluginMessage))
        }

        fun read(b: ByteBuf): Limits {
            val n = b.readVarInt(); require(n in 0..64) { "bad limits count $n" }
            val v = IntArray(64)
            repeat(n) { val k = b.readVarInt(); val x = b.readInt(); if (k in 0 until 64) v[k] = x }   // unknown (newer) fields are skipped
            return from(v)
        }

        /** Throws [IllegalArgumentException] naming the field when a value is outside what a node can sanely run with. */
        fun validate(l: Limits): Limits {
            val v = l.values()
            val max = intArrayOf(1 shl 30, 1 shl 30, 1 shl 30, 1 shl 30, 1 shl 30, 1_000_000, 1 shl 30, 1_000_000, 1 shl 30, 1_000_000, 1 shl 30, 1_000_000, 1 shl 26, 1 shl 30)
            val min = intArrayOf(1 shl 20, 1 shl 20, 64 * 1024, 64 * 1024, 64 * 1024, 1, 4096, 1, 4096, 1, 64 * 1024, 1, 1024, 1024)
            for (i in v.indices) require(v[i] in min[i]..max[i]) { "limits.${NAMES[i]}: expected ${min[i]} to ${max[i]}, got ${v[i]}" }
            require(l.frame <= l.maxMessage) { "limits.frame (${l.frame}) must not exceed limits.max-message (${l.maxMessage})" }
            for ((name, size) in listOf("chunk-blob" to l.chunkBlob, "player-data" to l.playerData, "entity-blob" to l.entityBlob, "item-blob" to l.itemBlob, "entity-ghost-blob" to l.entityGhostBlob, "player-frame" to l.playerFrame, "max-plugin-message" to l.maxPluginMessage))
                require(size <= l.maxMessage) { "limits.$name ($size) must not exceed limits.max-message (${l.maxMessage}): a message that big cannot be sent" }
            return l
        }

        @Volatile private var own: Limits = Limits()
        private val peerForThread = ThreadLocal<Limits?>()

        /** The caps this node accepts. Set once at start from the config. */
        fun own(): Limits = own
        fun setOwn(l: Limits) { own = validate(l) }

        /** The caps in force on this thread: while a link encodes a message that is own and the peer's minimum, otherwise our own. */
        fun cur(): Limits = peerForThread.get() ?: own

        /** Runs [block] (an encode) with [peer] limited to what that peer accepts. */
        inline fun <T> towards(peer: Limits?, block: () -> T): T { val prev = swap(peer?.min(own())); try { return block() } finally { swap(prev) } }
        @PublishedApi internal fun swap(l: Limits?): Limits? { val p = peerForThread.get(); if (l == null) peerForThread.remove() else peerForThread.set(l); return p }
    }
}

// the caps the codecs and the senders read: always the current value, never baked in (they were const vals before)
val MAX_CHUNK_BLOB: Int get() = Limits.cur().chunkBlob
val MAX_PLAYER_DATA: Int get() = Limits.cur().playerData
val MAX_ENTITY_BLOB: Int get() = Limits.cur().entityBlob
val MAX_ENTITY_BLOBS: Int get() = Limits.cur().entityBlobs
val MAX_ITEM_BLOB: Int get() = Limits.cur().itemBlob
val MAX_ITEM_TRANSFER: Int get() = Limits.cur().itemStacks
val MAX_ENTITY_GHOST_BLOB: Int get() = Limits.cur().entityGhostBlob
val MAX_ENTITY_GHOSTS: Int get() = Limits.cur().entityGhosts
val MAX_PLAYER_FRAME: Int get() = Limits.cur().playerFrame
val MAX_GHOST_CHUNKS: Int get() = Limits.cur().ghostChunks
