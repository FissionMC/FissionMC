package net.fissionmc.master.safety

import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.nbt.NBTByteArray
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import net.fissionmc.master.seamless.PlayPacketIds
import net.fissionmc.net.ConfigException
import net.fissionmc.net.FissionConfig
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

/** Crafted oversized packets of every known chunk ban vector through the safety layer (the vanilla decoder oracle for the sanitised output is `-PcheckSafety` in the backend, see client-safety.md). */
class ClientSafetyTest {
    init { FissionPacketEventsAPI.install(Side.MASTER) }
    private val log = ArrayList<String>()
    private fun safety(cfg: SafetyConfig = SafetyConfig()) = ClientSafety({ "Steve" }, { "backend-a" }, { log += it }, PeItemSanitizer, { cfg })
    private fun id(n: String) = PlayPacketIds.c(n)

    private class W {
        val o = ByteArrayOutputStream(); val d = DataOutputStream(o)
        fun vi(v: Int) = apply { o.write(Wire.varIntBytes(v)) }
        fun int(v: Int) = apply { d.writeInt(v) }; fun long(v: Long) = apply { d.writeLong(v) }; fun short(v: Int) = apply { d.writeShort(v) }; fun byte(v: Int) = apply { d.writeByte(v) }
        fun bytes(b: ByteArray) = apply { o.write(b) }
        fun utf(s: String) = apply { val b = s.toByteArray(); vi(b.size); o.write(b) }
        fun done(): ByteArray = o.toByteArray()
    }

    // ---- network NBT
    private fun stringTag(s: String): ByteArray { val o = ByteArrayOutputStream(); val d = DataOutputStream(o); d.writeByte(8); d.writeShort(s.length); d.write(s.toByteArray(Charsets.ISO_8859_1)); return o.toByteArray() }
    private fun byteArrayTag(n: Int): ByteArray { val o = ByteArrayOutputStream(); val d = DataOutputStream(o); d.writeByte(7); d.writeInt(n); d.write(ByteArray(n)); return o.toByteArray() }
    /** {"extra":[{"extra":[ ... {} ]}]}: a chat component nested [depth] levels (2 containers per level) */
    private fun deep(depth: Int): ByteArray {
        val o = ByteArrayOutputStream(); val d = DataOutputStream(o)
        d.writeByte(10)
        repeat(depth) { d.writeByte(9); d.writeShort(5); d.writeBytes("extra"); d.writeByte(10); d.writeInt(1) }
        repeat(depth + 1) { d.writeByte(0) }
        return o.toByteArray()
    }
    /** One compound with one byte entry "k". */
    private fun smallCompound(): ByteArray { val o = ByteArrayOutputStream(); val d = DataOutputStream(o); d.writeByte(10); d.writeByte(1); d.writeShort(1); d.writeByte('k'.code); d.writeByte(5); d.writeByte(0); return o.toByteArray() }

    @Test fun `scan follows the vanilla accounting and finds the end`() {
        val s = stringTag("hello"); val i = NbtScan.scan(s, 0)
        assertEquals(s.size, i.end); assertEquals(36L + 10, i.heap); assertEquals(5, i.maxString)
        val c = smallCompound(); val ci = NbtScan.scan(c, 0)
        assertEquals(c.size, ci.end); assertEquals(48L + 64 + 2 + 9, ci.heap); assertEquals(1, ci.depth)
        val d = deep(10); val di = NbtScan.scan(d, 0); assertEquals(d.size, di.end); assertEquals(21, di.depth)
        assertEquals(-1, NbtScan.scan(d, 0, d.size - 1).end, "cut off")
    }

    @Test fun `a 2 MiB custom payload is dropped, 1 MiB passes`() {
        val s = safety(); val ok = W().vi(id("CustomPayload")).utf("test:chan").bytes(ByteArray(1024 * 1024)).done()
        assertTrue(s.check(ConnectionState.PLAY, ok).frame!!.contentEquals(ok))
        val big = W().vi(id("CustomPayload")).utf("test:chan").bytes(ByteArray(2 * 1024 * 1024)).done()
        val v = s.check(ConnectionState.PLAY, big)
        assertNull(v.frame); assertNull(v.kick)
        assertTrue(log.single().contains("custom_payload"), log.toString())
    }

    @Test fun `a frame beyond the wire limits is dropped, a compressible one of the same size passes`() {
        val s = safety()
        val rnd = ByteArray(2_600_000).also { java.util.Random(1).nextBytes(it) }
        assertNull(s.check(ConnectionState.PLAY, W().vi(id("SetTime")).bytes(rnd).done()).frame, "incompressible 2.6 MB")
        val zeros = W().vi(id("SetTime")).bytes(ByteArray(2_600_000)).done()
        assertNotNull(s.check(ConnectionState.PLAY, zeros).frame, "compressible 2.6 MB")
        assertNull(s.check(ConnectionState.PLAY, W().vi(id("SetTime")).bytes(ByteArray(9 * 1024 * 1024)).done()).frame, "9 MiB uncompressed")
    }

    @Test fun `a critical packet kicks the player instead of hanging`() {
        val v = safety().check(ConnectionState.PLAY, W().vi(id("Login")).bytes(ByteArray(9 * 1024 * 1024)).done())
        assertNull(v.frame); assertNotNull(v.kick)
        val cfgFrame = W().vi(0x07).bytes(ByteArray(9 * 1024 * 1024)).done()   // any configuration frame
        assertNotNull(safety().check(ConnectionState.CONFIGURATION, cfgFrame).kick)
    }

    private fun chunk(bes: List<ByteArray>): ByteArray {
        val w = W().vi(id("LevelChunkWithLight")).int(3).int(-4).vi(1).vi(0).vi(2).long(0).long(0)   // heightmap type 0 with 2 longs
        w.vi(16).bytes(ByteArray(16))                                                                    // section data
        w.vi(bes.size); bes.forEachIndexed { i, nbt -> w.byte(i).short(64).vi(7).bytes(nbt) }
        w.vi(0).vi(0).vi(0).vi(0).vi(0).vi(0)                                                            // light: 4 masks, 2 arrays lists (empty)
        return w.done()
    }

    @Test fun `a 3 MiB block entity tag in a chunk is replaced by an empty tag, the others stay`() {
        val good = NBTCompound().also { it.setTag("Text1", NBTString("hi")) }
        val goodBytes = ByteArrayOutputStream().also { o -> val w = com.github.retrooper.packetevents.wrapper.PacketWrapper.createUniversalPacketWrapper(io.netty.buffer.Unpooled.buffer()); w.writeNBTRaw(good); val b = w.buffer as io.netty.buffer.ByteBuf; o.write(ByteArray(b.readableBytes()).also { b.readBytes(it) }) }.toByteArray()
        val poison = byteArrayTag(3 * 1024 * 1024).let { arr -> ByteArrayOutputStream().also { o -> o.write(10); o.write(7); o.write(0); o.write(1); o.write('a'.code); o.write(arr, 1, arr.size - 1); o.write(0) }.toByteArray() }
        val frame = chunk(listOf(goodBytes, poison, goodBytes))
        val v = safety().check(ConnectionState.PLAY, frame)
        val out = v.frame!!
        assertTrue(out.size < frame.size / 100, "poison removed: ${out.size} < ${frame.size}")
        assertEquals(frame.size - poison.size + 2, out.size)
        // the empty tag sits between the two good ones
        val expected = chunk(listOf(goodBytes, byteArrayOf(10, 0), goodBytes))
        assertContentEquals(expected, out)
        assertTrue(log.single().contains("block_entity_too_big"))
    }

    @Test fun `a chunk without poison is forwarded byte for byte`() {
        val f = chunk(listOf(byteArrayOf(10, 0)))
        assertSame(f, safety().check(ConnectionState.PLAY, f).frame)
    }

    @Test fun `a 40k character custom name in a system chat message is cut`() {
        val long = "x".repeat(40_000)
        val frame = W().vi(id("SystemChat")).bytes(stringTag(long)).byte(0).done()
        val out = safety().check(ConnectionState.PLAY, frame).frame!!
        assertEquals(frame.size - (40_000 - 32767), out.size)
        assertEquals(0, out[out.size - 1].toInt())
    }

    @Test fun `frames the master built itself are sanitised and never kick`() {
        val s = safety()
        val deepChat = W().vi(id("SystemChat")).bytes(deep(200)).byte(0).done()
        assertContentEquals(W().vi(id("SystemChat")).bytes(NbtScan.textTag(SafetyConfig().placeholder)).byte(0).done(), s.checkMasterFrame(deepChat))
        assertNull(s.checkMasterFrame(W().vi(id("Login")).bytes(ByteArray(9 * 1024 * 1024)).done()), "a critical violation is dropped, not turned into a kick")
    }

    @Test fun `a deeply nested component is replaced by a placeholder`() {
        val frame = W().vi(id("SystemChat")).bytes(deep(200)).byte(0).done()
        val out = safety().check(ConnectionState.PLAY, frame).frame!!
        assertContentEquals(W().vi(id("SystemChat")).bytes(NbtScan.textTag(SafetyConfig().placeholder)).byte(0).done(), out)
    }

    @Test fun `a nesting bomb deeper than the scanner stack is a violation of the title packet`() {
        val frame = W().vi(id("SetTitleText")).bytes(deep(70_000)).done()
        assertNull(safety().check(ConnectionState.PLAY, frame).frame)
    }

    @Test fun `tab list header and footer are checked`() {
        val frame = W().vi(id("TabList")).bytes(stringTag("ok")).bytes(stringTag("y".repeat(50_000))).done()
        val out = safety().check(ConnectionState.PLAY, frame).frame!!
        assertEquals(frame.size - (50_000 - 32767), out.size)
    }

    @Test fun `an item with a huge custom data component loses it, the rest of the packet is untouched`() {
        val data = NBTCompound().also { it.setTag("blob", NBTByteArray(ByteArray(3_000_000))) }
        val name = NBTString("keep")
        val poisoned = ItemStack.builder().type(ItemTypes.DIAMOND).amount(3).component(ComponentTypes.CUSTOM_DATA, data).build()
        val plain = ItemStack.builder().type(ItemTypes.STONE).amount(1).build()
        val w = SafeWrapper()
        w.writeVarInt(id("ContainerSetContent")); w.writeVarInt(1); w.writeVarInt(5); w.writeVarInt(2); w.writeItemStack(plain); w.writeItemStack(poisoned); w.writeItemStack(plain)
        val frame = ByteArray(w.buf.readableBytes()).also { w.buf.readBytes(it) }
        val out = safety().check(ConnectionState.PLAY, frame).frame!!
        assertTrue(out.size < 200, "size ${out.size}")
        val r = SafeWrapper(out, 1 + 0); r.readVarInt(); r.readVarInt(); assertEquals(2, r.readVarInt())
        r.readItemStack(); val b = r.readItemStack(); val c = r.readItemStack()
        assertEquals(ItemTypes.DIAMOND, b.type); assertEquals(3, b.amount); assertFalse(b.hasComponent(ComponentTypes.CUSTOM_DATA)); assertEquals(ItemTypes.STONE, c.type)
        assertEquals(0, r.buf.readableBytes())
    }

    @Test fun `a legitimate item with a modest custom data component is not touched`() {
        val data = NBTCompound().also { it.setTag("note", NBTString("x".repeat(2000))) }
        val st = ItemStack.builder().type(ItemTypes.DIAMOND).amount(1).component(ComponentTypes.CUSTOM_DATA, data).build()
        val w = SafeWrapper(); w.writeVarInt(id("ContainerSetSlot")); w.writeVarInt(0); w.writeVarInt(1); w.writeShort(3); w.writeItemStack(st)
        val frame = ByteArray(w.buf.readableBytes()).also { w.buf.readBytes(it) }
        assertSame(frame, safety(SafetyConfig(itemScanBytes = 0)).check(ConnectionState.PLAY, frame).frame)
    }

    @Test fun `the safety section is validated`() {
        val c = SafetyConfig.from(FissionConfig.parse("safety: { max-payload-bytes: 500000, chunk-policy: disconnect }"))
        assertEquals(500000, c.maxPayloadBytes); assertEquals(Policy.DISCONNECT, c.chunkPolicy); assertTrue(c.enabled)
        assertFailsWith<ConfigException> { SafetyConfig.from(FissionConfig.parse("safety: { chunk-policy: empty }")) }
        assertFailsWith<ConfigException> { SafetyConfig.from(FissionConfig.parse("safety: { max-frame-bytes: 99999999 }")) }.let { assertTrue(it.message!!.contains("safety.max-frame-bytes")) }
        assertFailsWith<ConfigException> { FissionConfig.parse("safety: { max-frames: 1 }").let { r -> SafetyConfig.from(r); r.finish() } }.let { assertTrue(it.message!!.contains("safety.max-frames")) }
    }

    @Test fun `disabled means untouched`() {
        val big = W().vi(id("CustomPayload")).utf("a:b").bytes(ByteArray(2 * 1024 * 1024)).done()
        assertSame(big, safety(SafetyConfig(enabled = false)).check(ConnectionState.PLAY, big).frame)
    }
}
