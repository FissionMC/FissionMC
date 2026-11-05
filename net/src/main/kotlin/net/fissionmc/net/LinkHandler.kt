package net.fissionmc.net

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import net.fissionmc.protocol.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal val sessionIds = AtomicLong(System.nanoTime())

/**
 * Handshake + heartbeat + dispatch. One per channel; also the [NodeLink] handed to users.
 * Initiator sends [Hello]; acceptor answers [Welcome]. Heartbeat: initiator pings, silence > [timeoutMs] drops the link.
 */
internal class LinkHandler(
    private val me: LocalNode,
    private val initiator: Boolean,
    private val heartbeatMs: Long,
    private val timeoutMs: Long,
    private val security: NodeSecurity?,
) : SimpleChannelInboundHandler<NodeMessage>(), NodeLink {
    private lateinit var channel: Channel
    override val ready = CompletableFuture<PeerInfo>()
    override val closed = CompletableFuture<String>()
    @Volatile override var latencyNanos = -1L
    @Volatile override var handler: (NodeMessage) -> Unit = {}
    override val isOpen get() = channel.isActive
    override val isWritable get() = channel.isWritable
    @Volatile private var lastRecv = System.nanoTime()
    private var seq = 0L

    // handshake state
    private val myNonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    private var peerHello: Hello? = null      // acceptor: what the initiator said
    private var peerChallenge: Challenge? = null // initiator: what the acceptor said
    private var myChallenge: Challenge? = null   // acceptor: what we sent

    override fun handlerAdded(ctx: ChannelHandlerContext) { channel = ctx.channel() }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (initiator) ctx.writeAndFlush(Hello(PROTOCOL_VERSION, me.nodeId, me.role, me.mcVersion, myNonce))
        // Both sides run the watchdog; only the initiator pings. Acceptor answers pongs, so it also sees traffic.
        ctx.executor().scheduleWithFixedDelay({
            if (!channel.isActive) return@scheduleWithFixedDelay
            if (System.nanoTime() - lastRecv > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) close("timed out")
            else if (initiator && ready.isDone && !ready.isCompletedExceptionally) send(Ping(seq++, System.nanoTime()))
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS)
        super.channelActive(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: NodeMessage) {
        lastRecv = System.nanoTime()
        when (msg) {
            is Hello -> {
                if (initiator || peerHello != null) return reject(ctx, "unexpected Hello")
                if (msg.protocol != PROTOCOL_VERSION) return reject(ctx, "protocol ${msg.protocol} != $PROTOCOL_VERSION")
                if (msg.mcVersion != me.mcVersion) return reject(ctx, "mc version ${msg.mcVersion} != ${me.mcVersion}")
                if (security != null && !security.knows(msg.nodeId)) return reject(ctx, "unknown node ${msg.nodeId}")
                peerHello = msg
                val sig = security?.sign(handshakeTranscript(msg.nodeId, me.nodeId, msg.nonce, myNonce, "acceptor")) ?: ByteArray(0)
                val ch = Challenge(me.nodeId, myNonce, sig)
                myChallenge = ch
                ctx.writeAndFlush(ch)
            }
            is Challenge -> {
                if (!initiator || peerChallenge != null) return reject(ctx, "unexpected Challenge")
                if (security != null &&
                    !security.verify(msg.nodeId, handshakeTranscript(me.nodeId, msg.nodeId, myNonce, msg.nonce, "acceptor"), msg.signature))
                    return reject(ctx, "acceptor ${msg.nodeId} failed authentication")
                peerChallenge = msg
                val sig = security?.sign(handshakeTranscript(me.nodeId, msg.nodeId, myNonce, msg.nonce, "initiator")) ?: ByteArray(0)
                ctx.writeAndFlush(Auth(sig))
            }
            is Auth -> {
                val hello = peerHello
                val ch = myChallenge
                if (initiator || hello == null || ch == null || ready.isDone) return reject(ctx, "unexpected Auth")
                if (security != null &&
                    !security.verify(hello.nodeId, handshakeTranscript(hello.nodeId, me.nodeId, hello.nonce, myNonce, "initiator"), msg.signature))
                    return reject(ctx, "node ${hello.nodeId} failed authentication")
                val session = sessionIds.incrementAndGet()
                ctx.writeAndFlush(Welcome(PROTOCOL_VERSION, me.nodeId, me.role, session))
                ready.complete(PeerInfo(hello.nodeId, hello.role, session, hello.mcVersion))
            }
            is Welcome -> {
                val ch = peerChallenge
                if (!initiator || ch == null || ready.isDone) return reject(ctx, "unexpected Welcome")
                if (msg.protocol != PROTOCOL_VERSION) return reject(ctx, "protocol ${msg.protocol} != $PROTOCOL_VERSION")
                if (msg.nodeId != ch.nodeId) return reject(ctx, "node id changed during handshake")
                ready.complete(PeerInfo(msg.nodeId, msg.role, msg.sessionId, null))
            }
            is Ping -> ctx.writeAndFlush(Pong(msg.seq, msg.sentNanos))
            is Pong -> latencyNanos = System.nanoTime() - msg.sentNanos
            is Disconnect -> { closed.complete("remote: ${msg.reason}"); ready.completeExceptionally(LinkClosed(msg.reason)); ctx.close() }
            else -> if (ready.isDone) handler(msg) else reject(ctx, "message before handshake")
        }
    }

    private fun reject(ctx: ChannelHandlerContext, why: String) {
        ready.completeExceptionally(LinkClosed(why))
        ctx.writeAndFlush(Disconnect(why)).addListener { ctx.close() }
        closed.complete("rejected: $why")
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        closed.complete("connection lost")
        ready.completeExceptionally(LinkClosed("connection lost"))
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        closed.complete("error: $cause")
        ready.completeExceptionally(cause)
        ctx.close()
    }

    override fun send(msg: NodeMessage) { channel.writeAndFlush(msg) }

    override fun close(reason: String) {
        if (!channel.isActive) return
        closed.complete("local: $reason")
        channel.writeAndFlush(Disconnect(reason)).addListener { channel.close() }
    }
}

class LinkClosed(message: String) : RuntimeException(message)
