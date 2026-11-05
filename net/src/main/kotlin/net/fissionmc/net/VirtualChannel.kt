package net.fissionmc.net

import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.embedded.EmbeddedChannel
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * An in-process channel that reports [remote] as its remote address, for connections that were terminated elsewhere (the master) and
 * are replayed into a server (the backend's virtual player connection).
 *
 * The address must be known while the superclass constructor runs: EmbeddedChannel's constructor registers the channel and fires
 * `channelActive`, and vanilla's `Connection.channelActive` copies `channel.remoteAddress()` into its own `address` field once. A Kotlin
 * constructor property would still be null at that moment (the bug that made `Connection.getRemoteAddress()` null, and
 * `IpBanList.isBanned` throw "address is null", reported to the player as "Invalid player data"), hence the thread-local hand-over.
 */
class VirtualChannel private constructor(init: ChannelInitializer<Channel>) : EmbeddedChannel(init) {
    @Volatile private var after: SocketAddress? = null
    override fun remoteAddress0(): SocketAddress? = PENDING.get() ?: after

    companion object {
        private val PENDING = ThreadLocal<InetSocketAddress?>()

        fun create(remote: InetSocketAddress, init: ChannelInitializer<Channel>): VirtualChannel {
            PENDING.set(remote)
            try { return VirtualChannel(init).also { it.after = remote } } finally { PENDING.remove() }
        }
    }
}
