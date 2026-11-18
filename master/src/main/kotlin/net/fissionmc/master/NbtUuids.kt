package net.fissionmc.master

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * The master has no Minecraft classes, but an entity blob (zlib-compressed NBT, as `NbtIo.write` makes it) names the entity and the whole tree of its passengers by `UUID`
 * int arrays. This reads just that, so a handover acknowledgement can be checked against what was actually offered. Anything unexpected throws [IOException]; callers then trust
 * only the blob's own uuid.
 */
object NbtUuids {
    private const val MAX_INFLATED = 16 shl 20
    private const val MAX_DEPTH = 64
    private const val MAX_ENTITIES = 4096

    /** (high, low) pairs of the root entity and every entity in its `Passengers` tree. */
    fun entityTree(zlibNbt: ByteArray): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        DataInputStream(Capped(InflaterInputStream(zlibNbt.inputStream()), MAX_INFLATED.toLong()).buffered()).use { i ->
            if (i.readByte().toInt() != 10) throw IOException("root is not a compound")
            i.skipNBytes(i.readUnsignedShort().toLong())   // the root name
            compound(i, out, 0, true)
        }
        return out
    }

    private fun compound(i: DataInputStream, out: MutableList<Pair<Long, Long>>, depth: Int, entity: Boolean) {
        if (depth > MAX_DEPTH) throw IOException("too deep")
        while (true) {
            val t = i.readByte().toInt()
            if (t == 0) return
            val name = i.readUTF()
            when {
                entity && name == "UUID" && t == 11 -> {
                    val n = i.readInt(); if (n != 4) { skipInts(i, n); continue }
                    val a = i.readInt().toLong(); val b = i.readInt().toLong(); val c = i.readInt().toLong(); val d = i.readInt().toLong()
                    if (out.size >= MAX_ENTITIES) throw IOException("too many entities")
                    out += ((a shl 32) or (b and 0xffffffffL)) to ((c shl 32) or (d and 0xffffffffL))
                }
                entity && name == "Passengers" && t == 9 -> {
                    val et = i.readByte().toInt(); val n = i.readInt()
                    if (n < 0) throw IOException("negative list")
                    if (n > 0 && et != 10) { repeat(n) { skip(i, et, depth + 1) } }
                    else repeat(n) { compound(i, out, depth + 1, true) }
                }
                else -> skip(i, t, depth + 1)
            }
        }
    }

    private fun skipInts(i: DataInputStream, n: Int) { if (n < 0) throw IOException("negative array"); i.skipNBytes(n * 4L) }

    private fun skip(i: DataInputStream, t: Int, depth: Int) {
        if (depth > MAX_DEPTH) throw IOException("too deep")
        when (t) {
            1 -> i.skipNBytes(1); 2 -> i.skipNBytes(2); 3 -> i.skipNBytes(4); 4 -> i.skipNBytes(8); 5 -> i.skipNBytes(4); 6 -> i.skipNBytes(8)
            7 -> { val n = i.readInt(); if (n < 0) throw IOException("negative array"); i.skipNBytes(n.toLong()) }
            8 -> i.skipNBytes(i.readUnsignedShort().toLong())
            9 -> { val et = i.readByte().toInt(); val n = i.readInt(); if (n < 0) throw IOException("negative list"); if (n > 0 && et == 0) throw IOException("list of end"); repeat(n) { skip(i, et, depth + 1) } }
            10 -> compound(i, ArrayList(), depth + 1, false)
            11 -> skipInts(i, i.readInt())
            12 -> { val n = i.readInt(); if (n < 0) throw IOException("negative array"); i.skipNBytes(n * 8L) }
            else -> throw IOException("unknown tag type $t")
        }
    }

    private class Capped(private val src: InputStream, private val max: Long) : InputStream() {
        private var n = 0L
        private fun count(k: Int): Int { if (k > 0) { n += k; if (n > max) throw IOException("inflated data over $max bytes") }; return k }
        override fun read(): Int { val b = src.read(); if (b >= 0) count(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(src.read(b, off, len))
        override fun close() = src.close()
    }
}
