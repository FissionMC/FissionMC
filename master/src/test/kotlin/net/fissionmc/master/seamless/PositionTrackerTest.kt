package net.fissionmc.master.seamless

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts
import net.fissionmc.protocol.registerPositionMessages
import kotlin.test.*

class PositionTrackerTest {
    private var now = 0L
    private val t = PositionTracker { now }
    private fun s(name: String, f: io.netty.buffer.ByteBuf.() -> Unit): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, PlayPacketIds.s(name)); b.f(); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }

    @Test fun `nothing is known before the first movement packet and status only packets change nothing`() {
        assertNull(t.latest()); assertFalse(t.observe(s("MovePlayerStatusOnly") { writeByte(1) })); assertNull(t.latest())
    }

    @Test fun `position and rotation come from the movement packets and velocity from their spacing`() {
        now = 0; assertTrue(t.observe(s("MovePlayerPosRot") { writeDouble(10.0); writeDouble(64.0); writeDouble(-3.0); writeFloat(90f); writeFloat(10f); writeByte(0) }))
        now = 50_000_000; assertTrue(t.observe(s("MovePlayerPos") { writeDouble(10.28); writeDouble(64.0); writeDouble(-3.0); writeByte(0) }))
        val p = t.latest()!!; assertEquals(10.28, p.x, 1e-9); assertEquals(90f, p.yaw); assertEquals(0.28, p.vx, 1e-9)
        val ahead = t.latest(2.0)!!; assertEquals(10.28 + 0.56, ahead.x, 1e-9)
        now = 150_000_000; t.observe(s("MovePlayerPos") { writeDouble(10.84); writeDouble(64.0); writeDouble(-3.0); writeByte(0) })   // two ticks later: 0.28 per tick again
        assertEquals(0.28, t.latest()!!.vx, 1e-9)
        t.observe(s("MovePlayerRot") { writeFloat(180f); writeFloat(0f); writeByte(0) }); assertEquals(180f, t.latest()!!.yaw); assertEquals(10.84, t.latest()!!.x, 1e-9)
    }

    @Test fun `a teleport sized jump is not momentum and the vehicle packet counts`() {
        now = 0; t.observe(s("MovePlayerPos") { writeDouble(0.0); writeDouble(0.0); writeDouble(0.0); writeByte(0) })
        now = 50_000_000; t.observe(s("MovePlayerPos") { writeDouble(500.0); writeDouble(0.0); writeDouble(0.0); writeByte(0) }); assertEquals(0.0, t.latest()!!.vx)
        now = 100_000_000; t.observe(s("MoveVehicle") { writeDouble(501.0); writeDouble(0.0); writeDouble(0.0); writeFloat(0f); writeFloat(0f); writeByte(0) }); assertEquals(501.0, t.latest()!!.x)
    }

    @Test fun `a server teleport moves the known position and clears the momentum`() {
        now = 0; t.observe(s("MovePlayerPos") { writeDouble(60.0); writeDouble(100.0); writeDouble(4.5); writeByte(0) })
        now = 50_000_000; t.observe(s("MovePlayerPos") { writeDouble(60.28); writeDouble(100.0); writeDouble(4.5); writeByte(0) })
        val b = Unpooled.buffer(); VarInts.write(b, PositionFrames.playerPositionId); VarInts.write(b, 3)
        b.writeDouble(-40.0); b.writeDouble(100.0); b.writeDouble(4.5); repeat(3) { b.writeDouble(0.0) }; b.writeFloat(0f); b.writeFloat(0f); b.writeInt(0)
        PositionFrames.observeTeleport(ByteArray(b.readableBytes()).also { b.readBytes(it) }, t)
        val p = t.latest()!!; assertEquals(-40.0, p.x, 1e-9); assertEquals(0.0, p.vx)
        val r = Unpooled.buffer(); VarInts.write(r, PositionFrames.playerPositionId); VarInts.write(r, 4)   // relative x: +2
        r.writeDouble(2.0); r.writeDouble(0.0); r.writeDouble(0.0); repeat(3) { r.writeDouble(0.0) }; r.writeFloat(0f); r.writeFloat(0f); r.writeInt(1)
        PositionFrames.observeTeleport(ByteArray(r.readableBytes()).also { r.readBytes(it) }, t); assertEquals(-38.0, t.latest()!!.x, 1e-9)
    }

    @Test fun `a PlayerPosition frame is rewritten to the clients real position with its momentum`() {
        val b = Unpooled.buffer(); VarInts.write(b, PositionFrames.playerPositionId); VarInts.write(b, 7)
        repeat(3) { b.writeDouble(1.0) }; repeat(3) { b.writeDouble(0.0) }; b.writeFloat(5f); b.writeFloat(6f); b.writeInt(0); b.writeBytes(byteArrayOf(9, 9))
        val frame = ByteArray(b.readableBytes()).also { b.readBytes(it) }
        val out = PositionFrames.patchPlayerPosition(frame, PositionTracker.Pos(100.5, 70.0, -20.25, 123f, -4f, 0.28, 0.0, 0.1))
        val r = Unpooled.wrappedBuffer(out); assertEquals(PositionFrames.playerPositionId, VarInts.tryRead(r)); assertEquals(7, VarInts.tryRead(r))
        assertEquals(listOf(100.5, 70.0, -20.25, 0.28, 0.0, 0.1), List(6) { r.readDouble() }); assertEquals(123f, r.readFloat()); assertEquals(-4f, r.readFloat()); assertEquals(0, r.readInt()); assertEquals(2, r.readableBytes())
        assertEquals(7, PositionFrames.teleportId(out))
        // relative flags: nothing absolute to replace
        val rel = frame.copyOf(); rel[rel.size - 3] = 1.toByte()
        assertContentEquals(rel, PositionFrames.patchPlayerPosition(rel, PositionTracker.Pos(0.0, 0.0, 0.0, 0f, 0f, 0.0, 0.0, 0.0)))
    }

    @Test fun `the teleport confirmation carries the position the server applies`() {
        val f = PositionFrames.acceptTeleportation(300, 1.5, 2.5, -3.5, 45f, 10f)
        val r = Unpooled.wrappedBuffer(f); assertEquals(PlayPacketIds.s("AcceptTeleportation"), VarInts.tryRead(r)); assertEquals(300, VarInts.tryRead(r))
        assertEquals(listOf(1.5, 2.5, -3.5), List(3) { r.readDouble() }); assertEquals(45f, r.readFloat()); assertEquals(10f, r.readFloat()); assertEquals(0, r.readableBytes())
    }

    @Test fun `PlayerMoveTo survives the wire`() {
        val reg = net.fissionmc.protocol.MessageRegistry.core().registerPositionMessages()
        val m = net.fissionmc.protocol.PlayerMoveTo(1, 2, 3.5, 64.0, -9.25, 90f, 5f)
        val b = Unpooled.buffer(); reg.encode(m, b); assertEquals(m, reg.decode(b)); assertEquals(0, b.readableBytes())
    }
}
