package net.fissionmc.protocol

import io.netty.buffer.ByteBuf

/** Primitive helpers for the node wire format (varints, UTF-8 strings). */
fun ByteBuf.writeVarInt(value: Int): ByteBuf {
    var v = value
    while (v and 0x7F.inv() != 0) {
        writeByte((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    writeByte(v)
    return this
}

fun ByteBuf.readVarInt(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val b = readByte().toInt()
        result = result or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
        require(shift < 35) { "VarInt too long" }
    }
}

fun ByteBuf.writeString(s: String, maxBytes: Int = 32767): ByteBuf {
    val bytes = s.toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes) { "String of ${bytes.size} bytes exceeds $maxBytes" }
    writeVarInt(bytes.size)
    writeBytes(bytes)
    return this
}

fun ByteBuf.readString(maxBytes: Int = 32767): String {
    val len = readVarInt()
    require(len in 0..maxBytes) { "Bad string length $len" }
    val bytes = ByteArray(len)
    readBytes(bytes)
    return String(bytes, Charsets.UTF_8)
}

fun ByteBuf.writeByteArray(a: ByteArray, max: Int = Int.MAX_VALUE): ByteBuf {
    require(a.size <= max) { "Array of ${a.size} bytes exceeds $max" }
    writeVarInt(a.size)
    writeBytes(a)
    return this
}

fun ByteBuf.readByteArray(max: Int = Int.MAX_VALUE): ByteArray {
    val len = readVarInt()
    require(len in 0..max && len <= readableBytes()) { "Bad array length $len" }
    return ByteArray(len).also { readBytes(it) }
}
