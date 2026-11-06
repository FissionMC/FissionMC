package net.fissionmc.master.client

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.CorruptedFrameException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher

/** Minecraft varint helpers (pure, unit-tested). */
object VarInts {
    fun size(v: Int): Int { var n = 1; var x = v ushr 7; while (x != 0) { n++; x = x ushr 7 }; return n }
    fun write(buf: ByteBuf, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { buf.writeByte((v and 0x7F) or 0x80); v = v ushr 7 }
        buf.writeByte(v)
    }
    /** Returns the value, or null if [buf] does not yet hold a complete varint (reader index is restored). */
    fun tryRead(buf: ByteBuf): Int? {
        val mark = buf.readerIndex(); var v = 0
        for (i in 0 until 5) {
            if (!buf.isReadable) { buf.readerIndex(mark); return null }
            val b = buf.readByte().toInt()
            v = v or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 == 0) return v
        }
        throw CorruptedFrameException("varint too big")
    }
}

/** Splits the byte stream into frames (varint length prefix). */
class FrameDecoder(private val maxFrame: Int = 2 * 1024 * 1024) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        val mark = input.readerIndex()
        val len = VarInts.tryRead(input) ?: return
        if (len < 0 || len > maxFrame) throw CorruptedFrameException("bad frame length $len")
        if (input.readableBytes() < len) { input.readerIndex(mark); return }
        out += input.readRetainedSlice(len)
    }
}

/** Prepends the varint length to every outgoing frame. */
class FramePrepender : ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val b = msg as ByteBuf
        val out = ctx.alloc().buffer(VarInts.size(b.readableBytes()) + b.readableBytes())
        VarInts.write(out, b.readableBytes()); out.writeBytes(b); b.release()
        ctx.write(out, promise)
    }
}

/** Inbound: strips the compression header (threshold >= 0 negotiated). */
class DecompressionDecoder(private val maxSize: Int = 8 * 1024 * 1024) : ByteToMessageDecoder() {
    private val inflater = Inflater()
    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (!input.isReadable) return
        val size = VarInts.tryRead(input) ?: throw CorruptedFrameException("bad compressed header")
        if (size == 0) { out += input.readRetainedSlice(input.readableBytes()); return }
        if (size < 0 || size > maxSize) throw CorruptedFrameException("bad uncompressed size $size")
        val src = ByteArray(input.readableBytes()); input.readBytes(src)
        inflater.reset(); inflater.setInput(src)
        val dst = ByteArray(size)
        val n = inflater.inflate(dst)
        if (n != size || !inflater.finished()) throw CorruptedFrameException("compressed size mismatch")
        out += Unpooled.wrappedBuffer(dst)
    }
    override fun handlerRemoved0(ctx: ChannelHandlerContext) { inflater.end() }
}

/** Outbound: compresses frames of at least [threshold] bytes. */
class CompressionEncoder(private val threshold: Int) : ChannelOutboundHandlerAdapter() {
    private val deflater = Deflater()
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val b = msg as ByteBuf
        val n = b.readableBytes()
        val out = ctx.alloc().buffer(n + 8)
        if (n < threshold) { VarInts.write(out, 0); out.writeBytes(b) }
        else {
            val src = ByteArray(n); b.readBytes(src)
            VarInts.write(out, n)
            deflater.reset(); deflater.setInput(src); deflater.finish()
            val tmp = ByteArray(8192)
            while (!deflater.finished()) { val c = deflater.deflate(tmp); out.writeBytes(tmp, 0, c) }
        }
        b.release()
        ctx.write(out, promise)
    }
    override fun handlerRemoved(ctx: ChannelHandlerContext) { deflater.end() }
}

/** AES/CFB8 in both directions (one Cipher per direction). */
class CipherDecoder(private val cipher: Cipher) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val b = msg as ByteBuf
        val inBytes = ByteArray(b.readableBytes()); b.readBytes(inBytes); b.release()
        ctx.fireChannelRead(Unpooled.wrappedBuffer(cipher.update(inBytes)))
    }
}
class CipherEncoder(private val cipher: Cipher) : ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val b = msg as ByteBuf
        val inBytes = ByteArray(b.readableBytes()); b.readBytes(inBytes); b.release()
        ctx.write(Unpooled.wrappedBuffer(cipher.update(inBytes)), promise)
    }
}

/**
 * Debug (`debug: dump-client-stream: <dir>` in fission.yml): writes every byte exchanged with each client to
 * `<dir>/client-<n>-s2c.bin` (server to client) and `-c2s.bin`. Offline mode only (online mode sees encrypted bytes).
 * A backend replays an s2c file through vanilla's client decoders: `gradlew :backend:run -PcheckStream=<file>`.
 */
class StreamDump(dir: java.nio.file.Path, n: Int) : io.netty.channel.ChannelDuplexHandler() {
    // buffered, flushed once a second and on close: a synchronous file write per packet on the netty loop made relayed play visibly slow
    private val s2c = java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("client-$n-s2c.bin")), 1 shl 16)
    private val c2s = java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("client-$n-c2s.bin")), 1 shl 16)
    private fun copy(msg: Any, out: java.io.OutputStream) { val b = msg as? ByteBuf ?: return; val a = ByteArray(b.readableBytes()); b.getBytes(b.readerIndex(), a); out.write(a) }
    override fun handlerAdded(ctx: ChannelHandlerContext) { ctx.executor().scheduleAtFixedRate({ runCatching { s2c.flush(); c2s.flush() } }, 1, 1, java.util.concurrent.TimeUnit.SECONDS) }
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { copy(msg, c2s); ctx.fireChannelRead(msg) }
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) { copy(msg, s2c); ctx.write(msg, promise) }
    override fun channelInactive(ctx: ChannelHandlerContext) { s2c.close(); c2s.close(); ctx.fireChannelInactive() }
}
