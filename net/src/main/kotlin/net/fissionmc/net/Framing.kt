package net.fissionmc.net

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import net.fissionmc.protocol.MessageRegistry
import net.fissionmc.protocol.NodeMessage

internal const val MAX_FRAME = 8 * 1024 * 1024

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
