package net.fissionmc.master.safety

import java.io.ByteArrayOutputStream

/** The packet is cut off or does not have the layout this reader knows: not a violation, the frame is passed on as it is. */
class Unparseable(msg: String) : RuntimeException(msg, null, false, false)
/** The packet breaks a vanilla client limit and cannot be repaired in place: the configured policy decides. */
class Violation(val reason: String, val detail: String = "") : RuntimeException(reason, null, false, false)

/** Reader over a frame (minecraft wire types), every read is bounds checked. */
class Cur(val b: ByteArray, var p: Int = 0) {
    private fun need(n: Int) { if (n < 0 || p + n > b.size) throw Unparseable("cut off at $p (+$n of ${b.size})") }
    fun byte(): Int { need(1); return b[p++].toInt() and 0xFF }
    fun bool() = byte() != 0
    fun skip(n: Int) { need(n); p += n }
    fun short(): Int { need(2); val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2; return v }
    fun int(): Int { need(4); var v = 0; for (i in 0 until 4) v = (v shl 8) or (b[p + i].toInt() and 0xFF); p += 4; return v }
    fun long(): Long { need(8); var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[p + i].toLong() and 0xFF); p += 8; return v }
    fun varInt(): Int {
        var v = 0
        for (i in 0 until 5) { val x = byte(); v = v or ((x and 0x7F) shl (7 * i)); if (x and 0x80 == 0) return v }
        throw Unparseable("varint too big at $p")
    }
    /** utf string: length varint + bytes; returns the byte length. */
    fun utf(): Int { val n = varInt(); if (n < 0 || n > 3 * 32767 + 8) throw Unparseable("string length $n at $p"); skip(n); return n }
    fun byteArray(): Int { val n = varInt(); if (n < 0) throw Unparseable("negative array"); skip(n); return n }
    val left get() = b.size - p
}

/** Replaces byte ranges of a frame (ascending, non overlapping). */
class Edit(private val src: ByteArray) {
    private val out = ByteArrayOutputStream(src.size); private var at = 0; var changed = false; private set
    fun replace(from: Int, to: Int, with: ByteArray) { out.write(src, at, from - at); out.write(with); at = to; changed = true }
    fun result(): ByteArray { out.write(src, at, src.size - at); at = src.size; return out.toByteArray() }
}

object Wire {
    fun varIntBytes(v: Int): ByteArray { val o = ByteArrayOutputStream(5); var x = v; while (x and 0x7F.inv() != 0) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }; o.write(x); return o.toByteArray() }
}
