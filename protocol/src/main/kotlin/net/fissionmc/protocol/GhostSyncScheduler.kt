package net.fissionmc.protocol

/**
 * Decides which ghost chunks a backend asks its owner about, and when: a chunk that is new (or was reloaded: another [instance]) goes first, every other chunk is re-checked every
 * [intervalTicks] (any unit, as long as [pick] is given the same one for `now`: the backend uses wall-clock ms); at most [budget] per call (or the `budget` passed to [pick]: a slow ticking server catches up with more), so the cost stays low however many ghost chunks the players see. Pure logic, server thread only.
 */
class GhostSyncScheduler(private val intervalTicks: Long, private val budget: Int) {
    class Candidate(val key: Long, val instance: Int)
    private class Seen(val instance: Int, var tick: Long)
    private val seen = HashMap<Long, Seen>()

    fun pick(now: Long, candidates: Collection<Candidate>, budget: Int = this.budget): List<Candidate> {
        if (seen.size > candidates.size * 2 + 64) { val keys = candidates.mapTo(HashSet()) { it.key }; seen.keys.retainAll(keys) }   // forget unloaded chunks
        val fresh = ArrayList<Candidate>(); val due = ArrayList<Pair<Long, Candidate>>()
        for (c in candidates) {
            val s = seen[c.key]
            if (s == null || s.instance != c.instance) fresh += c
            else if (now - s.tick >= intervalTicks) due += s.tick to c
        }
        val out = ArrayList<Candidate>()
        for (c in fresh) { if (out.size >= budget) break; out += c }
        for ((_, c) in due.sortedBy { it.first }) { if (out.size >= budget) break; out += c }
        for (c in out) { val s = seen[c.key]; if (s != null && s.instance == c.instance) s.tick = now else seen[c.key] = Seen(c.instance, now) }
        return out
    }

    /** The owner answered with a fix: check that chunk again soon. */
    fun recheck(key: Long) { seen.remove(key) }
}
