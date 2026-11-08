package net.fissionmc.master.seamless

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts
import kotlin.test.*

class ClientInputStateTest {
    private val st = ClientInputState()
    private fun s(name: String, f: io.netty.buffer.ByteBuf.() -> Unit): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, PlayPacketIds.s(name)); b.f(); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
    private fun command(entity: Int, action: Int) = s("PlayerCommand") { VarInts.write(this, entity); VarInts.write(this, action); VarInts.write(this, 0) }

    @Test fun `nothing is replayed before the client sent anything`() { assertTrue(st.replay(5).isEmpty()) }

    @Test fun `sprint input slot and flying are replayed with the new backends own entity id`() {
        st.observe(s("SetCarriedItem") { writeShort(4) }); st.observe(s("PlayerAbilities") { writeByte(2) })
        st.observe(s("PlayerInput") { writeByte(0x41) }); st.observe(command(7, ClientInputState.START_SPRINTING))
        val frames = st.replay(900)
        assertEquals(4, frames.size)
        val kinds = frames.map { f -> Unpooled.wrappedBuffer(f).let { VarInts.tryRead(it) } }
        assertEquals(listOf("SetCarriedItem", "PlayerAbilities", "PlayerInput", "PlayerCommand").map { PlayPacketIds.s(it) }, kinds)
        val cmd = Unpooled.wrappedBuffer(frames[3]); VarInts.tryRead(cmd)
        assertEquals(900, VarInts.tryRead(cmd)); assertEquals(ClientInputState.START_SPRINTING, VarInts.tryRead(cmd))
        val inp = Unpooled.wrappedBuffer(frames[2]); VarInts.tryRead(inp); assertEquals(0x41, inp.readByte().toInt())
        val ab = Unpooled.wrappedBuffer(frames[1]); VarInts.tryRead(ab); assertEquals(2, ab.readByte().toInt())
    }

    @Test fun `stopping the sprint ends the replay of it`() {
        st.observe(command(7, ClientInputState.START_SPRINTING)); st.observe(command(7, ClientInputState.STOP_SPRINTING))
        assertTrue(st.replay(1).isEmpty())
    }

    @Test fun `a glide is replayed only while the client is in the air`() {
        st.observe(command(7, ClientInputState.START_FALL_FLYING)); assertTrue(st.fallFlying)
        st.observe(s("MovePlayerPos") { writeDouble(1.0); writeDouble(2.0); writeDouble(3.0); writeByte(0) }); assertTrue(st.fallFlying)
        st.observe(s("MovePlayerPos") { writeDouble(1.0); writeDouble(2.0); writeDouble(3.0); writeByte(1) }); assertFalse(st.fallFlying)
        assertTrue(st.replay(1).isEmpty())
    }

    @Test fun `unrelated and truncated frames are ignored`() {
        st.observe(s("PlayerInput") { }); st.observe(byteArrayOf()); st.observe(s("ChatCommand") { writeByte(1) })
        assertEquals(-1, st.input)
    }
}
