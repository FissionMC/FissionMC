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
    /** X25519 key of this link (only with link security): the link is encrypted with keys both sides derive from the two ephemeral keys. */
    private val eph: java.security.KeyPair? = if (security != null) LinkCrypto.newEphemeral() else null
    private val myEph: ByteArray get() = eph?.public?.encoded ?: ByteArray(0)

    /** From here on every frame in both directions is encrypted and authenticated. [context] = the signed handshake transcript. */
    private fun encrypt(ctx: ChannelHandlerContext, peerEph: ByteArray, context: ByteArray) {
        val e = eph ?: return
        val (i2a, a2i) = LinkCrypto.deriveKeys(e.private, peerEph, context)
        ctx.pipeline().addAfter("frame-dec", "crypt-in", LinkDecrypt(if (initiator) a2i else i2a))
        ctx.pipeline().addAfter("frame-enc", "crypt-out", LinkEncrypt(if (initiator) i2a else a2i))
    }

    /** The peer is authenticated: full size frames are allowed now. */
    private fun authenticated(ctx: ChannelHandlerContext) { (ctx.pipeline().get("frame-dec") as? LinkFrameDecoder)?.limit = MAX_FRAME + 64 }

    override fun handlerAdded(ctx: ChannelHandlerContext) { channel = ctx.channel() }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (initiator) ctx.writeAndFlush(Hello(PROTOCOL_VERSION, me.nodeId, me.role, me.mcVersion, myNonce, myEph))
        // a peer that does not finish the handshake in time is dropped (a connection that only sits there, or only pings, must not stay for ever)
        ctx.executor().schedule({ if (!ready.isDone && channel.isActive) reject(ctx, "handshake not finished within ${HANDSHAKE_TIMEOUT_MS} ms") }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
                if (security != null && !security.knows(msg.nodeId)) return reject(ctx, "unknown node ${msg.nodeId}: add the line '${msg.nodeId} <public key>' to the master's authorized keys file - ${security.authorizedHint()} (the backend prints that exact line at its start; gradlew :master:nodeKeys does the same)")
                if (security != null && msg.ephemeral.isEmpty()) return reject(ctx, "node ${msg.nodeId} sent no link key: it runs an older FissionMC or has node.security off (update it / turn security on)")
                peerHello = msg
                val sig = security?.sign(handshakeTranscript(msg.nodeId, me.nodeId, msg.nonce, myNonce, msg.ephemeral, myEph, "acceptor")) ?: ByteArray(0)
                val ch = Challenge(me.nodeId, myNonce, sig, myEph)
                myChallenge = ch
                ctx.writeAndFlush(ch)
            }
            is Challenge -> {
                if (!initiator || peerChallenge != null) return reject(ctx, "unexpected Challenge")
                if (security != null &&
                    !security.verify(msg.nodeId, handshakeTranscript(me.nodeId, msg.nodeId, myNonce, msg.nonce, myEph, msg.ephemeral, "acceptor"), msg.signature))
                    return reject(ctx, "acceptor ${msg.nodeId} failed authentication")
                if (security != null && msg.ephemeral.isEmpty()) return reject(ctx, "acceptor ${msg.nodeId} sent no link key: it runs an older FissionMC (update it)")
                peerChallenge = msg
                val transcript = handshakeTranscript(me.nodeId, msg.nodeId, myNonce, msg.nonce, myEph, msg.ephemeral, "initiator")
                val sig = security?.sign(transcript) ?: ByteArray(0)
                ctx.writeAndFlush(Auth(sig))
                // the acceptor is authenticated; everything after Auth is encrypted (its Welcome too)
                if (security != null) { encrypt(ctx, msg.ephemeral, transcript); authenticated(ctx) }
            }
            is Auth -> {
                val hello = peerHello
                val ch = myChallenge
                if (initiator || hello == null || ch == null || ready.isDone) return reject(ctx, "unexpected Auth")
                val transcript = handshakeTranscript(hello.nodeId, me.nodeId, hello.nonce, myNonce, hello.ephemeral, myEph, "initiator")
                if (security != null) encrypt(ctx, hello.ephemeral, transcript)   // the initiator encrypts everything after its Auth; our answer (Welcome or the refusal) is encrypted as well
                if (security != null && !security.verify(hello.nodeId, transcript, msg.signature))
                    return reject(ctx, "node ${hello.nodeId} failed authentication")
                authenticated(ctx)
                val session = sessionIds.incrementAndGet()
                ctx.writeAndFlush(Welcome(PROTOCOL_VERSION, me.nodeId, me.role, session))
                ctx.channel().attr(PEER_ATTR).set(hello.nodeId); ready.complete(PeerInfo(hello.nodeId, hello.role, session, hello.mcVersion))
            }
            is Welcome -> {
                val ch = peerChallenge
                if (!initiator || ch == null || ready.isDone) return reject(ctx, "unexpected Welcome")
                if (msg.protocol != PROTOCOL_VERSION) return reject(ctx, "protocol ${msg.protocol} != $PROTOCOL_VERSION")
                if (msg.nodeId != ch.nodeId) return reject(ctx, "node id changed during handshake")
                authenticated(ctx)
                ctx.channel().attr(PEER_ATTR).set(msg.nodeId); ready.complete(PeerInfo(msg.nodeId, msg.role, msg.sessionId, null))
            }
            is Ping -> if (ready.isDone) ctx.writeAndFlush(Pong(msg.seq, msg.sentNanos)) else reject(ctx, "ping before handshake")
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

    override fun send(msg: NodeMessage) {
        // a peer that stops reading must not make this node buffer without limit: past the cap the link is dropped (it reconnects and resyncs)
        if (channel.bytesBeforeWritable() > MAX_PENDING_BYTES) { if (overflowed.compareAndSet(false, true)) { closed.complete("local: send buffer over ${MAX_PENDING_BYTES shr 20} MiB (the peer is not reading)"); channel.close() }; return }
        channel.writeAndFlush(msg)
    }
    private val overflowed = java.util.concurrent.atomic.AtomicBoolean()

    override fun close(reason: String) {
        if (!channel.isActive) return
        closed.complete("local: $reason")
        channel.writeAndFlush(Disconnect(reason)).addListener { channel.close() }
    }
}

class LinkClosed(message: String) : RuntimeException(message)

internal const val HANDSHAKE_TIMEOUT_MS = 10_000L
/** Bytes queued towards one peer before the link is given up. */
internal const val MAX_PENDING_BYTES = 256L * 1024 * 1024
