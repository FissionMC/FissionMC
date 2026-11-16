package net.fissionmc.master.region

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection

/** `regions.border-particles:` of fission.yml. */
data class BorderParticleSettings(
    val enabled: Boolean = false, val distance: Int = 12, val particle: String = "end_rod", val color: Int = 0x55FFFF, val scale: Float = 1f,
    val spacing: Double = 2.0, val heightBelow: Int = 3, val heightAbove: Int = 5, val intervalTicks: Int = 20, val maxPerRefresh: Int = 160, val respectClientSetting: Boolean = true,
) {
    companion object {
        val PARTICLES = setOf("end_rod", "glow", "electric_spark", "dust")

        fun from(root: ConfigSection): BorderParticleSettings {
            val d = BorderParticleSettings(); val s = root.section("regions").section("border-particles")
            fun range(key: String, v: Int, r: IntRange) = v.also { if (it !in r) throw ConfigException("regions.border-particles.$key: expected ${r.first}-${r.last}, got $it") }
            val particle = s.string("particle", d.particle).lowercase().also { if (it !in PARTICLES) throw ConfigException("regions.border-particles.particle: expected one of ${PARTICLES.joinToString(", ")}, got '$it'") }
            val color = s.string("color", "#55ffff").trim().removePrefix("#").let { c -> c.toIntOrNull(16)?.takeIf { c.length == 6 } ?: throw ConfigException("regions.border-particles.color: expected a hex colour like #55ffff, got '#$c'") }
            val spacing = s.string("spacing", d.spacing.toString()).toDoubleOrNull()?.also { if (it < 0.5 || it > 16.0) throw ConfigException("regions.border-particles.spacing: expected 0.5-16, got $it") } ?: throw ConfigException("regions.border-particles.spacing: expected a number")
            val scale = s.string("scale", d.scale.toString()).toFloatOrNull()?.also { if (it < 0.1f || it > 4f) throw ConfigException("regions.border-particles.scale: expected 0.1-4, got $it") } ?: throw ConfigException("regions.border-particles.scale: expected a number")
            return BorderParticleSettings(
                s.bool("enabled", d.enabled), range("distance", s.int("distance", d.distance), 2..64), particle, color, scale, spacing,
                range("height-below", s.int("height-below", d.heightBelow), 0..32), range("height-above", s.int("height-above", d.heightAbove), 0..32),
                range("interval-ticks", s.int("interval-ticks", d.intervalTicks), 2..200), range("max-per-refresh", s.int("max-per-refresh", d.maxPerRefresh), 1..2000), s.bool("respect-client-setting", d.respectClientSetting),
            )
        }
    }
}

/** Samples particle positions of the region borders around a player. */
object BorderParticles {
    /**
     * @param edges the border segments of the player's dimension
     * @param x player x
     * @param y player y
     * @param z player z
     * @return x/y/z triples lying on border lines, nearest first, at most maxPerRefresh
     */
    fun sample(edges: List<RegionMap.Edge>, x: Double, y: Double, z: Double, s: BorderParticleSettings): List<DoubleArray> {
        if (edges.isEmpty()) return emptyList()
        val out = ArrayList<DoubleArray>()
        val dist = s.distance.toDouble()
        val y0 = Math.floor(y) - s.heightBelow; val y1 = Math.floor(y) + s.heightAbove
        val ys = lattice(y0, y1, s.spacing)
        for (e in edges) {
            val across = if (e.vertical) x else z; val along = if (e.vertical) z else x
            val d = Math.abs(across - e.line)
            if (d > dist) continue
            val h = Math.sqrt(dist * dist - d * d)
            val lo = Math.max(e.from.toDouble(), along - h); val hi = Math.min(e.to.toDouble(), along + h)
            if (lo > hi) continue
            val points = lattice(lo, hi, s.spacing).toMutableSet()
            if (e.from.toDouble() >= lo) points += e.from.toDouble()
            if (e.to.toDouble() <= hi) points += e.to.toDouble()
            for (a in points) for (py in ys) out += if (e.vertical) doubleArrayOf(e.line.toDouble(), py, a) else doubleArrayOf(a, py, e.line.toDouble())
        }
        val unique = out.distinctBy { Triple(it[0], it[1], it[2]) }
        return unique.sortedBy { (it[0] - x) * (it[0] - x) + (it[1] - y) * (it[1] - y) + (it[2] - z) * (it[2] - z) }.take(s.maxPerRefresh)
    }

    /**
     * @param p one position from sample
     * @return a ready to write clientbound level particles frame (id and payload)
     */
    fun frame(p: DoubleArray, s: BorderParticleSettings): ByteArray {
        val particle = when (s.particle) {
            "glow" -> com.github.retrooper.packetevents.protocol.particle.Particle(com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes.GLOW)
            "electric_spark" -> com.github.retrooper.packetevents.protocol.particle.Particle(com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes.ELECTRIC_SPARK)
            "dust" -> com.github.retrooper.packetevents.protocol.particle.Particle(com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes.DUST,
                com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData(s.scale, ((s.color shr 16) and 0xFF) / 255f, ((s.color shr 8) and 0xFF) / 255f, (s.color and 0xFF) / 255f))
            else -> com.github.retrooper.packetevents.protocol.particle.Particle(com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes.END_ROD)
        }
        val zero = com.github.retrooper.packetevents.util.Vector3f(0f, 0f, 0f)
        return net.fissionmc.master.client.Limbo.encode(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle(
            particle, true, com.github.retrooper.packetevents.util.Vector3d(p[0], p[1], p[2]), zero, zero, 0, true,
            com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle.RandomizationType.DEFAULT))
    }

    /**
     * @param payload a ServerboundClientInformation payload without its packet id
     * @return the particle setting (0 all, 1 decreased, 2 minimal), or null when it cannot be read
     */
    fun particleStatus(payload: ByteArray): Int? {
        var i = 0
        fun varInt(): Int? { var v = 0; var shift = 0; while (i < payload.size && shift < 35) { val b = payload[i++].toInt(); v = v or ((b and 0x7F) shl shift); if (b and 0x80 == 0) return v; shift += 7 }; return null }
        val len = varInt() ?: return null
        i += len
        i += 1
        varInt() ?: return null
        i += 1
        i += 1
        varInt() ?: return null
        i += 2
        if (i > payload.size) return null
        return varInt()
    }

    private fun lattice(lo: Double, hi: Double, step: Double): List<Double> {
        val first = Math.ceil(lo / step) * step
        val out = ArrayList<Double>()
        var v = first
        while (v <= hi && out.size < 4096) { out += v; v += step }
        return out
    }
}
