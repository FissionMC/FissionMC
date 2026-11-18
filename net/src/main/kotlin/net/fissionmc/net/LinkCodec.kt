package net.fissionmc.net

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import net.fissionmc.protocol.*
import java.util.concurrent.TimeUnit

/** Per link state shared by [LinkWriter], [LinkReader] and the [LinkHandler] (kept as a channel attribute, so the handlers need no new constructor parameters). */
internal class LinkState {
    /** What the peer accepts; null until its LinkLimits arrived (nothing but handshake messages is written before). */
    @Volatile var peer: Limits? = null
    var writer: LinkWriter? = null
    /** Bytes of fragmented messages waiting to be cut into pieces. */
    @Volatile var pendingBytes = 0L
    fun peerKnown(l: Limits) { peer = l; writer?.peerKnown() }
}
internal val STATE_ATTR: io.netty.util.AttributeKey<LinkState> = io.netty.util.AttributeKey.valueOf("fission.linkstate")

private fun isControl(m: NodeMessage) = m is Hello || m is Challenge || m is Auth || m is Welcome || m is Disconnect || m is LinkLimits || m is PairChallenge || m is PairAuth

/**
 * Encodes messages (replaces the plain encoder) and does the sending half of the large message layer: a message above the fragment threshold leaves as
 * pieces, interleaved with the other messages; everything else is written as one frame. Until the peer's limits are known only handshake messages go out.
 */
internal class LinkWriter(private val registry: MessageRegistry, private val state: LinkState) : ChannelDuplexHandler() {
    private val sched = FragmentScheduler<ChannelPromise>(LimitsConfig.active.fragmentSize)
    private class Deferred(val msg: NodeMessage, val promise: ChannelPromise)
    private val deferred = ArrayList<Deferred>()
    private var ctx: ChannelHandlerContext? = null
    private var pumpScheduled = false

    override fun handlerAdded(ctx: ChannelHandlerContext) { this.ctx = ctx; state.writer = this }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg !is NodeMessage) { ctx.write(msg, promise); return }
        if (state.peer == null && !isControl(msg)) { deferred += Deferred(msg, promise); return }
        send(ctx, msg, promise)
    }

    /** The peer's limits arrived (called on the link's event loop, right after [LinkState.peer] was set): what waited goes out now, before anything written later. */
    fun peerKnown() {
        val c = ctx ?: return
        if (!c.executor().inEventLoop()) { c.executor().execute { peerKnown() }; return }
        val d = ArrayList(deferred); deferred.clear()
        for (x in d) send(c, x.msg, x.promise)
        c.flush()
    }

    private fun send(ctx: ChannelHandlerContext, msg: NodeMessage, promise: ChannelPromise) {
        val peer = state.peer
        val buf: ByteBuf = ctx.alloc().buffer()
        try {
            try { Limits.towards(peer) { registry.encode(msg, buf) } }
            catch (e: IllegalArgumentException) { buf.release(); promise.tryFailure(LinkLimitException("${msg.javaClass.simpleName} not sent: ${e.message}")); return }
            val size = buf.readableBytes()
            if (net.fissionmc.net.trace.Trace.on) traceLink("link.out", ctx, registry.idOf(msg), msg, size)
            val eff = if (peer == null) Limits.own() else Limits.own().min(peer)
            if (size > eff.maxMessage) { buf.release(); promise.tryFailure(LinkLimitException("${msg.javaClass.simpleName} of $size bytes not sent: over the link's max-message ${eff.maxMessage} (own ${Limits.own().maxMessage}, peer ${peer?.maxMessage})")); return }
            val key = (msg as? OrderedMessage)?.orderKey
            val threshold = minOf(LimitsConfig.active.fragmentAbove, eff.frame)
            if (size <= threshold && (key == null || !sched.busy(key))) { ctx.write(buf, promise); return }
            val bytes = ByteArray(size).also { buf.readBytes(it) }; buf.release()
            sched.chunkSize = minOf(LimitsConfig.active.fragmentSize, eff.frame - 1024).coerceAtLeast(1024)   // a peer with a smaller frame limit than ours would close the link on the first piece
            sched.enqueue(bytes, key, promise); state.pendingBytes = sched.pendingBytes
            pump(ctx)
        } catch (e: Throwable) { if (buf.refCnt() > 0) buf.release(); promise.tryFailure(e) }
    }

    /** Writes a few pieces, then gives the event loop back (messages queued meanwhile go out between the pieces); continues when the channel is writable. */
    private fun pump(ctx: ChannelHandlerContext) {
        if (pumpScheduled) return
        pumpScheduled = true
        ctx.executor().execute { pumpScheduled = false; pumpNow(ctx) }
    }

    private fun pumpNow(ctx: ChannelHandlerContext) {
        var n = 0
        while (n < 4 && ctx.channel().isWritable) {
            val p = sched.next() ?: break
            val b = ctx.alloc().buffer(p.fragment.data.size + 32)
            try { registry.encode(p.fragment, b) } catch (e: Throwable) { b.release(); p.stream.token.tryFailure(e); sched.abort(p.stream.id); continue }
            if (p.last) ctx.write(b, p.stream.token) else ctx.write(b)
            n++
        }
        state.pendingBytes = sched.pendingBytes
        if (n > 0) ctx.flush()
        if (!sched.isEmpty && ctx.channel().isWritable) pump(ctx)
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable && !sched.isEmpty) pump(ctx)
        ctx.fireChannelWritabilityChanged()
    }

    /** The receiver refuses stream [id] (budget, timeout): stop sending it and fail the message's promise with the reason. */
    fun peerAborted(id: Long, reason: String) {
        sched.abort(id)?.tryFailure(LinkLimitException("message dropped by the peer: $reason")); state.pendingBytes = sched.pendingBytes
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val gone = LinkLimitException("link closed")
        sched.clear().forEach { it.tryFailure(gone) }; deferred.forEach { it.promise.tryFailure(gone) }; deferred.clear(); state.pendingBytes = 0
        ctx.fireChannelInactive()
    }
}

/** Receiving half: collects the pieces of fragmented messages and hands the reassembled message on as if it had arrived whole. Fragments from a peer that has not announced limits are a violation. */
internal class LinkReader(private val registry: MessageRegistry, private val state: LinkState) : io.netty.channel.ChannelInboundHandlerAdapter() {
    private val cfg = LimitsConfig.active
    private val reasm = Reassembler({ Limits.own().maxMessage }, LimitsConfig.reassemblyBudget, cfg.maxReassemblies, TimeUnit.MILLISECONDS.toNanos(cfg.reassemblyTimeoutMs))
    private var timer: io.netty.util.concurrent.ScheduledFuture<*>? = null

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        timer = ctx.executor().scheduleWithFixedDelay({
            for (id in reasm.expire()) {
                java.util.logging.Logger.getLogger("FissionLink").warning("[fission] fragmented message $id from ${ctx.channel().attr(PEER_ATTR).get()} dropped: not complete within ${cfg.reassemblyTimeoutMs / 1000} s")
                if (ctx.channel().isActive) ctx.channel().writeAndFlush(FragmentAbort(id, true, "not complete within ${cfg.reassemblyTimeoutMs / 1000} s"))
            }
        }, 1, 1, TimeUnit.SECONDS)
    }
    override fun handlerRemoved(ctx: ChannelHandlerContext) { timer?.cancel(false); reasm.close() }
    override fun channelInactive(ctx: ChannelHandlerContext) { reasm.close(); ctx.fireChannelInactive() }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is Fragment -> {
                if (state.peer == null) throw FragmentViolation("fragment before the limits exchange")
                when (val r = reasm.accept(msg)) {
                    is Reassembler.Result.Pending -> {}
                    is Reassembler.Result.Refused -> {
                        java.util.logging.Logger.getLogger("FissionLink").warning("[fission] fragmented message ${r.streamId} from ${ctx.channel().attr(PEER_ATTR).get()} refused: ${r.reason}")
                        ctx.channel().writeAndFlush(FragmentAbort(r.streamId, true, r.reason))
                    }
                    is Reassembler.Result.Done -> {
                        val buf = io.netty.buffer.Unpooled.wrappedBuffer(r.bytes)
                        val m = registry.decode(buf)
                        check(!buf.isReadable) { "Trailing bytes after reassembled message" }
                        if (m is Fragment || m is FragmentAbort || isControl(m)) throw FragmentViolation("a fragmented message must not be a ${m.javaClass.simpleName}")
                        if (net.fissionmc.net.trace.Trace.on) traceLink("link.in", ctx, registry.idOf(m), m, r.bytes.size)
                        ctx.fireChannelRead(m)
                    }
                }
            }
            is FragmentAbort -> {
                if (state.peer == null) throw FragmentViolation("fragment abort before the limits exchange")
                if (msg.byReceiver) state.writer?.peerAborted(msg.streamId, msg.reason) else reasm.abort(msg.streamId)
            }
            else -> ctx.fireChannelRead(msg)
        }
    }
}
