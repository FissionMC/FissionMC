package net.fissionmc.master.seamless

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts

/** Byte level helpers for the position packets of the hand-off (layouts from the decompiled 26.3 codecs). */
object PositionFrames {
    val playerPositionId: Int get() = PlayPacketIds.c("PlayerPosition")

    /** `ServerboundAcceptTeleportation`: VarInt id, x y z (doubles), yRot xRot (floats). The 26.3 server applies the client's position from it. */
    fun acceptTeleportation(teleportId: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): ByteArray {
        val b = Unpooled.buffer(); VarInts.write(b, PlayPacketIds.s("AcceptTeleportation")); VarInts.write(b, teleportId)
        b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeFloat(yaw); b.writeFloat(pitch)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }

    /** The teleport id of a `ClientboundPlayerPosition` frame (VarInt id first), or null. */
    fun teleportId(frame: ByteArray): Int? { val b = Unpooled.wrappedBuffer(frame); VarInts.tryRead(b); return VarInts.tryRead(b) }

    /** Feeds a `ClientboundPlayerPosition` frame to [t]: the client was teleported there. */
    fun observeTeleport(frame: ByteArray, t: PositionTracker) {
        val b = Unpooled.wrappedBuffer(frame); VarInts.tryRead(b) ?: return; VarInts.tryRead(b) ?: return
        if (b.readableBytes() < 24 + 24 + 8 + 4) return
        val x = b.readDouble(); val y = b.readDouble(); val z = b.readDouble(); b.skipBytes(24); val yaw = b.readFloat(); val pitch = b.readFloat(); val rel = b.readInt()
        t.teleported(x, y, z, yaw, pitch, rel)
    }

    /**
     * Rewrites a `ClientboundPlayerPosition` frame (VarInt teleport id, x y z, delta x y z (doubles), yRot xRot (floats), int relative flags) to put the player at [pos] with its
     * momentum; unchanged when the packet uses relative flags (nothing absolute to replace).
     */
    fun patchPlayerPosition(frame: ByteArray, pos: PositionTracker.Pos): ByteArray {
        val b = Unpooled.wrappedBuffer(frame); val pid = VarInts.tryRead(b) ?: return frame; val tp = VarInts.tryRead(b) ?: return frame
        if (b.readableBytes() < 24 + 24 + 8 + 4) return frame
        val rel = b.getInt(b.readerIndex() + 24 + 24 + 8)
        if (rel != 0) return frame
        val o = Unpooled.buffer(frame.size); VarInts.write(o, pid); VarInts.write(o, tp)
        o.writeDouble(pos.x); o.writeDouble(pos.y); o.writeDouble(pos.z); o.writeDouble(pos.vx); o.writeDouble(pos.vy); o.writeDouble(pos.vz)
        b.skipBytes(48); b.readFloat(); b.readFloat(); o.writeFloat(pos.yaw); o.writeFloat(pos.pitch); o.writeInt(b.readInt()); o.writeBytes(b)
        return ByteArray(o.readableBytes()).also { o.readBytes(it) }
    }
}
