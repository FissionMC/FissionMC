package net.fissionmc.backend.relay

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * Reads zlib-compressed NBT that came from another node. The link limits (`entity-blob`, `item-blob`, `chunk-blob`) bound the COMPRESSED size only, so the
 * inflated size and the NBT heap are bounded here: a few KiB of zeros must not become a gigabyte on a server thread.
 */
object BoundedNbt {
    /** The inflated size and the NBT heap allowed for a blob whose compressed limit is [blobLimit]: 32x, between 8 MiB and 256 MiB. */
    fun cap(blobLimit: Int): Long = (blobLimit.toLong() * 32).coerceIn(8L shl 20, 256L shl 20)

    fun read(bytes: ByteArray, blobLimit: Int): CompoundTag {
        val cap = cap(blobLimit)
        return DataInputStream(Capped(InflaterInputStream(bytes.inputStream()), cap).buffered()).use { NbtIo.read(it, NbtAccounter.create(cap)) }
    }

    /** Fails with an [IOException] once more than [max] bytes were read. */
    class Capped(private val src: InputStream, private val max: Long) : InputStream() {
        private var n = 0L
        private fun count(k: Int): Int { if (k > 0) { n += k; if (n > max) throw IOException("inflated data over $max bytes") }; return k }
        override fun read(): Int { val b = src.read(); if (b >= 0) count(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(src.read(b, off, len))
        override fun close() = src.close()
    }
}
