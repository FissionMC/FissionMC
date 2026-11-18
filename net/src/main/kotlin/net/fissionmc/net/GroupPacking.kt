package net.fissionmc.net

/**
 * Splits [groups] (items that must stay in one message, e.g. an entity with what is leashed to it) into messages of at most [maxCount] items AND at most [maxBytes]
 * (by [sizeOf]); no group is split. A group that alone is over either cap goes to [tooBig] (it can never be sent: the caller says so and keeps it where it is) instead
 * of a message that the writer would refuse again and again.
 */
fun <T> packGroupsSized(groups: List<List<T>>, maxCount: Int, maxBytes: Long, sizeOf: (T) -> Int, tooBig: (List<T>) -> Unit = {}): List<List<T>> {
    val out = ArrayList<List<T>>(); var cur = ArrayList<T>(); var curBytes = 0L
    for (g in groups) {
        val gb = g.sumOf { sizeOf(it).toLong() }
        if (g.size > maxCount || gb > maxBytes) { tooBig(g); continue }
        if (cur.isNotEmpty() && (cur.size + g.size > maxCount || curBytes + gb > maxBytes)) { out += cur; cur = ArrayList(); curBytes = 0 }
        cur += g; curBytes += gb
    }
    if (cur.isNotEmpty()) out += cur
    return out
}
