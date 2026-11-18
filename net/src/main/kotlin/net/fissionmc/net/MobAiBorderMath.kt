package net.fissionmc.net

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Pure helpers of the mob AI border (regions.mob-ai-border): the decisions that need no Minecraft classes, so they can be unit tested. */
object MobAiBorderMath {
    /** A destination is subject to the border rule when it lies on the other side than the mob itself (both flags: "owned by this backend"). */
    @JvmStatic fun restricted(destMine: Boolean, mobMine: Boolean): Boolean = destMine != mobMine

    /** How far one hop of a cube mob (slime, magma cube, sulfur cube) of [size] carries it, in blocks (a rough upper estimate: 2 for size 1, up to 6). */
    @JvmStatic fun hopDistance(size: Int): Double = (2.0 + 0.5 * (size - 1).coerceAtLeast(0)).coerceAtMost(6.0)

    /** Landing x of a hop of [dist] blocks from [x] along the Minecraft yaw [yawDeg] (yaw 0 = +z, 90 = -x). */
    @JvmStatic fun landingX(x: Double, yawDeg: Float, dist: Double): Double = x - sin(yawDeg * PI / 180.0) * dist
    @JvmStatic fun landingZ(z: Double, yawDeg: Float, dist: Double): Double = z + cos(yawDeg * PI / 180.0) * dist

    /** The yaw to hop along: [yaw] itself when its landing block is allowed, else the first of yaw+180, +90, -90 that is, else null (no safe direction: skip the hop). */
    @JvmStatic fun safeYaw(x: Double, z: Double, yaw: Float, dist: Double, allowed: (Int, Int) -> Boolean): Float? {
        for (turn in floatArrayOf(0f, 180f, 90f, -90f)) {
            val y = wrap(yaw + turn)
            if (allowed(floor(landingX(x, y, dist)).toInt(), floor(landingZ(z, y, dist)).toInt())) return y
        }
        return null
    }

    @JvmStatic fun wrap(deg: Float): Float { var d = deg % 360f; if (d >= 180f) d -= 360f; if (d < -180f) d += 360f; return d }

    /** Index of the first of [count] path nodes that [allowed] refuses, or -1 when all pass. */
    @JvmStatic fun firstRefused(count: Int, allowed: (Int) -> Boolean): Int { for (i in 0 until count) if (!allowed(i)) return i; return -1 }

    /**
     * Movement-level guard: the horizontal step ([dx], [dz]) a mob at ([x], [z]) is about to make, with the components zeroed that would take it onto a block [allowed] refuses
     * (both, then x only, then z only, then neither). Returns the step to keep as (dx, dz).
     */
    @JvmStatic fun clampStep(x: Double, z: Double, dx: Double, dz: Double, allowed: (Int, Int) -> Boolean): DoubleArray {
        fun ok(sx: Double, sz: Double) = allowed(floor(x + sx).toInt(), floor(z + sz).toInt())
        if (ok(dx, dz)) return doubleArrayOf(dx, dz)
        if (dx != 0.0 && ok(dx, 0.0)) return doubleArrayOf(dx, 0.0)
        if (dz != 0.0 && ok(0.0, dz)) return doubleArrayOf(0.0, dz)
        return doubleArrayOf(0.0, 0.0)
    }
}
