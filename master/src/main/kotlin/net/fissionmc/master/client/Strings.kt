package net.fissionmc.master.client

import io.netty.buffer.Unpooled

object Strings {
    /** First field of a frame (after the packet id) as a UTF-8 string: the command of the chat command packets, the text of the chat packet. */
    fun readFirst(frame: ByteArray): String? = try {
        val b = Unpooled.wrappedBuffer(frame)
        VarInts.tryRead(b) ?: return null
        val n = VarInts.tryRead(b) ?: return null
        if (n < 0 || n > b.readableBytes()) null else ByteArray(n).also { b.readBytes(it) }.toString(Charsets.UTF_8)
    } catch (_: Throwable) { null }
}
