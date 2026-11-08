package net.fissionmc.protocol

/**
 * Collects the ticks and neighbour updates a backend produced for chunks another backend owns and turns them into [BorderForward] messages, one per (owner, dimension) and flush
 * (called once per server tick). Pure logic (no Minecraft types) so the routing and de-duplication are unit tested. Not thread safe: server thread only.
 *
 * [chunkOwner] maps (dimension, chunk x, chunk z) to the owning backend id, null = nobody known (then nothing is forwarded and the caller runs the work locally).
 */
class BorderBatcher(private val you: () -> String, private val chunkOwner: (String, Int, Int) -> String?) {
    private class Bucket { val ticks = LinkedHashMap<BorderTick, Unit>(); val neighbors = LinkedHashMap<BorderNeighbor, Unit>() }
    private val buckets = LinkedHashMap<Pair<String, String>, Bucket>()
    var forwardedTicks = 0L; private set
    var forwardedNeighbors = 0L; private set

    /** The owner of the chunk when it is another backend than this one, else null. */
    fun foreignOwner(dimension: String, x: Int, z: Int): String? {
        val o = chunkOwner(dimension, x shr 4, z shr 4) ?: return null
        return if (o == you()) null else o
    }

    /** Queues a tick for a foreign position; false (and nothing queued) when the position is ours or unowned. Identical queued ticks are merged. */
    fun addTick(dimension: String, t: BorderTick): Boolean {
        val o = foreignOwner(dimension, t.x, t.z) ?: return false
        bucket(o, dimension).ticks[t] = Unit; forwardedTicks++; return true
    }

    fun addNeighbor(dimension: String, n: BorderNeighbor): Boolean {
        val o = foreignOwner(dimension, n.x, n.z) ?: return false
        bucket(o, dimension).neighbors[n] = Unit; forwardedNeighbors++; return true
    }

    private fun bucket(owner: String, dim: String) = buckets.getOrPut(owner to dim) { Bucket() }

    /** The messages to send now (at most [MAX_BORDER_ITEMS] of each kind per message); empties the queue. */
    fun drain(): List<BorderForward> {
        if (buckets.isEmpty()) return emptyList()
        val out = ArrayList<BorderForward>()
        for ((k, b) in buckets) {
            val ticks = b.ticks.keys.toList().chunked(MAX_BORDER_ITEMS); val neighbors = b.neighbors.keys.toList().chunked(MAX_BORDER_ITEMS)
            for (i in 0 until maxOf(ticks.size, neighbors.size)) out += BorderForward(you(), k.first, k.second, ticks.getOrElse(i) { emptyList() }, neighbors.getOrElse(i) { emptyList() })
        }
        buckets.clear(); return out
    }
}
