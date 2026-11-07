package net.fissionmc.master.seamless

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts

/**
 * The master's knowledge of where the client IS: parsed (cheaply, by packet id) from the serverbound movement packets that pass through the relay (
 * "position hand-off"). A backend switch takes 0.1-2 s during which the client keeps moving on the old backend; the new backend starts from the player data saved when the switch
 * began, so without this the player is snapped backwards by speed x switch duration. Also keeps a velocity estimate (blocks per tick) for the Login+Respawn fallback, whose
 * PlayerPosition carries the momentum.
 */
class PositionTracker(private val clockNanos: () -> Long = System::nanoTime) {
    private val pPos = PlayPacketIds.s("MovePlayerPos"); private val pPosRot = PlayPacketIds.s("MovePlayerPosRot"); private val pRot = PlayPacketIds.s("MovePlayerRot")
    private val pVehicle = PlayPacketIds.s("MoveVehicle")

    class Pos(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val vx: Double, val vy: Double, val vz: Double)

    @Volatile var known = false; private set
    /** The client sent a position packet since the last server teleport: only then can it be anywhere but where the backend last put it. */
    @Volatile var moved = false; private set
    private var x = 0.0; private var y = 0.0; private var z = 0.0; private var yaw = 0f; private var pitch = 0f
    private var vx = 0.0; private var vy = 0.0; private var vz = 0.0
    private var lastNanos = 0L

    /** Feed every serverbound play frame (id + payload). Returns true when it was a movement packet. */
    fun observe(frame: ByteArray): Boolean {
        val b = Unpooled.wrappedBuffer(frame); val pid = VarInts.tryRead(b) ?: return false
        try {
            when (pid) {
                pPos -> position(b.readDouble(), b.readDouble(), b.readDouble())
                pPosRot -> { position(b.readDouble(), b.readDouble(), b.readDouble()); yaw = b.readFloat(); pitch = b.readFloat() }
                pRot -> { yaw = b.readFloat(); pitch = b.readFloat() }
                pVehicle -> { position(b.readDouble(), b.readDouble(), b.readDouble()); yaw = b.readFloat(); pitch = b.readFloat() }
                else -> return false
            }
        } catch (_: Throwable) { return false }
        return true
    }

    private fun position(nx: Double, ny: Double, nz: Double) {
        val now = clockNanos()
        if (known) {
            val ticks = Math.max(1.0, Math.round((now - lastNanos) / 50_000_000.0).toDouble())
            val dx = (nx - x) / ticks; val dy = (ny - y) / ticks; val dz = (nz - z) / ticks
            // a teleport (a jump of more than 16 blocks per tick) is not momentum
            if (dx * dx + dy * dy + dz * dz < 256.0) { vx = dx; vy = dy; vz = dz } else { vx = 0.0; vy = 0.0; vz = 0.0 }
        }
        x = nx; y = ny; z = nz; lastNanos = now; known = true; moved = true
    }

    /** The latest position, extrapolated by [extraTicks] ticks of the estimated velocity (the time the hand-off needs to reach the client), or null before the first movement packet. */
    fun latest(extraTicks: Double = 0.0): Pos? = if (!known) null else Pos(x + vx * extraTicks, y + vy * extraTicks, z + vz * extraTicks, yaw, pitch, vx, vy, vz)

    /**
     * A server moved the client (ClientboundPlayerPosition: spawn, /tp, a switch): from now on that is where it is, until its next movement packet (a standing client sends none).
     * [rel] is the packet's relative-flags int (bit 0-2 x y z, 3-4 yaw pitch, the rest is momentum and is ignored here).
     */
    fun teleported(nx: Double, ny: Double, nz: Double, nyaw: Float, npitch: Float, rel: Int = 0) {
        x = if (rel and 1 != 0) x + nx else nx; y = if (rel and 2 != 0) y + ny else ny; z = if (rel and 4 != 0) z + nz else nz
        yaw = if (rel and 8 != 0) yaw + nyaw else nyaw; pitch = if (rel and 16 != 0) pitch + npitch else npitch
        vx = 0.0; vy = 0.0; vz = 0.0; lastNanos = clockNanos(); known = true; moved = false
    }

    /** The player was teleported by a server (spawn, respawn, a command): movement history before is no momentum. */
    fun clearVelocity() { vx = 0.0; vy = 0.0; vz = 0.0 }
}
