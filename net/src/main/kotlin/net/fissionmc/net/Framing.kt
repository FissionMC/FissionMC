package net.fissionmc.net

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import net.fissionmc.protocol.MessageRegistry
import net.fissionmc.protocol.NodeMessage

/** Frame limit until the handshake is done: a peer that has not proven who it is cannot make this node buffer megabytes. */
internal const val PREAUTH_FRAME = 4096

/** 4 byte length prefix; [limit] starts small and is raised once the peer is authenticated. */
internal class LinkFrameDecoder(@Volatile var limit: Int) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        if (buf.readableBytes() < 4) return
        val len = buf.getInt(buf.readerIndex())
        if (len < 0 || len > limit) throw io.netty.handler.codec.TooLongFrameException("node link frame of $len bytes (limit $limit)")
        if (buf.readableBytes() < 4 + len) return
        buf.skipBytes(4); out.add(buf.readRetainedSlice(len))
    }
}

/** Decrypts each frame (sits right behind [LinkFrameDecoder]). A frame that fails authentication closes the link through exceptionCaught. */
internal class LinkDecrypt(key: ByteArray) : io.netty.channel.ChannelInboundHandlerAdapter() {
    private val open = LinkCrypto.Sealer(key, javax.crypto.Cipher.DECRYPT_MODE)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val b = msg as ByteBuf
        val data = try { ByteArray(b.readableBytes()).also { b.readBytes(it) } } finally { b.release() }
        ctx.fireChannelRead(io.netty.buffer.Unpooled.wrappedBuffer(open.apply(data)))
    }
}

/** Encrypts each encoded message before the length prefix is added. */
internal class LinkEncrypt(key: ByteArray) : io.netty.channel.ChannelOutboundHandlerAdapter() {
    private val seal = LinkCrypto.Sealer(key, javax.crypto.Cipher.ENCRYPT_MODE)
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: io.netty.channel.ChannelPromise) {
        val b = msg as? ByteBuf
        if (b == null) { ctx.write(msg, promise); return }
        val data = try { ByteArray(b.readableBytes()).also { b.readBytes(it) } } finally { b.release() }
        ctx.write(io.netty.buffer.Unpooled.wrappedBuffer(seal.apply(data)), promise)
    }
}

/** The node id of the peer on this channel, set when the handshake finished (only read by the flight recorder). */
internal val PEER_ATTR: io.netty.util.AttributeKey<String> = io.netty.util.AttributeKey.valueOf("fission.peer")

/** Link level flight recorder line: message class, wire id, size on the wire, the peer. Pings and the per-packet relay frames (traced as `pkt.*` with more detail) are left out. */
internal fun traceLink(dir: String, ctx: ChannelHandlerContext, id: Int, msg: NodeMessage, size: Int) {
    if (msg is net.fissionmc.protocol.Ping || msg is net.fissionmc.protocol.Pong || msg is net.fissionmc.protocol.PlayerPacketToBackend || msg is net.fissionmc.protocol.PlayerPacketToClient) return
    net.fissionmc.net.trace.Trace.emit(dir) { str("msg", msg.javaClass.simpleName); int("id", id); int("size", size); str("peer", ctx.channel().attr(PEER_ATTR).get()) }
}

internal class MessageEncoder(private val registry: MessageRegistry) : MessageToByteEncoder<NodeMessage>() {
    override fun encode(ctx: ChannelHandlerContext, msg: NodeMessage, out: ByteBuf) {
        val start = out.writerIndex()
        registry.encode(msg, out)
        if (net.fissionmc.net.trace.Trace.on) traceLink("link.out", ctx, registry.idOf(msg), msg, out.writerIndex() - start)
    }
}

/** Sits behind a length-field decoder, so each [ByteBuf] it sees is exactly one frame. */
internal class MessageDecoder(private val registry: MessageRegistry) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        val size = buf.readableBytes()
        val m = registry.decode(buf)
        if (net.fissionmc.net.trace.Trace.on) traceLink("link.in", ctx, registry.idOf(m), m, size)
        out.add(m)
        check(!buf.isReadable) { "Trailing bytes after message" }
    }
}
