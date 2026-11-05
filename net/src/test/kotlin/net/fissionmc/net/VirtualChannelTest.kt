package net.fissionmc.net

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VirtualChannelTest {
    private val addr = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 1, 2, 3)), 50505)

    /** Mirrors vanilla's Connection.channelActive: it copies channel.remoteAddress() ONCE, while the channel is being constructed. */
    private class Capturing : ChannelInboundHandlerAdapter() {
        var seenAtActive: Any? = "unset"
        override fun channelActive(ctx: ChannelHandlerContext) { seenAtActive = ctx.channel().remoteAddress(); ctx.fireChannelActive() }
    }

    @Test fun `remote address is visible when channelActive fires and afterwards`() {
        val h = Capturing()
        val ch = VirtualChannel.create(addr, object : ChannelInitializer<Channel>() { override fun initChannel(c: Channel) { c.pipeline().addLast(h) } })
        assertNotNull(h.seenAtActive, "address must not be null in channelActive (vanilla Connection.address, IpBanList.isBanned)")
        assertEquals(addr, h.seenAtActive)
        assertEquals(addr, ch.remoteAddress())
        assertEquals("10.1.2.3", (ch.remoteAddress() as InetSocketAddress).address.hostAddress)
    }

    @Test fun `channels do not leak the address into each other`() {
        val other = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 9, 9, 9)), 1)
        val a = VirtualChannel.create(addr, object : ChannelInitializer<Channel>() { override fun initChannel(c: Channel) {} })
        val b = VirtualChannel.create(other, object : ChannelInitializer<Channel>() { override fun initChannel(c: Channel) {} })
        assertEquals(addr, a.remoteAddress()); assertEquals(other, b.remoteAddress())
    }
}
