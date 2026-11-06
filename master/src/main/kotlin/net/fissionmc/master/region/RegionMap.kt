package net.fissionmc.master.region

import net.fissionmc.protocol.RegionRule
import java.util.Properties

/**
 * Which backend owns which part of the world: per dimension a list of half open block rectangles that must tile
 * the plane (every point exactly one owner). Config (`run-master/fission.properties`):
 *
 *     regions.overworld=min,min,0,max:backend-a;0,min,max,max:backend-b     # x1,z1,x2,z2:backend ; ... ; `min`/`max` = no limit
 *     regions.the_nether=*:backend-a                                         # `*` = everything
 *     regions.default=backend-a                                              # dimensions without a rule (the end, custom ones)
 *
 * No `regions.*` key at all = regions off (every backend owns everything, as before).
 */
class RegionMap(rulesByDimension: Map<String, List<RegionRule>>, private val defaultOwner: String? = null) {
    private val rules: Map<String, List<RegionRule>> = rulesByDimension
    val enabled: Boolean get() = rules.isNotEmpty() || defaultOwner != null

    /** The owner of block ([x], [z]) in [dimension] (null when regions are off or the dimension has no rule and no default). */
    fun owner(dimension: String, x: Int, z: Int): String? {
        rules[dimension]?.let { rs -> return rs.firstOrNull { x >= it.x1 && x < it.x2 && z >= it.z1 && z < it.z2 }?.backend }
        return defaultOwner
    }

    /**
     * Owner with hysteresis for a player currently handled by [current]: the player changes owner only when the point and the four points [margin] blocks
     * around it belong to the new owner, so a player walking along the border does not flap between two backends.
     */
    fun ownerWithHysteresis(dimension: String, x: Int, z: Int, current: String?, margin: Int): String? {
        val here = owner(dimension, x, z) ?: return current
        if (current == null || here == current) return here
        val ring = listOf(x + margin to z, x - margin to z, x to z + margin, x to z - margin)
        return if (ring.all { (px, pz) -> owner(dimension, px, pz) == here }) here else current
    }

    /**
     * Who generates chunk ([cx],[cz]): its owner, except in the border strip (chunks within [strip] chunks of a border): there the lower backend id of the owners in the
     * neighbourhood generates, so one backend writes both sides of a border (features of a chunk reach into its neighbours). Null = regions off.
     */
    fun generationOwner(dimension: String, cx: Int, cz: Int, strip: Int): String? {
        if (!enabled) return null
        val own = owner(dimension, cx * 16 + 8, cz * 16 + 8) ?: return null
        if (strip <= 0) return own
        var best = own
        for (dx in -strip..strip) for (dz in -strip..strip) { val o = owner(dimension, (cx + dx) * 16 + 8, (cz + dz) * 16 + 8) ?: continue; if (o < best) best = o }
        return best
    }

    /** The rules for the backends; `regions.default` travels as a rule for the dimension "*" (every dimension without rules of its own). */
    fun toRules(): List<RegionRule> = rules.values.flatten() + listOfNotNull(defaultOwner?.let { RegionRule("*", Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, it) })
    fun describe(): List<String> = buildList {
        rules.forEach { (d, rs) -> rs.forEach { r -> add("$d  ${lim(r.x1)},${lim(r.z1)} .. ${lim(r.x2)},${lim(r.z2)}  -> ${r.backend}") } }
        defaultOwner?.let { add("(all other dimensions) -> $it") }
    }
    private fun lim(v: Int) = when (v) { Int.MIN_VALUE -> "min"; Int.MAX_VALUE -> "max"; else -> v.toString() }

    /** Problems of the map: overlapping rectangles and uncovered areas (coordinate compression: every cell between the boundaries is tested once). Empty = valid. */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        for ((d, rs) in rules) {
            val xs = (rs.flatMap { listOf(it.x1, it.x2) } + Int.MIN_VALUE + Int.MAX_VALUE).distinct().sorted()
            val zs = (rs.flatMap { listOf(it.z1, it.z2) } + Int.MIN_VALUE + Int.MAX_VALUE).distinct().sorted()
            for (i in 0 until xs.size - 1) for (j in 0 until zs.size - 1) {
                val cx = xs[i]; val cz = zs[j]   // the cell [xs[i], xs[i+1]) x [zs[j], zs[j+1]) is tested at its lower corner
                val covering = rs.filter { cx >= it.x1 && cx < it.x2 && cz >= it.z1 && cz < it.z2 }
                if (covering.size > 1) problems += "$d: ${covering.joinToString(" and ") { it.backend }} overlap at x=${lim(cx)}, z=${lim(cz)}"
                if (covering.isEmpty()) problems += "$d: nobody owns x=${lim(cx)}..${lim(xs[i + 1])}, z=${lim(cz)}..${lim(zs[j + 1])}"
            }
        }
        return problems.distinct()
    }

    companion object {
        val OFF = RegionMap(emptyMap())
        private val aliases = mapOf("overworld" to "minecraft:overworld", "the_nether" to "minecraft:the_nether", "nether" to "minecraft:the_nether", "the_end" to "minecraft:the_end", "end" to "minecraft:the_end")

        private fun bound(s: String, lo: Boolean): Int = when (s.trim().lowercase()) { "min" -> Int.MIN_VALUE; "max" -> Int.MAX_VALUE; else -> s.trim().toInt() }

        /** Parses the `regions.*` keys. Throws IllegalArgumentException (with the bad value) for a malformed rule or an invalid map: the master must not start with a broken map. */
        fun parse(p: Properties): RegionMap {
            val byDim = LinkedHashMap<String, List<RegionRule>>()
            var default: String? = null
            for (key in p.stringPropertyNames().sorted()) {
                if (!key.startsWith("regions.")) continue
                val name = key.removePrefix("regions."); val value = p.getProperty(key).trim()
                if (name == "default") { default = value.takeIf { it.isNotEmpty() }; continue }
                val dim = aliases[name] ?: if (':' in name) name else "minecraft:$name"
                byDim[dim] = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { rule ->
                    val i = rule.lastIndexOf(':'); require(i > 0) { "$key: '$rule' must be x1,z1,x2,z2:backend or *:backend" }
                    val backend = rule.substring(i + 1).trim(); val area = rule.substring(0, i).trim()
                    if (area == "*") RegionRule(dim, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, backend)
                    else { val n = area.split(','); require(n.size == 4) { "$key: '$rule' needs four numbers" }; RegionRule(dim, bound(n[0], true), bound(n[1], true), bound(n[2], false), bound(n[3], false), backend) }
                }
            }
            val map = RegionMap(byDim, default)
            val problems = map.validate()
            require(problems.isEmpty()) { "invalid regions: " + problems.joinToString("; ") }
            return map
        }
    }
}
