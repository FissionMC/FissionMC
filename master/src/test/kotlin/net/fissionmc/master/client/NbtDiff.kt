package net.fissionmc.master.client

/** SNBT (the text `/data get` prints) to a tree of maps, lists and strings, and a flat path -> value view for diffing entity data before and after a move. */
class Snbt(private val s: String) {
    private var i = 0
    fun parse(): Any? { ws(); return value() }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? { ws(); return when (s[i]) { '{' -> compound(); '[' -> list(); '"', '\'' -> quoted(); else -> bare() } }
    private fun compound(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(); i++; ws()
        while (s[i] != '}') {
            ws(); val k = if (s[i] == '"' || s[i] == '\'') quoted() else { val st = i; while (s[i] != ':') i++; s.substring(st, i).trim() }
            ws(); require(s[i] == ':') { "expected : at $i" }; i++
            m[k] = value(); ws(); if (s[i] == ',') i++; ws()
        }
        i++; return m
    }
    private fun list(): List<Any?> {
        val l = ArrayList<Any?>(); i++; ws()
        if (i + 1 < s.length && s[i] in "IBL" && s[i + 1] == ';') { val tag = s[i]; i += 2; l += "array:$tag" }
        while (s[i] != ']') { l += value(); ws(); if (s[i] == ',') i++; ws() }
        i++; return l
    }
    private fun quoted(): String {
        val q = s[i++]; val sb = StringBuilder()
        while (s[i] != q) { if (s[i] == '\\') i++; sb.append(s[i++]) }
        i++; return sb.toString()
    }
    private fun bare(): String { val st = i; while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++; return s.substring(st, i).trim() }

    companion object {
        fun flatten(v: Any?, path: String = "", out: MutableMap<String, String> = LinkedHashMap()): Map<String, String> {
            when (v) {
                is Map<*, *> -> { if (v.isEmpty() && path.isNotEmpty()) out[path] = "{}"; v.forEach { (k, x) -> flatten(x, if (path.isEmpty()) k.toString() else "$path.$k", out) } }
                is List<*> -> {
                    if (v.isEmpty()) out[path] = "[]"
                    // lists of compounds with an id (attributes, active_effects): the set is what matters, the order is not (it differs after a load)
                    val byId = v.isNotEmpty() && v.all { it is Map<*, *> && it["id"] is String }
                    v.forEachIndexed { n, x -> flatten(x, if (byId) "$path[${(x as Map<*, *>)["id"]}]" else "$path[$n]", out) }
                }
                else -> out[path] = v.toString()
            }
            return out
        }

        /** The entity SNBT of a `/data get entity` console line (everything after "entity data: "), flattened. */
        fun fromConsole(out: String): Map<String, String>? {
            val marker = "entity data: "; val at = out.lastIndexOf(marker); if (at < 0) return null
            val body = out.substring(at + marker.length).lines().first().trim()
            return try { flatten(Snbt(body).parse()) } catch (t: Throwable) { null }
        }
    }
}

/** Result of comparing the entity data before and after a move. */
class NbtDelta(val changed: List<String>, val missing: List<String>, val added: List<String>, val allowedChanged: List<String>) {
    val clean: Boolean get() = changed.isEmpty() && missing.isEmpty()
    fun describe(max: Int = 12) = "changed ${changed.take(max)}${if (changed.size > max) "..." else ""}, missing on target ${missing.take(max)}, added on target ${added.take(max)} (allowed changes: ${allowedChanged.size})"
}

object NbtDiff {
    /**
     * Keys that may legitimately differ after a move (the documented allowlist): where the entity stands and moves
     * (it is placed next to the owner), transient physics and combat counters, and Paper's spawn bookkeeping. `UUID` and everything else must be identical.
     */
    val allowed = listOf(
        // the leaf names below also match inside nested passengers (`Passengers[minecraft:pig].Rotation[0]`)
        Regex("(^|.*\\.)Pos(\\[.*)?$"), Regex("(^|.*\\.)Motion(\\[.*)?$"), Regex("(^|.*\\.)Rotation(\\[.*)?$"), Regex("(^|.*\\.)(FallDistance|fall_distance)$"),
        Regex("(^|.*\\.)OnGround$"), Regex("(^|.*\\.)Air$"), Regex("(^|.*\\.)(Fire|fire)$"), Regex("(^|.*\\.)(PortalCooldown|portal_cooldown)$"),
        Regex("(^|.*\\.)(HurtTime|HurtByTimestamp|hurt_time|hurt_by_timestamp|DeathTime)$"), Regex("(^|.*\\.)Paper\\..*$"), Regex("(^|.*\\.)Spigot\\..*$"),
        Regex("(^|.*\\.)(WorldUUIDMost|WorldUUIDLeast|Bukkit\\.updateLevel)$"),
    )
    /** Counters that run with the game clock (potion duration, a baby's age, ticks lived): they may differ by the ticks that passed between the two dumps (at most [TIME_TOLERANCE] ticks), not by anything else. */
    val timeDriven = listOf(Regex("^active_effects\\[.*\\]\\.duration$"), Regex("^(Age|ForcedAge|InLove)$"), Regex("(^|.*\\.)Spigot\\.ticksLived$"), Regex("(^|.*\\.)(Age|ForcedAge|InLove)$"), Regex("^(ticks_lived|TicksLived)$"))
    const val TIME_TOLERANCE = 1200L
    private fun num(v: String) = v.trimEnd('b', 'B', 's', 'S', 'l', 'L', 'f', 'F', 'd', 'D').toDoubleOrNull()
    private fun timeDrift(k: String, a: String, b: String): Boolean {
        if (timeDriven.none { it.matches(k) }) return false
        val x = num(a); val y = num(b); return x != null && y != null && Math.abs(x - y) <= TIME_TOLERANCE
    }

    fun diff(before: Map<String, String>, after: Map<String, String>): NbtDelta {
        val changed = ArrayList<String>(); val allowedChanged = ArrayList<String>()
        for ((k, v) in before) {
            val a = after[k] ?: continue
            if (a != v) { if (allowed.any { it.matches(k) } || timeDrift(k, v, a)) allowedChanged += k else changed += "$k: $v -> $a" }
        }
        val missing = before.keys.filter { it !in after && !allowed.any { r -> r.matches(it) } }
        val added = after.keys.filter { it !in before && !allowed.any { r -> r.matches(it) } }
        return NbtDelta(changed, missing, added, allowedChanged)
    }
}
