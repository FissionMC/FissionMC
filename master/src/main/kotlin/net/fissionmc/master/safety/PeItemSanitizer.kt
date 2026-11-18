package net.fissionmc.master.safety

import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.component.ComponentType
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter
import com.github.retrooper.packetevents.protocol.nbt.codec.NBTCodec
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.fissionmc.master.seamless.PlayPacketIds
import java.util.Optional

/** A PacketEvents wrapper over a frame that reads NBT with a generous limit (the poisoned data is exactly what the client limit would reject; here it is measured and cut out). */
class SafeWrapper(frame: ByteArray, startAt: Int) : PacketWrapper<SafeWrapper>(ClientVersion.V_26_3, ServerVersion.V_26_3, 0) {
    init { buffer = Unpooled.wrappedBuffer(frame); (buffer as ByteBuf).readerIndex(startAt) }
    constructor() : this(ByteArray(0), 0) { buffer = Unpooled.buffer() }
    val buf get() = buffer as ByteBuf
    override fun readNBTRaw(): NBT = NBTCodec.readNBTFromBuffer(buffer!!, serverVersion, NBTLimiter.forBuffer(buffer!!, 24 shl 20, 512))
    override fun readUnlimitedNBTRaw(): NBT = readNBTRaw()
}

/**
 * Layer 2 for items: container content, set slot, cursor, player inventory, equipment, entity data (item entities, name tags) and merchant offers. Each ItemStack is read with
 * PacketEvents; one whose encoding or a component is beyond the limits has its offending components removed (biggest first); the frame is rebuilt by replacing only the byte range of the
 * stack, everything else stays byte-identical. A frame without a bad stack is returned as null (nothing to change).
 */
object PeItemSanitizer : ItemSanitizer {
    private val ids by lazy {
        mapOf(PlayPacketIds.c("ContainerSetContent") to 0, PlayPacketIds.c("ContainerSetSlot") to 1, PlayPacketIds.c("SetCursorItem") to 2, PlayPacketIds.c("SetPlayerInventory") to 3,
            PlayPacketIds.c("SetEquipment") to 4, PlayPacketIds.c("SetEntityData") to 5, PlayPacketIds.c("MerchantOffers") to 6)
    }

    private fun idLen(f: ByteArray): Int { var i = 0; while (i < f.size && f[i].toInt() and 0x80 != 0) i++; return i + 1 }

    private class Ctx(val f: ByteArray, val w: SafeWrapper, val cfg: SafetyConfig, val report: (String, Int) -> Unit) { val e = Edit(f) }

    override fun sanitize(frame: ByteArray, id: Int, cfg: SafetyConfig, report: (String, Int) -> Unit): ByteArray? {
        val kind = ids[id] ?: return null
        if (frame.size > 12 shl 20) return null
        val w = SafeWrapper(frame, idLen(frame)); val c = Ctx(frame, w, cfg, report)
        try {
            when (kind) {
                0 -> { w.readVarInt(); w.readVarInt(); val n = w.readVarInt(); if (n < 0 || n > 100_000) throw Unparseable("slots $n"); repeat(n) { stack(c) }; stack(c) }
                1 -> { w.readVarInt(); w.readVarInt(); w.readShort(); stack(c) }
                2 -> stack(c)
                3 -> { w.readVarInt(); stack(c) }
                4 -> { w.readVarInt(); do { val s = w.readUnsignedByte().toInt(); stack(c); val more = s and 0x80 != 0; if (!more) break } while (true) }
                5 -> { w.readVarInt(); metadata(c) }
                6 -> offers(c)
            }
        } catch (t: Violation) { throw t } catch (t: Throwable) {
            if (t.message?.contains("limit reached") == true) throw Violation("item_data_too_big", t.message ?: "")
            throw Unparseable("items: $t")
        }
        return if (c.e.changed) c.e.result() else null
    }

    private fun stack(c: Ctx) {
        val s = c.w.buf.readerIndex()
        val st = c.w.readItemStack()
        val e = c.w.buf.readerIndex()
        val fixed = fix(c, st, e - s) ?: return
        val tmp = SafeWrapper(); tmp.writeItemStack(fixed)
        c.e.replace(s, e, ByteArray(tmp.buf.readableBytes()).also { tmp.buf.readBytes(it) })
    }

    private fun metadata(c: Ctx) {
        val w = c.w
        while (true) {
            val index = w.readUnsignedByte().toInt(); if (index == 255) return
            val type = com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.getById(ClientVersion.V_26_3, w.readVarInt()) ?: throw Unparseable("entity data type")
            val start = w.buf.readerIndex()
            val v = type.read(w)
            val end = w.buf.readerIndex()
            when {
                v is ItemStack -> fixAt(c, start, end, v)
                v is Optional<*> && v.orElse(null) is ItemStack -> fixAt(c, start, end, v.get() as ItemStack, optional = true)
                type.name.toString().endsWith("component") -> componentAt(c, start, end, optional = v is Optional<*>)
            }
        }
    }

    private fun fixAt(c: Ctx, start: Int, end: Int, st: ItemStack, optional: Boolean = false) {
        val fixed = fix(c, st, end - start) ?: return
        val tmp = SafeWrapper(); if (optional) tmp.writeBoolean(true); tmp.writeItemStack(fixed)
        c.e.replace(start, end, ByteArray(tmp.buf.readableBytes()).also { tmp.buf.readBytes(it) })
    }

    private fun componentAt(c: Ctx, start: Int, end: Int, optional: Boolean) {
        var s = start
        if (optional) { if (c.f[s].toInt() == 0) return; s++ }
        val info = NbtScan.scan(c.f, s, end, c.cfg.maxStringBytes)
        if (info.end < 0) return
        val bad = info.depth > c.cfg.maxNbtDepth || info.heap > c.cfg.maxNbtBytes || info.end - s > c.cfg.maxComponentBytes
        if (bad) { c.e.replace(s, end, NbtScan.textTag(c.cfg.placeholder)); c.report("entity_name_too_big", end - s) }
        else if (info.rewritten != null) { c.e.replace(s, end, info.rewritten); c.report("entity_name_string_cut", end - s - info.rewritten.size) }
    }

    private fun offers(c: Ctx) {
        val w = c.w
        w.readVarInt()
        val n = w.readVarInt(); if (n < 0 || n > 10_000) throw Unparseable("offers $n")
        repeat(n) {
            com.github.retrooper.packetevents.protocol.recipe.data.MerchantItemCost.readItem(w)
            stack(c)
            w.readOptional(com.github.retrooper.packetevents.protocol.recipe.data.MerchantItemCost::readItem)
            w.readBoolean(); w.readInt(); w.readInt(); w.readInt(); w.readInt(); w.readFloat(); w.readInt()
        }
    }

    private fun encode(w: SafeWrapper, f: (SafeWrapper) -> Unit): ByteArray { val t = SafeWrapper(); f(t); return ByteArray(t.buf.readableBytes()).also { t.buf.readBytes(it) } }

    /** The stack without its bad components, or null when it is fine. [wire] = its size on the wire as it came. */
    @Suppress("UNCHECKED_CAST")
    private fun fix(c: Ctx, st: ItemStack, wire: Int): ItemStack? {
        if (st.isEmpty || !st.hasComponentPatches()) return if (wire > c.cfg.maxItemBytes) throw Violation("item_too_big", "$wire bytes without components") else null
        val patches = st.components.patches
        var removed = 0; var saved = 0
        val sizes = HashMap<ComponentType<*>, Int>()
        for ((type, value) in patches.entries.toList()) {
            if (!value.isPresent) continue
            val bytes = try { encode(c.w) { t -> (type as ComponentType<Any>).write(t, value.get()) } } catch (t: Throwable) { continue }
            sizes[type] = bytes.size
            var bad = bytes.size > c.cfg.maxItemComponentBytes
            if (!bad && bytes.isNotEmpty()) {   // a component that is one NBT tag (custom data, names, lore entries...) is also measured the way the client counts it
                val i = NbtScan.scan(bytes, 0)
                if (i.end == bytes.size && (i.heap > c.cfg.maxNbtBytes || i.depth > c.cfg.maxNbtDepth || i.maxString > c.cfg.maxStringBytes)) bad = true
            }
            if (bad) { patches.remove(type); removed++; saved += bytes.size; c.report("item_component_removed", bytes.size) }
        }
        // the whole stack still too big: drop the biggest components
        var total = encode(c.w) { it.writeItemStack(st) }.size
        while (total > c.cfg.maxItemBytes) {
            val big = patches.entries.filter { it.value.isPresent }.maxByOrNull { sizes[it.key] ?: 0 } ?: throw Violation("item_too_big", "$total bytes")
            patches.remove(big.key); removed++; saved += sizes[big.key] ?: 0; c.report("item_component_removed", sizes[big.key] ?: 0)
            total = encode(c.w) { it.writeItemStack(st) }.size
        }
        return if (removed > 0) st else null
    }
}
