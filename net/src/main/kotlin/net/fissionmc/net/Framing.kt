package net.fissionmc.net

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import net.fissionmc.protocol.MessageRegistry
import net.fissionmc.protocol.NodeMessage

internal const val MAX_FRAME = 8 * 1024 * 1024

internal class MessageEncoder(private val registry: MessageRegistry) : MessageToByteEncoder<NodeMessage>() {
    override fun encode(ctx: ChannelHandlerContext, msg: NodeMessage, out: ByteBuf) = registry.encode(msg, out)
}

/** Sits behind a length-field decoder, so each [ByteBuf] it sees is exactly one frame. */
internal class MessageDecoder(private val registry: MessageRegistry) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        out.add(registry.decode(buf))
        check(!buf.isReadable) { "Trailing bytes after message" }
    }
}
