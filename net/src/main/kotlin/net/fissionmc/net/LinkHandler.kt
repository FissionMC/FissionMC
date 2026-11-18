package net.fissionmc.net

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import net.fissionmc.net.pairing.Audit
import net.fissionmc.net.pairing.Fingerprint
import net.fissionmc.net.pairing.PairingCode
import net.fissionmc.net.pairing.PairingCrypto
import net.fissionmc.net.pairing.PendingPairing
import net.fissionmc.net.pairing.PreAuthGuard
import net.fissionmc.protocol.*
import java.security.MessageDigest
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
    private val guard: PreAuthGuard? = null,
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
    private var helloOut: Hello? = null       // initiator: what we said
    private var pairSession: net.fissionmc.net.pairing.PairingClient.Session? = null   // initiator: pairing mode (first contact) with this one-time secret
    private var pairedMaster: java.security.PublicKey? = null                           // initiator: the master key proven in pairing mode, pinned once the Welcome arrives
    private var peerId: String? = null        // initiator: the acceptor's id
    private var pairPending: PendingPairing? = null                                     // acceptor: the pairing this link is using
    private class PairState(val keys: PairingCrypto.MacKeys, val macA: ByteArray, val masterPub: ByteArray)
    private var pairState: PairState? = null
    private var signedWithPrevious = false    // acceptor: answered with the master's previous key (the backend still pins it)
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
    private fun authenticated(ctx: ChannelHandlerContext) { (ctx.pipeline().get("frame-dec") as? LinkFrameDecoder)?.limit = Limits.own().frame + 64; guard?.authenticated(ctx.channel()) }

    override fun handlerAdded(ctx: ChannelHandlerContext) { channel = ctx.channel() }

    private fun params(h: Hello, serverId: String, serverNonce: ByteArray, serverEph: ByteArray) =
        HandshakeParams(h.protocol, h.mode, h.mcVersion, h.masterHint, h.nodeId, serverId, h.nonce, serverNonce, h.ephemeral, serverEph)

    /** Per link state of the large message layer (a channel attribute set by the transport; null in hand built pipelines, which then have no fragmentation). */
    private val state: LinkState? get() = channel.attr(STATE_ATTR).get()
    /** First message after the handshake, both ways: the caps this node accepts. The peer holds back everything else until it has them. */
    private fun announceLimits(ctx: ChannelHandlerContext) { if (state != null) ctx.writeAndFlush(LinkLimits(Limits.own())) }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (initiator) {
            // first contact (no pinned master key, or the master refused us as unknown): pairing mode with a one-time secret; otherwise normal mode, telling the master which key we pinned
            val pc = security?.pairingClient
            pairSession = if (pc != null && pc.wanted()) pc.session() else null
            val hint = security?.trustedKey(NodeSupport.MASTER_ID)?.let(Fingerprint::hint) ?: ByteArray(0)
            val h = Hello(PROTOCOL_VERSION, me.nodeId, me.role, me.mcVersion, myNonce, myEph, if (pairSession != null) HELLO_PAIRING else HELLO_NORMAL, hint)
            helloOut = h
            ctx.writeAndFlush(h)
        }
        // a peer that does not finish the handshake in time is dropped (a connection that only sits there, or only pings, must not stay for ever)
        ctx.executor().schedule({ if (!ready.isDone && channel.isActive) reject(ctx, "handshake not finished within ${HANDSHAKE_TIMEOUT_MS} ms") }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // Both sides run the watchdog; only the initiator pings. Acceptor answers pongs, so it also sees traffic.
        heartbeat = ctx.executor().scheduleWithFixedDelay({
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
                if (msg.mcVersion != me.mcVersion) return reject(ctx, "mc version ${Audit.clean(msg.mcVersion, 32)} != ${me.mcVersion}")
                if (!PairingCode.NODE_ID.matches(msg.nodeId)) return reject(ctx, "invalid node id '${Audit.clean(msg.nodeId, 64)}'", NOT_AUTHORIZED)
                if (msg.mode != HELLO_NORMAL && msg.mode != HELLO_PAIRING) return reject(ctx, "unknown handshake mode ${msg.mode}")
                if (security != null && msg.ephemeral.isEmpty()) return reject(ctx, "node ${msg.nodeId} sent no link key: it has node.security off (or runs an older FissionMC) but this master requires link security")
                if (msg.mode == HELLO_PAIRING) return helloPairing(ctx, msg)
                if (security != null && !security.knows(msg.nodeId)) return reject(ctx, "unknown node ${msg.nodeId}: authorize it with `node add <code>` on the master console - ${security.authorizedHint()}", NOT_AUTHORIZED)
                peerHello = msg
                val signer = security?.signerFor(msg.masterHint)
                signedWithPrevious = signer?.isPrevious == true
                val sig = if (security != null && signer != null) security.sign(handshakeTranscript(params(msg, me.nodeId, myNonce, myEph), "acceptor"), signer.priv) else ByteArray(0)
                val ch = Challenge(me.nodeId, myNonce, sig, myEph, signer?.pub?.encoded ?: ByteArray(0))
                myChallenge = ch
                ctx.writeAndFlush(ch)
            }
            is Challenge -> {
                val hello = helloOut
                if (!initiator || peerChallenge != null || hello == null) return reject(ctx, "unexpected Challenge")
                if (pairSession != null) return reject(ctx, "this backend asked for pairing but the master answered with a normal handshake: it does not know the pairing secret, refusing")
                if (security != null) {
                    val pinned = security.trustedKey(msg.nodeId)
                        ?: return reject(ctx, "no master key is pinned for ${msg.nodeId}: this backend has to be paired first (see the pairing code in its console)")
                    if (msg.publicKey.isNotEmpty() && !MessageDigest.isEqual(pinned.encoded, msg.publicKey)) return reject(ctx, keyMismatch(pinned, msg.publicKey))
                    if (!security.verify(pinned, handshakeTranscript(params(hello, msg.nodeId, msg.nonce, msg.ephemeral), "acceptor"), msg.signature))
                        return reject(ctx, "acceptor ${msg.nodeId} failed authentication: its signature does not verify with the pinned master key ${Fingerprint.of(pinned)}")
                    if (msg.ephemeral.isEmpty()) return reject(ctx, "acceptor ${msg.nodeId} sent no link key: it runs an older FissionMC (update it)")
                }
                peerChallenge = msg; peerId = msg.nodeId
                val transcript = handshakeTranscript(params(hello, msg.nodeId, msg.nonce, msg.ephemeral), "initiator")
                val sig = security?.sign(transcript) ?: ByteArray(0)
                ctx.writeAndFlush(Auth(sig))
                // the acceptor is authenticated; everything after Auth is encrypted (its Welcome too)
                if (security != null) { encrypt(ctx, msg.ephemeral, transcript); authenticated(ctx) }
            }
            is PairChallenge -> {
                val hello = helloOut; val session = pairSession; val sec = security
                if (!initiator || hello == null || session == null || sec == null || peerId != null) return reject(ctx, "unexpected PairChallenge")
                if (msg.nodeId != NodeSupport.MASTER_ID) return reject(ctx, "pairing answer from '${Audit.clean(msg.nodeId, 64)}', expected the master")
                if (msg.ephemeral.isEmpty()) return reject(ctx, "the master sent no link key")
                val tA = handshakeTranscript(params(hello, msg.nodeId, msg.nonce, msg.ephemeral), "pair-acceptor", msg.masterPublic)
                val keys = PairingCrypto.macKeys(session.secret, me.nodeId)
                // 1. the answer proves knowledge of the one-time secret: nobody who did not see the console code can produce it
                if (!PairingCrypto.equal(PairingCrypto.mac(keys.acceptor, tA, msg.signature), msg.mac))
                    return reject(ctx, "pairing failed: the answer does not prove the pairing secret. Wrong or expired code pasted on the master, or the address node.master points to is not your master. Nothing was pinned.")
                // 2. proof of possession of the key it claims
                val masterKey = try { Keys.decodePublicRaw(msg.masterPublic) } catch (e: Exception) { return reject(ctx, "the master sent an invalid public key") }
                if (!sec.verify(masterKey, tA, msg.signature)) return reject(ctx, "pairing failed: the master's signature does not verify with its own key")
                // 3. a pinned key (re-pairing) must be the same key
                sec.trustedKey(NodeSupport.MASTER_ID)?.let { pinned -> if (!MessageDigest.isEqual(pinned.encoded, masterKey.encoded)) return reject(ctx, keyMismatch(pinned, masterKey.encoded)) }
                peerId = msg.nodeId; pairedMaster = masterKey
                val tI = handshakeTranscript(params(hello, msg.nodeId, msg.nonce, msg.ephemeral), "pair-initiator", msg.masterPublic + msg.mac)
                val sig = sec.sign(tI); val mac = PairingCrypto.mac(keys.initiator, tI, sig)
                ctx.writeAndFlush(PairAuth(sig, mac))
                encrypt(ctx, msg.ephemeral, tI + mac); authenticated(ctx)
            }
            is Auth -> {
                val hello = peerHello
                val ch = myChallenge
                if (initiator || hello == null || ch == null || ready.isDone) return reject(ctx, "unexpected Auth")
                val transcript = handshakeTranscript(params(hello, me.nodeId, myNonce, myEph), "initiator")
                if (security != null) encrypt(ctx, hello.ephemeral, transcript)   // the initiator encrypts everything after its Auth; our answer (Welcome or the refusal) is encrypted as well
                if (security != null && !security.verify(hello.nodeId, transcript, msg.signature))
                    return reject(ctx, "node ${hello.nodeId} failed authentication: its signature does not verify with the authorized key", NOT_AUTHORIZED)
                admit(ctx, hello)
            }
            is PairAuth -> {
                val hello = peerHello; val p = pairPending; val st = pairState; val sec = security; val store = sec?.pairingStore
                if (initiator || hello == null || p == null || st == null || sec == null || store == null || ready.isDone) return reject(ctx, "unexpected PairAuth")
                val tI = handshakeTranscript(params(hello, me.nodeId, myNonce, myEph), "pair-initiator", st.masterPub + st.macA)
                encrypt(ctx, hello.ephemeral, tI + msg.mac)
                val okSig = sec.verify(p.publicKey, tI, msg.signature)
                val okMac = PairingCrypto.equal(PairingCrypto.mac(st.keys.initiator, tI, msg.signature), msg.mac)
                if (!okSig || !okMac) return reject(ctx, "pairing proof of ${hello.nodeId} is wrong (key signature ${if (okSig) "ok" else "BAD"}, secret proof ${if (okMac) "ok" else "BAD"})", NOT_AUTHORIZED)
                if (!store.consume(p)) return reject(ctx, "pairing of ${hello.nodeId} was used or expired in the meantime", NO_PENDING)
                Audit.event("paired node ${hello.nodeId} key ${Fingerprint.of(p.publicKey)} from ${guard?.remote(channel) ?: "local"}; the one-time secret is used up")
                admit(ctx, hello)
            }
            is Welcome -> {
                val id = peerId
                if (!initiator || id == null || ready.isDone) return reject(ctx, "unexpected Welcome")
                if (msg.protocol != PROTOCOL_VERSION) return reject(ctx, "protocol ${msg.protocol} != $PROTOCOL_VERSION")
                if (msg.nodeId != id) return reject(ctx, "node id changed during handshake")
                pairedMaster?.let { m -> try { security?.pairingClient?.completed(m) } catch (e: Exception) { return reject(ctx, "paired, but the master key could not be saved: ${e.message}") } }
                authenticated(ctx)
                announceLimits(ctx); ctx.channel().attr(PEER_ATTR).set(msg.nodeId); ready.complete(PeerInfo(msg.nodeId, msg.role, msg.sessionId, null))
            }
            is Ping -> if (ready.isDone) ctx.writeAndFlush(Pong(msg.seq, msg.sentNanos)) else reject(ctx, "ping before handshake")
            is Pong -> latencyNanos = System.nanoTime() - msg.sentNanos
            is LinkLimits -> if (ready.isDone && state?.peer == null) state?.peerKnown(msg.limits) else reject(ctx, "unexpected LinkLimits")
            is Disconnect -> {
                if (initiator && !ready.isDone) security?.pairingClient?.let { pc ->
                    if (msg.reason.startsWith(NOT_AUTHORIZED)) { pc.requestPairing(); pc.session() }   // removed, or never added: print a (new) pairing code the admin can paste
                    else if (msg.reason.startsWith(NO_PENDING)) pc.dropRequest()
                }
                closed.complete("remote: ${msg.reason}"); ready.completeExceptionally(LinkClosed(msg.reason)); ctx.close() }
            else -> if (ready.isDone) {
                // a handler that throws on one message must not take the whole link (and every player on it) down
                try { handler(msg) } catch (t: Exception) { handlerErrors++; java.util.logging.Logger.getLogger("FissionMC").log(java.util.logging.Level.WARNING, "[link] handler failed on ${msg.javaClass.simpleName}: $t (the link stays up)", t) }
            } else reject(ctx, "message before handshake")
        }
    }

    /** The acceptor's last step: the peer is proven, the link is up. */
    private fun admit(ctx: ChannelHandlerContext, hello: Hello) {
        authenticated(ctx)
        val session = sessionIds.incrementAndGet()
        ctx.writeAndFlush(Welcome(PROTOCOL_VERSION, me.nodeId, me.role, session))
        announceLimits(ctx); ctx.channel().attr(PEER_ATTR).set(hello.nodeId); ready.complete(PeerInfo(hello.nodeId, hello.role, session, hello.mcVersion, signedWithPrevious))
    }

    /** First contact as the acceptor: the node's key was authorized by `node add`, its pairing secret is pending. */
    private fun helloPairing(ctx: ChannelHandlerContext, msg: Hello) {
        val sec = security ?: return reject(ctx, "node ${msg.nodeId} asked for pairing but this side has no link security (node.security: false)")
        val store = sec.pairingStore
        if (store == null || !store.enabled) return reject(ctx, "pairing request of ${msg.nodeId} refused: pairing is disabled (node.pairing: false)", "pairing is disabled on this master")
        val p = store.pendingFor(msg.nodeId)
            ?: return reject(ctx, "pairing request of ${msg.nodeId}, but no pairing is pending for it (paste its code with `node add <code>`; codes expire and work once)", NO_PENDING)
        val master = sec.publicKey ?: return reject(ctx, "this node has no public key to pair with")
        peerHello = msg; pairPending = p
        val tA = handshakeTranscript(params(msg, me.nodeId, myNonce, myEph), "pair-acceptor", master.encoded)
        val sig = sec.sign(tA)
        val keys = PairingCrypto.macKeys(p.secret, msg.nodeId)
        val mac = PairingCrypto.mac(keys.acceptor, tA, sig)
        pairState = PairState(keys, mac, master.encoded)
        ctx.writeAndFlush(PairChallenge(me.nodeId, myNonce, myEph, master.encoded, sig, mac))
    }

    private fun keyMismatch(pinned: java.security.PublicKey, presented: ByteArray) =
        "MASTER KEY MISMATCH: the master answering presented key ${Fingerprint.of(presented)} but this backend pinned ${Fingerprint.of(pinned)}. Either the master's key was replaced without telling this backend " +
            "(delete .fission-master-key to pair again) or someone is impersonating the master. NOT connecting."

    /** [why] is for our own log; [peerReason] is what the peer is told (nothing that tells an unauthenticated peer which node ids exist or why exactly it failed). */
    private fun reject(ctx: ChannelHandlerContext, why: String, peerReason: String = why) {
        if (!initiator && !ready.isDone) { guard?.rejected(channel); Audit.failure(guard?.remote(channel) ?: "local", "handshake from ${guard?.remote(channel) ?: "local"}: ${Audit.clean(why, 200)}") }
        ready.completeExceptionally(LinkClosed(why))
        ctx.writeAndFlush(Disconnect(peerReason.take(250))).addListener { ctx.close() }
        closed.complete("rejected: $why")
    }

    /** Messages whose handler threw (the link was kept). */
    @Volatile var handlerErrors = 0L; private set
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null   // cancelled with the connection: the event loop outlives it and would keep the handler alive

    override fun channelInactive(ctx: ChannelHandlerContext) {
        heartbeat?.cancel(false)
        closed.complete("connection lost")
        ready.completeExceptionally(LinkClosed("connection lost"))
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        closed.complete("error: $cause")
        val wasReady = ready.isDone && !ready.isCompletedExceptionally
        ready.completeExceptionally(cause)
        // an authenticated peer that broke a limit is told why before the link is closed (the reason shows in its log)
        if (wasReady && channel.isActive) ctx.writeAndFlush(Disconnect((cause.message ?: cause.toString()).take(250))).addListener { ctx.close() } else ctx.close()
    }

    override fun send(msg: NodeMessage) {
        // a peer that stops reading must not make this node buffer without limit: past the cap the link is dropped (it reconnects and resyncs)
        val maxPending = LimitsConfig.active.maxPendingSend
        if (channel.bytesBeforeWritable() + (state?.pendingBytes ?: 0L) > maxPending) { if (overflowed.compareAndSet(false, true)) { closed.complete("local: send buffer over ${maxPending shr 20} MiB (the peer is not reading)"); channel.close() }; return }
        channel.writeAndFlush(msg).addListener { f -> reportSendFailure(msg, f.cause()) }
    }

    override fun sendTracked(msg: NodeMessage, done: (Throwable?) -> Unit) {
        val maxPending = LimitsConfig.active.maxPendingSend
        if (channel.bytesBeforeWritable() + (state?.pendingBytes ?: 0L) > maxPending) { done(LinkLimitException("send buffer over ${maxPending shr 20} MiB")); return }
        channel.writeAndFlush(msg).addListener { f -> reportSendFailure(msg, f.cause()); done(f.cause()) }
    }
    override val peerLimits: Limits? get() = state?.peer?.min(Limits.own())

    /** A message that exceeds a limit is not sent; this says so (the link stays up). Other failures are connection loss, reported by the link closing. */
    private fun reportSendFailure(msg: NodeMessage, cause: Throwable?) {
        if (cause is LinkLimitException && cause.message != "link closed")
            java.util.logging.Logger.getLogger("FissionLink").warning("[fission] link to ${channel.attr(PEER_ATTR).get()}: ${cause.message}")
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
/** What a peer is told when it is refused for who it is (unknown id, bad signature, bad pairing proof): the same words for all, the details go to the master's log only. */
const val NOT_AUTHORIZED = "not authorized: the master did not accept this node (the master's console log says why)"
const val NO_PENDING = "no pending pairing for this node on the master (code not pasted yet, expired or already used)"
