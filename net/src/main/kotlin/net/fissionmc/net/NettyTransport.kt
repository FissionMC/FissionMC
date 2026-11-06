package net.fissionmc.net

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.local.*
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import net.fissionmc.protocol.MessageRegistry
import net.fissionmc.protocol.registerWorldMessages
import net.fissionmc.protocol.registerReplicationMessages
import net.fissionmc.protocol.registerPlayerMessages
import net.fissionmc.protocol.registerChunkMessages
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

/**
 * [Transport] over Netty. Local and Tcp use the exact same pipeline (frame, codec, [LinkHandler]);
 * only the channel type differs, so code above this layer cannot tell them apart.
 */
class NettyTransport(
    private val me: LocalNode,
    private val registry: MessageRegistry = MessageRegistry.core().registerWorldMessages().registerReplicationMessages().registerPlayerMessages().registerChunkMessages(),
    private val heartbeatMs: Long = 5_000,
    private val timeoutMs: Long = 20_000,
    private val security: NodeSecurity? = null,
) : Transport {
    private val tcpGroup = MultiThreadIoEventLoopGroup(0, io.netty.util.concurrent.DefaultThreadFactory("fission-node-tcp", true), NioIoHandler.newFactory())
    private val localGroup = MultiThreadIoEventLoopGroup(1, io.netty.util.concurrent.DefaultThreadFactory("fission-node-local", true), LocalIoHandler.newFactory())

    private fun init(ch: Channel, initiator: Boolean, onLink: (NodeLink) -> Unit) {
        val link = LinkHandler(me, initiator, heartbeatMs, timeoutMs, security)
        ch.pipeline()
            .addLast("frame-dec", LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 4, 0, 4))
            .addLast("frame-enc", LengthFieldPrepender(4))
            .addLast("dec", MessageDecoder(registry))
            .addLast("enc", MessageEncoder(registry))
            .addLast("link", link)
        onLink(link)
    }

    override fun bind(address: NodeAddress, onLink: (NodeLink) -> Unit): Closeable {
        val b = ServerBootstrap()
        val sock = when (address) {
            is NodeAddress.Tcp -> b.group(tcpGroup).channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.TCP_NODELAY, true)   // relayed player packets are small and latency bound: no Nagle
                .localAddress(InetSocketAddress(address.host, address.port))
            is NodeAddress.Local -> b.group(localGroup).channel(LocalServerChannel::class.java)
                .localAddress(LocalAddress(address.name))
        }
        val ch = sock.childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) = init(ch, false, onLink)
        }).bind().syncUninterruptibly().channel()
        return Closeable { ch.close().syncUninterruptibly() }
    }

    override fun connect(address: NodeAddress): CompletableFuture<NodeLink> {
        val result = CompletableFuture<NodeLink>()
        var link: NodeLink? = null
        val b = Bootstrap()
        val handler = object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) = init(ch, true) { link = it }
        }
        val f = when (address) {
            is NodeAddress.Tcp -> b.group(tcpGroup).channel(NioSocketChannel::class.java).option(ChannelOption.TCP_NODELAY, true).handler(handler)
                .connect(InetSocketAddress(address.host, address.port))
            is NodeAddress.Local -> b.group(localGroup).channel(LocalChannel::class.java).handler(handler)
                .connect(LocalAddress(address.name))
        }
        f.addListener { if (it.isSuccess) result.complete(link) else result.completeExceptionally(it.cause()) }
        return result
    }

    override fun close() {
        tcpGroup.shutdownGracefully().syncUninterruptibly()
        localGroup.shutdownGracefully().syncUninterruptibly()
    }
}
