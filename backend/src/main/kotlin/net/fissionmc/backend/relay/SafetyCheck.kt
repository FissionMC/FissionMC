package net.fissionmc.backend.relay

import com.github.retrooper.packetevents.protocol.ConnectionState
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import net.fissionmc.master.client.CompressionEncoder
import net.fissionmc.master.client.FramePrepender
import net.fissionmc.master.safety.ClientSafety
import net.fissionmc.master.safety.PeItemSanitizer
import net.fissionmc.master.safety.SafetyConfig
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import java.io.ByteArrayOutputStream
import java.util.logging.Logger

/**
 * The vanilla oracle of the client safety layer (`-PcheckSafety=1`, runs inside the booted backend like [LimboCheck]): poisoned packets are BUILT WITH VANILLA'S OWN CODECS (or hand built where
 * vanilla has no public constructor), the unsanitised bytes must FAIL in vanilla's client codec (proof that the vector is real), then the output of the master's [ClientSafety] must DECODE with
 * vanilla's codec and keep the legitimate content. Frame size limits are checked with vanilla's own Varint21FrameDecoder + CompressionDecoder behind the master's encoder.
 */
object SafetyCheck {
    private val log = Logger.getLogger("FissionMC")
    private var passed = 0; private var failed = 0

    private fun verdict(name: String, ok: Boolean, detail: String = "") { if (ok) passed++ else failed++; log.info("[fission] safety check ${if (ok) "OK  " else "FAIL"} $name $detail") }

    fun run(server: MinecraftServer) {
        try { runAll(server) } catch (t: Throwable) { log.warning("[fission] safety check crashed: $t"); t.printStackTrace(); failed++ }
        log.info("[fission] safety check finished: $passed passed, $failed failed")
        if (System.getProperty("fission.check-safety") == "exit") server.halt(false)   // -PcheckSafety=exit stops the server afterwards
    }

    private fun runAll(server: MinecraftServer) {
        FissionPacketEventsAPI.install(Side.MASTER)
        val play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec()
        @Suppress("UNCHECKED_CAST") fun enc(p: Packet<*>): ByteArray { val o = Unpooled.buffer(); (play as net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, Packet<*>>).encode(o, p); return ByteArray(o.readableBytes()).also { o.readBytes(it) } }
        fun dec(b: ByteArray): Result<Packet<*>> = runCatching { val buf = Unpooled.wrappedBuffer(b); val p = play.decode(buf); check(buf.readableBytes() == 0) { "${buf.readableBytes()} bytes unread" }; p }
        val logLines = ArrayList<String>()
        val safety = ClientSafety({ "oracle" }, { "oracle" }, { logLines += it }, PeItemSanitizer)
        fun sanitise(b: ByteArray): ByteArray? = safety.check(ConnectionState.PLAY, b).frame
        fun regBuf() = RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess())
        fun comp(c: Component): ByteArray { val b = regBuf(); net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(b, c); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }

        // ---- items: huge custom data (wire size) and a heap bomb (small on the wire, over the client's quota)
        val bomb = CompoundTag().also { t -> for (i in 0 until 40_000) t.putByte("k$i", 1) }
        val blob = CompoundTag().also { it.putByteArray("blob", ByteArray(3_000_000)) }
        for ((label, tag) in listOf("3 MB custom data" to blob, "heap bomb custom data" to bomb)) {
            val poisoned = ItemStack(Items.DIAMOND, 3).also { it.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)) }
            val plain = ItemStack(Items.STONE, 7)
            val raw = enc(net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket(1, 5, listOf(plain, poisoned, plain), ItemStack.EMPTY))
            verdict("vector real: container content with $label (${raw.size} wire bytes) fails in vanilla", dec(raw).isFailure, dec(raw).exceptionOrNull()?.javaClass?.simpleName ?: "DECODED")
            val out = sanitise(raw)
            val p = out?.let { dec(it) }
            val items = (p?.getOrNull() as? net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket)?.items()
            verdict("sanitised container content with $label decodes", p?.isSuccess == true, p?.exceptionOrNull()?.toString() ?: "")
            verdict("  ... and keeps the stacks, only the custom data is gone", items != null && items.size == 3 && items[1].`is`(Items.DIAMOND) && items[1].count == 3 && !items[1].has(DataComponents.CUSTOM_DATA) && items[0].`is`(Items.STONE) && items[2].count == 7)
            val eq = enc(net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(5, listOf(com.mojang.datafixers.util.Pair.of(net.minecraft.world.entity.EquipmentSlot.HEAD, poisoned), com.mojang.datafixers.util.Pair.of(net.minecraft.world.entity.EquipmentSlot.CHEST, plain))))
            verdict("vector real: equipment with $label fails in vanilla", dec(eq).isFailure)
            val eo = sanitise(eq); verdict("sanitised equipment with $label decodes", eo != null && dec(eo).isSuccess, if (eo == null) "dropped: ${logLines.lastOrNull()}" else detail(eo, ::dec))
        }
        // a legitimate big-ish stack stays byte for byte
        val fine = ItemStack(Items.DIAMOND, 3).also { it.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { t -> t.putString("note", "x".repeat(5000)) })) }
        val fineRaw = enc(net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket(1, 5, List(5) { fine }, ItemStack.EMPTY))
        verdict("legitimate stacks (5 x 5 KB custom data) pass unchanged", sanitise(fineRaw)?.contentEquals(fineRaw) == true)

        // ---- components: nested too deep for the client (NBT depth 512): {text:"y",extra:[{text:"y",extra:[...]}]} hand built, vanilla's component encoder overflows its own stack first
        fun nestedComponent(levels: Int): ByteArray {
            val o = ByteArrayOutputStream(); o.write(10)
            repeat(levels) { o.write(byteArrayOf(8, 0, 4)); o.write("text".toByteArray()); o.write(byteArrayOf(0, 1, 'y'.code.toByte())); o.write(byteArrayOf(9, 0, 5)); o.write("extra".toByteArray()); o.write(byteArrayOf(10, 0, 0, 0, 1)) }
            repeat(levels + 1) { o.write(0) }
            return o.toByteArray()
        }
        val deep = nestedComponent(300); val footer = comp(Component.literal("footer"))
        fun withId(name: String, vararg parts: ByteArray): ByteArray { val o = ByteArrayOutputStream(); o.write(idOf(name)); parts.forEach { o.write(it) }; return o.toByteArray() }
        for ((label, raw) in listOf("system chat" to withId("SystemChat", deep, byteArrayOf(0)), "tab list header" to withId("TabList", deep, footer), "tab list footer" to withId("TabList", footer, deep),
            "action bar" to withId("SetActionBarText", deep), "title" to withId("SetTitleText", deep), "subtitle" to withId("SetSubtitleText", deep), "disconnect" to withId("Disconnect", deep))) {
            verdict("vector real: $label with 300 nested levels fails in vanilla", dec(raw).isFailure, dec(raw).exceptionOrNull()?.toString()?.take(80) ?: "DECODED")
            val out = sanitise(raw); verdict("sanitised $label decodes", out != null && dec(out).isSuccess, detail(out, ::dec))
        }
        // long text: cut to the limit, still decodes
        val longText = enc(net.minecraft.network.protocol.game.ClientboundSystemChatPacket(Component.literal("z".repeat(60_000)), false))
        val lt = sanitise(longText); val ltp = lt?.let { dec(it).getOrNull() } as? net.minecraft.network.protocol.game.ClientboundSystemChatPacket
        verdict("60k character system chat is cut to 32767 characters and decodes", ltp != null && ltp.content().string.length == 32767, "len=${ltp?.content()?.string?.length}")
        val plainChat = enc(net.minecraft.network.protocol.game.ClientboundSystemChatPacket(Component.literal("hello"), false))
        verdict("ordinary chat passes unchanged", sanitise(plainChat)?.contentEquals(plainChat) == true)

        // ---- player info display name with a nested component (hand built layout, decoded by vanilla)
        fun playerInfo(display: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(); o.write(idOf("PlayerInfoUpdate"))
            o.write(0x01 or 0x20); o.write(1)   // ADD_PLAYER | UPDATE_DISPLAY_NAME, one entry
            o.write(ByteArray(16) { 1 }); o.write(4); o.write("Test".toByteArray()); o.write(0)   // uuid, name, no properties
            o.write(1); o.write(display)        // display name present
            return o.toByteArray()
        }
        val pi = playerInfo(deep)
        verdict("vector real: player info display name nested 300 levels fails in vanilla", dec(pi).isFailure, dec(pi).exceptionOrNull()?.toString() ?: "DECODED")
        val pio = sanitise(pi); verdict("sanitised player info decodes", pio != null && dec(pio).isSuccess, detail(pio, ::dec))
        val pif = playerInfo(comp(Component.literal("Nick"))); verdict("ordinary player info passes unchanged and decodes", sanitise(pif)?.contentEquals(pif) == true && dec(pif).isSuccess, dec(pif).exceptionOrNull()?.toString() ?: "")

        // ---- chunk with a 3 MB block entity tag (hand built: vanilla has no public constructor from a poisoned tag), vanilla decodes the result
        fun chunk(tags: List<ByteArray>): ByteArray {
            val o = ByteArrayOutputStream(); o.write(idOf("LevelChunkWithLight"))
            fun vi(v: Int) { var x = v; while (x and 0x7F.inv() != 0) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }; o.write(x) }
            o.write(byteArrayOf(0, 0, 0, 3, -1, -1, -1, -4)); vi(0)          // x, z, no heightmaps
            vi(16); o.write(ByteArray(16))                                    // section data
            vi(tags.size); tags.forEachIndexed { i, t -> o.write(i); o.write(0); o.write(64); vi(0); o.write(t) }   // packedXZ, y(short), type 0 (air-ish id, any valid), tag
            repeat(4) { vi(0) }; vi(0); vi(0)                                  // light masks and arrays
            return o.toByteArray()
        }
        fun nbt(c: CompoundTag): ByteArray { val b = Unpooled.buffer(); val f = net.minecraft.network.FriendlyByteBuf(b); f.writeNbt(c); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
        val sign = nbt(CompoundTag().also { it.putString("note", "hello") })
        for ((label, tag) in listOf("3 MB array" to nbt(blob), "heap bomb" to nbt(bomb))) {
            val raw = chunk(listOf(sign, tag, sign))
            verdict("vector real: chunk with a block entity tag ($label, ${raw.size} wire bytes) fails in vanilla", dec(raw).isFailure, dec(raw).exceptionOrNull()?.toString() ?: "DECODED")
            val out = sanitise(raw); val r = out?.let { dec(it) }
            verdict("sanitised chunk ($label) decodes in vanilla", r?.isSuccess == true, r?.exceptionOrNull()?.toString() ?: if (out == null) "dropped" else "")
            verdict("  ... and is tiny again", out != null && out.size < 1000, "${out?.size}")
        }
        val good = chunk(listOf(sign, sign)); verdict("ordinary chunk passes unchanged and decodes", sanitise(good)?.contentEquals(good) == true && dec(good).isSuccess, dec(good).exceptionOrNull()?.toString() ?: "")

        // ---- custom payload: vanilla rejects more than 1 MiB
        fun payload(n: Int): ByteArray { val o = ByteArrayOutputStream(); o.write(idOf("CustomPayload")); val id = "test:chan".toByteArray(); o.write(id.size); o.write(id); o.write(ByteArray(n)); return o.toByteArray() }
        verdict("vector real: custom payload of 1 MiB + 1 fails in vanilla", dec(payload(1024 * 1024 + 1)).isFailure)
        verdict("custom payload of 1 MiB + 1 is dropped, 1 MiB passes and decodes", sanitise(payload(1024 * 1024 + 1)) == null && sanitise(payload(1024 * 1024)) != null && dec(payload(1024 * 1024)).isSuccess, dec(payload(1024 * 1024)).exceptionOrNull()?.toString() ?: "")

        // ---- frame size through vanilla's splitter + decompressor behind the master's encoder
        fun vanillaWire(frame: ByteArray): Boolean {
            val out = EmbeddedChannel(FramePrepender(), CompressionEncoder(256)); out.writeOutbound(Unpooled.wrappedBuffer(frame))
            val inCh = EmbeddedChannel(net.minecraft.network.Varint21FrameDecoder(null), net.minecraft.network.CompressionDecoder(256, true))
            return try {
                while (true) { val b = out.readOutbound<io.netty.buffer.ByteBuf>() ?: break; inCh.writeInbound(b) }
                val got = inCh.readInbound<io.netty.buffer.ByteBuf>(); got != null && got.readableBytes() == frame.size
            } catch (t: Throwable) { false }
        }
        fun filler(n: Int, random: Boolean) = ByteArray(n).also { if (random) java.util.Random(7).nextBytes(it); it[0] = 1 }
        for ((n, random) in listOf(1_900_000 to true, 2_095_000 to true, 2_097_100 to true, 3_000_000 to false, 9_000_000 to false)) {
            val f = filler(n, random)
            val vanillaOk = vanillaWire(f); val guardOk = safety.check(ConnectionState.PLAY, f).frame != null
            verdict("frame of $n bytes (${if (random) "incompressible" else "compressible"}): guard ${if (guardOk) "passes" else "drops"}, vanilla client ${if (vanillaOk) "takes" else "rejects"} it", guardOk == vanillaOk)
        }
        logLines.take(3).forEach { log.info("[fission] safety check sample log: $it") }
    }

    private fun detail(out: ByteArray?, dec: (ByteArray) -> Result<Packet<*>>): String = if (out == null) "dropped" else dec(out).exceptionOrNull()?.toString() ?: ""

    private fun idOf(name: String): Int = net.fissionmc.master.seamless.PlayPacketIds.c(name)
}
