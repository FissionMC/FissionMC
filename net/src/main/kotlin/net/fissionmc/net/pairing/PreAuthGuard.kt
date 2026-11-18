package net.fissionmc.net.pairing

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.AttributeKey
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Resource limits for connections that have not proven who they are (a listener is reachable by anyone). Per remote IP: at most [maxHalfOpen] connections that have not finished the handshake,
 * at most [maxPerMinute] new connections per minute, and after [failLimit] failed handshakes within a minute the address is refused for [banMs]. Limits apply to TCP only; the in-JVM link is not exposed.
 */
class PreAuthGuard(
    val maxHalfOpen: Int = 8, val maxPerMinute: Int = 60, val failLimit: Int = 10, val banMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State { var halfOpen = 0; val attempts = ArrayDeque<Long>(); val fails = ArrayDeque<Long>(); var bannedUntil = 0L; var last = 0L }
    private val states = ConcurrentHashMap<String, State>()
    private val ipKey: AttributeKey<String> = AttributeKey.valueOf("fission.preauth.ip")

    /** True = let the connection in (and count it as half open until [released]). */
    fun admit(ip: String): Boolean {
        val now = clock()
        if (states.size > 20_000) states.entries.removeIf { it.value.halfOpen == 0 && it.value.bannedUntil < now && now - it.value.last > 120_000 }
        val s = states.computeIfAbsent(ip) { State() }
        synchronized(s) {
            s.last = now
            if (now < s.bannedUntil) return false
            while (s.attempts.isNotEmpty() && now - s.attempts.first() > 60_000) s.attempts.removeFirst()
            if (s.halfOpen >= maxHalfOpen) return false
            if (s.attempts.size >= maxPerMinute) return false
            s.attempts.addLast(now); s.halfOpen++
            return true
        }
    }

    fun released(ip: String) { states[ip]?.let { s -> synchronized(s) { if (s.halfOpen > 0) s.halfOpen-- } } }

    fun failed(ip: String) {
        val now = clock(); val s = states.computeIfAbsent(ip) { State() }
        synchronized(s) {
            s.fails.addLast(now); while (s.fails.isNotEmpty() && now - s.fails.first() > 60_000) s.fails.removeFirst()
            if (s.fails.size >= failLimit && now >= s.bannedUntil) { s.bannedUntil = now + banMs; s.fails.clear(); Audit.event("address $ip refused for ${banMs / 1000}s after $failLimit failed handshakes") }
        }
    }

    // --- Netty glue
    private fun ipOf(ch: Channel): String? = (ch.remoteAddress() as? InetSocketAddress)?.address?.hostAddress

    /** First handler of an accepted TCP channel: counts the connection, closes it at once when over a limit. */
    internal fun gate(): ChannelInboundHandlerAdapter = object : ChannelInboundHandlerAdapter() {
        override fun channelActive(ctx: ChannelHandlerContext) {
            val ip = ipOf(ctx.channel())
            if (ip != null) {
                if (!admit(ip)) { Audit.failure("limit:$ip", "connection from $ip refused (too many unauthenticated or recent connections)"); ctx.close(); return }
                ctx.channel().attr(ipKey).set(ip)
            }
            super.channelActive(ctx)
        }
        override fun channelInactive(ctx: ChannelHandlerContext) { release(ctx.channel()); super.channelInactive(ctx) }
    }

    private fun release(ch: Channel) { ch.attr(ipKey).getAndSet(null)?.let(::released) }
    /** The peer finished the handshake: it is not half open any more. */
    internal fun authenticated(ch: Channel) = release(ch)
    /** The handshake failed on this channel. */
    internal fun rejected(ch: Channel) { ipOf(ch)?.let(::failed) }
    internal fun remote(ch: Channel): String = ipOf(ch) ?: "local"
}
