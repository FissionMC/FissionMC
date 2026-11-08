package net.fissionmc.master.region

/**
 * When a player standing in one backend's region is moved to another's. On top of [RegionMap.ownerWithHysteresis] (the player must be [margin] blocks inside the new
 * region) this adds a cooldown after every switch (the new backend settles, its first position reports are not trusted to decide again) and a ping-pong guard: going straight
 * back to the backend the player just left within [pingPongNanos] needs twice the margin. One instance per player session.
 */
class RegionSwitchPolicy(private val margin: Int, private val cooldownNanos: Long = 3_000_000_000L, private val pingPongNanos: Long = 30_000_000_000L) {
    private var lastSwitch = Long.MIN_VALUE; private var lastFrom: String? = null

    /** The backend the player should move to, or null to stay on [current]. */
    fun decide(map: RegionMap, dimension: String, x: Int, z: Int, current: String, nowNanos: Long): String? {
        if (lastSwitch != Long.MIN_VALUE && nowNanos - lastSwitch < cooldownNanos) return null
        var target = map.ownerWithHysteresis(dimension, x, z, current, margin) ?: return null
        if (target == current) return null
        if (target == lastFrom && lastSwitch != Long.MIN_VALUE && nowNanos - lastSwitch < pingPongNanos) {
            target = map.ownerWithHysteresis(dimension, x, z, current, Math.min(margin * 2, 128)) ?: return null
            if (target == current) return null
        }
        return target
    }

    /** A switch from [from] to another backend was started at [nowNanos]. */
    fun switched(from: String, nowNanos: Long) { lastFrom = from; lastSwitch = nowNanos }
}
