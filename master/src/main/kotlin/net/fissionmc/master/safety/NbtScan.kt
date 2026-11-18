package net.fissionmc.master.safety

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** [end] = index after the tag (-1: malformed or cut off), [heap] = vanilla's NbtAccounter estimate, [depth] = container nesting, [maxString] = longest string/key in wire bytes, [rewritten] = the tag with strings cut. */
class NbtInfo(val end: Int, val heap: Long, val depth: Int, val maxString: Int, val rewritten: ByteArray? = null)

/**
 * Byte-level reader of one NETWORK NBT tag (type byte, no name: the format of every component, item component and block entity tag on the wire since 1.20.2). Iterative, so a
 * nesting bomb cannot overflow the stack. [heap] mirrors vanilla's `NbtAccounter` bookkeeping (what the client counts against its 2 MiB quota; constants read from the 26.3 jar:
 * compound 48 + 64 per entry + 2 per key char, list 36 + 4 per element, string 36 + 2 per char, byte/short/int/long/float/double 9/10/12/16/12/16, arrays 24 + element size per element).
 */
object NbtScan {
    private const val MAX_STACK = 65_536
    val EMPTY_COMPOUND = byteArrayOf(10, 0)

    /** A component (network NBT) that is just the text [s]. */
    fun textTag(s: String): ByteArray { val o = ByteArrayOutputStream(); val d = DataOutputStream(o); d.writeByte(8); d.writeUTF(s.take(2000)); return o.toByteArray() }

    /** @param cutStrings > 0: also build [NbtInfo.rewritten] in which every string/key longer than this many bytes is cut (at a character boundary). */
    fun scan(b: ByteArray, start: Int, limit: Int = b.size, cutStrings: Int = 0): NbtInfo {
        var pos = start; var heap = 0L; var maxDepth = 0; var maxStr = 0
        val out = if (cutStrings > 0) ByteArrayOutputStream() else null
        var copied = start
        var kinds = IntArray(16); var remain = IntArray(16); var elem = IntArray(16); var sp = 0
        fun bad() = NbtInfo(-1, heap, maxDepth, maxStr)
        fun u16(p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun i32(p: Int) = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
        // a string/key at pos (length u16 first); false = cut off
        fun string(): Boolean {
            if (pos + 2 > limit) return false
            val n = u16(pos)
            if (pos + 2 + n > limit) return false
            if (n > maxStr) maxStr = n
            if (out != null && n > cutStrings) {
                var cut = cutStrings
                while (cut > 0 && (b[pos + 2 + cut].toInt() and 0xC0) == 0x80) cut--
                out.write(b, copied, pos - copied); out.write(cut ushr 8); out.write(cut and 0xFF); out.write(b, pos + 2, cut)
                copied = pos + 2 + n
                heap += 2L * cut
            } else heap += 2L * n
            pos += 2 + n
            return true
        }
        // returns false when malformed; containers are pushed
        fun value(t: Int): Boolean {
            when (t) {
                1 -> { heap += 9; pos += 1 }
                2 -> { heap += 10; pos += 2 }
                3, 5 -> { heap += 12; pos += 4 }
                4, 6 -> { heap += 16; pos += 8 }
                7, 11, 12 -> {
                    if (pos + 4 > limit) return false
                    val n = i32(pos); if (n < 0) return false
                    val sz = if (t == 7) 1 else if (t == 11) 4 else 8
                    heap += 24L + sz.toLong() * n; pos += 4; if (pos.toLong() + sz.toLong() * n > limit) return false; pos += sz * n
                }
                8 -> { heap += 36; if (!string()) return false }
                9 -> {
                    if (pos + 5 > limit) return false
                    val et = b[pos].toInt(); val n = i32(pos + 1); pos += 5
                    if (n < 0 || et !in 0..12 || (et == 0 && n > 0)) return false
                    heap += 36L + 4L * n
                    if (n > 0) {
                        if (sp == MAX_STACK) return false
                        if (sp == kinds.size) { kinds = kinds.copyOf(sp * 2); remain = remain.copyOf(sp * 2); elem = elem.copyOf(sp * 2) }
                        kinds[sp] = 9; remain[sp] = n; elem[sp] = et; sp++; if (sp > maxDepth) maxDepth = sp
                    } else { if (sp + 1 > maxDepth) maxDepth = sp + 1 }
                }
                10 -> {
                    heap += 48
                    if (sp == MAX_STACK) return false
                    if (sp == kinds.size) { kinds = kinds.copyOf(sp * 2); remain = remain.copyOf(sp * 2); elem = elem.copyOf(sp * 2) }
                    kinds[sp] = 10; sp++; if (sp > maxDepth) maxDepth = sp
                }
                else -> return false
            }
            return pos <= limit
        }
        if (pos >= limit) return bad()
        val root = b[pos++].toInt()
        if (root == 0) return NbtInfo(pos, 0, 0, 0)
        if (!value(root)) return bad()
        while (sp > 0) {
            val top = sp - 1
            if (kinds[top] == 10) {
                if (pos >= limit) return bad()
                val t = b[pos++].toInt()
                if (t == 0) { sp--; continue }
                heap += 64
                if (!string()) return bad()
                if (!value(t)) return bad()
            } else {
                if (remain[top] == 0) { sp--; continue }
                remain[top]--
                if (!value(elem[top])) return bad()
            }
        }
        val rewritten = if (out != null && out.size() > 0) { out.write(b, copied, pos - copied); out.toByteArray() } else null
        return NbtInfo(pos, heap, maxDepth, maxStr, rewritten)
    }
}
