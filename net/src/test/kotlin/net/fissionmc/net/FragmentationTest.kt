package net.fissionmc.net

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import net.fissionmc.protocol.*
import kotlin.test.*

class FragmentationTest {
    private val reg = MessageRegistry.core().registerLimitsMessages().registerWorldMessages()
    private fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }
    private fun split(data: ByteArray, chunk: Int = 1000): List<Fragment> {
        val s = FragmentScheduler<Unit>(chunk); s.enqueue(data, null, Unit)
        return generateSequence { s.next() }.map { it.fragment }.toList()
    }
    private fun reasm(max: Int = 1 shl 20, budget: Long = 4L shl 20, streams: Int = 4, timeoutMs: Long = 1000, clock: () -> Long = System::nanoTime) =
        Reassembler({ max }, ByteBudget(budget), streams, timeoutMs * 1_000_000, clock)
    private fun feed(r: Reassembler, frags: List<Fragment>): ByteArray? { var out: ByteArray? = null; for (f in frags) { val x = r.accept(f); if (x is Reassembler.Result.Done) out = x.bytes }; return out }

    // ---- split / reassemble
    @Test fun `split and reassemble round trip`() {
        for (n in listOf(1, 999, 1000, 1001, 12_345, 250_000)) {
            val d = bytes(n, n); val frags = split(d)
            assertEquals((n + 999) / 1000, frags.size); assertTrue(frags.first().flags and Fragment.FIRST != 0); assertTrue(frags.last().flags and Fragment.LAST != 0)
            assertEquals(n, frags.first().total)
            assertContentEquals(d, feed(reasm(), frags), "size $n")
        }
    }

    @Test fun `fragments survive the wire codec`() {
        val f = split(bytes(5000))[0]; val b = Unpooled.buffer(); reg.encode(f, b)
        val g = reg.decode(b) as Fragment
        assertEquals(f.streamId, g.streamId); assertEquals(f.total, g.total); assertContentEquals(f.data, g.data)
    }

    // ---- interleaving
    @Test fun `pieces of two streams alternate and a small stream finishes first`() {
        val s = FragmentScheduler<String>(100)
        val big = s.enqueue(bytes(1000), null, "big"); val small = s.enqueue(bytes(250), null, "small")
        val order = generateSequence { s.next() }.map { it.stream.token }.toList()
        assertEquals(listOf("big", "small", "big", "small", "big", "small", "big", "big", "big", "big", "big", "big", "big").take(order.size), order)
        assertEquals(10 + 3, order.size); assertNotEquals(big, small)
        assertEquals(0, s.pendingBytes)
    }

    @Test fun `streams with one order key go strictly one after the other`() {
        val s = FragmentScheduler<String>(100)
        s.enqueue(bytes(300), "k", "first"); s.enqueue(bytes(50), "k", "second"); s.enqueue(bytes(300), "other", "free")
        assertTrue(s.busy("k")); assertFalse(s.busy("zzz"))
        val order = generateSequence { s.next() }.map { it.stream.token }.toList()
        assertTrue(order.indexOf("second") > order.lastIndexOf("first"), "second waits for first: $order")
        assertTrue(order.indexOf("free") < order.lastIndexOf("first"), "other keys interleave: $order")
        assertFalse(s.busy("k"))
    }

    @Test fun `aborting the head of a key starts the next one`() {
        val s = FragmentScheduler<String>(100)
        val a = s.enqueue(bytes(300), "k", "a"); s.enqueue(bytes(100), "k", "b")
        assertEquals("a", s.next()!!.stream.token)
        assertEquals("a", s.abort(a)); assertNull(s.abort(a))
        assertEquals("b", s.next()!!.stream.token); assertNull(s.next()); assertEquals(0, s.pendingBytes)
    }

    // ---- reassembly limits
    @Test fun `interleaved streams reassemble independently`() {
        val r = reasm(); val a = bytes(3000, 1); val b = bytes(2500, 2)
        val fa = split(a); val fb = split(b).map { Fragment(it.streamId + 100, it.index, it.flags, it.total, it.data) }
        val got = HashMap<Long, ByteArray>()
        for (i in 0 until maxOf(fa.size, fb.size)) for (f in listOfNotNull(fa.getOrNull(i), fb.getOrNull(i))) (r.accept(f) as? Reassembler.Result.Done)?.let { got[f.streamId] = it.bytes }
        assertContentEquals(a, got[fa[0].streamId]); assertContentEquals(b, got[fb[0].streamId]); assertEquals(0, r.reserved); assertEquals(0, r.active)
    }

    @Test fun `a message over max-message is a violation`() {
        val e = assertFailsWith<FragmentViolation> { reasm(max = 2000).accept(split(bytes(3000))[0]) }
        assertTrue("max-message" in e.message!!)
    }

    @Test fun `memory budget and stream count refuse politely and drop the rest of that stream`() {
        val r = reasm(max = 10_000, budget = 15_000, streams = 2)
        fun f(id: Long, n: Int) = split(bytes(n)).map { Fragment(id, it.index, it.flags, it.total, it.data) }
        assertIs<Reassembler.Result.Pending>(r.accept(f(1, 8000)[0]))
        val refused = r.accept(f(2, 8000)[0]); assertIs<Reassembler.Result.Refused>(refused); assertTrue("budget" in refused.reason)
        for (p in f(2, 8000).drop(1)) assertIs<Reassembler.Result.Pending>(r.accept(p))       // late pieces of the refused stream are dropped silently
        assertEquals(8000, r.reserved)
        assertIs<Reassembler.Result.Pending>(r.accept(f(3, 1500)[0]))
        val third = r.accept(f(4, 100)[0]); assertIs<Reassembler.Result.Refused>(third); assertTrue("max-reassemblies" in third.reason)
    }

    @Test fun `budget is given back when a stream completes`() {
        val r = reasm(max = 10_000, budget = 12_000)
        assertNotNull(feed(r, split(bytes(9000)))); assertEquals(0, r.reserved)
        assertNotNull(feed(r, split(bytes(9000)).map { Fragment(it.streamId + 7, it.index, it.flags, it.total, it.data) }))
    }

    @Test fun `a message that is not complete in time is dropped and its pieces ignored`() {
        var now = 0L; val r = reasm(timeoutMs = 1000) { now }
        val fr = split(bytes(5000)); r.accept(fr[0]); r.accept(fr[1])
        now = 900_000_000L; assertTrue(r.expire().isEmpty())
        now = 1_100_000_000L; assertEquals(listOf(fr[0].streamId), r.expire()); assertEquals(0, r.reserved)
        assertIs<Reassembler.Result.Pending>(r.accept(fr[2]))      // the rest of it is still coming: no error, no memory
        assertEquals(0, r.active)
    }

    @Test fun `the memory budget is shared by reassemblers and given back when a link closes`() {
        val shared = ByteBudget(10_000)
        val a = Reassembler({ 10_000 }, shared, 4, 1_000_000_000L); val b = Reassembler({ 10_000 }, shared, 4, 1_000_000_000L)
        assertIs<Reassembler.Result.Pending>(a.accept(frag(1, 0, Fragment.FIRST, 7000, 100)))
        assertIs<Reassembler.Result.Refused>(b.accept(frag(1, 0, Fragment.FIRST, 7000, 100)))      // the other link finds the pool used up
        a.close(); assertEquals(0, shared.inUse)
        assertIs<Reassembler.Result.Pending>(b.accept(frag(1, 0, Fragment.FIRST, 7000, 100)))
    }

    @Test fun `the sender can abort`() {
        val r = reasm(); val fr = split(bytes(5000)); r.accept(fr[0]); r.abort(fr[0].streamId)
        assertEquals(0, r.reserved); assertIs<Reassembler.Result.Pending>(r.accept(fr[1]))
    }

    // ---- malicious sequences
    private fun frag(id: Long, i: Int, flags: Int, total: Int, n: Int) = Fragment(id, i, flags, total, bytes(n))
    @Test fun `malicious sequences close the link`() {
        fun violation(vararg fs: Fragment, check: (String) -> Unit = {}) { val r = reasm(); val e = assertFailsWith<FragmentViolation> { fs.forEach { r.accept(it) } }; check(e.message!!) }
        violation(frag(1, 1, 0, 0, 10)) { assertTrue("unknown stream" in it) }                                            // piece without a start
        violation(frag(1, 0, Fragment.FIRST, 100, 10), frag(1, 0, Fragment.FIRST, 100, 10)) { assertTrue("twice" in it) }    // started twice
        violation(frag(1, 0, Fragment.FIRST, 100, 10), frag(1, 0, 0, 0, 10)) { assertTrue("duplicate or out of order" in it) } // duplicate piece
        violation(frag(1, 0, Fragment.FIRST, 100, 10), frag(1, 2, 0, 0, 10)) { assertTrue("out of order" in it) }            // gap
        violation(frag(1, 3, Fragment.FIRST, 100, 10)) { assertTrue("index" in it) }                                      // first piece not index 0
        violation(frag(1, 0, Fragment.FIRST, 0, 10))                                                                       // announces nothing
        violation(frag(1, 0, Fragment.FIRST, -5, 10))
        violation(frag(1, 0, Fragment.FIRST, 100, 60), frag(1, 1, 0, 0, 60)) { assertTrue("more bytes" in it) }             // more than announced
        violation(frag(1, 0, Fragment.FIRST, 100, 60), frag(1, 1, Fragment.LAST, 0, 10)) { assertTrue("ended after 70 of 100" in it) }  // short
        violation(frag(1, 0, Fragment.FIRST, 100, 0)) { assertTrue("empty piece" in it) }
    }

    @Test fun `a complete stream cannot be continued`() {
        val r = reasm(); val fr = split(bytes(2500)); feed(r, fr)
        assertFailsWith<FragmentViolation> { r.accept(Fragment(fr[0].streamId, fr.size, 0, 0, bytes(5))) }
    }

    // ---- through the real handlers (EmbeddedChannel pair)
    private class Side(val reg: MessageRegistry, peer: Limits?) {
        val state = LinkState().also { it.peer = peer }
        val out = EmbeddedChannel(LinkWriter(reg, state))
        val inn = EmbeddedChannel(MessageDecoder(reg), LinkReader(reg, state))
        fun pump() { out.runPendingTasks(); out.flush() }
        /** Moves everything written so far to the other side's reader. */
        fun deliverTo(other: Side): List<NodeMessage> {
            pump(); val got = ArrayList<NodeMessage>()
            while (true) { val b = out.readOutbound<ByteBuf>() ?: break; other.inn.writeInbound(b); while (true) { got += other.inn.readInbound<NodeMessage>() ?: break } }
            return got
        }
    }
    private fun plugin(ch: String, n: Int, seed: Int = 1) = NetPluginMessage("a", "b", "p", ch, bytes(n, seed))

    @Test fun `a big message is split reassembled and equal`() {
        val a = Side(reg, Limits()); val b = Side(reg, Limits())
        val m = NetPluginMessage("a", "b", "p", "x:y", bytes(5_000_000))
        a.out.writeOneOutbound(m); val got = a.deliverTo(b).filterIsInstance<NetPluginMessage>()
        assertEquals(1, got.size); assertContentEquals(m.data, got[0].data); assertEquals("x:y", got[0].channel)
    }

    @Test fun `a small message overtakes a big one but ordered messages of one channel keep their order`() {
        val a = Side(reg, Limits()); val b = Side(reg, Limits())
        a.out.writeOutbound(plugin("x:big", 5_000_000), WorldRequest("small"), plugin("x:big", 10, 2), plugin("x:other", 10, 3))
        val got = a.deliverTo(b)
        val names = got.map { when (it) { is WorldRequest -> "world"; is NetPluginMessage -> "${it.channel}:${it.data.size}"; else -> "?" } }
        assertEquals(setOf("world", "x:big:5000000", "x:big:10", "x:other:10"), names.toSet())
        assertTrue(names.indexOf("world") < names.indexOf("x:big:5000000"), "a small message is not stuck behind the big one: $names")
        assertTrue(names.indexOf("x:other:10") < names.indexOf("x:big:5000000"), names.toString())
        assertTrue(names.indexOf("x:big:5000000") < names.indexOf("x:big:10"), "same sender+channel: sent order kept: $names")
    }

    @Test fun `nothing but the handshake leaves before the peer limits are known`() {
        val a = Side(reg, null); val b = Side(reg, Limits())
        a.out.writeOutbound(WorldRequest("later"))
        assertTrue(a.deliverTo(b).isEmpty())
        a.out.writeOutbound(Disconnect("control passes"))
        assertEquals(listOf("control passes"), a.deliverTo(b).map { (it as Disconnect).reason })
        a.state.peerKnown(Limits()); assertEquals(listOf("later"), a.deliverTo(b).map { (it as WorldRequest).name })
    }

    @Test fun `a message over the peer's limits is refused locally with the reason`() {
        val peer = Limits(maxPluginMessage = 1 shl 20)
        val a = Side(reg, peer)
        val f = a.out.writeOneOutbound(plugin("x:y", 2 shl 20)); a.out.flush()
        val e = assertFailsWith<Exception> { f.get() }.cause as LinkLimitException
        assertTrue("NetPluginMessage" in e.message!! && "exceeds" in e.message!!, e.message)
        // a message over max-message of the link (not a single field)
        val small = Limits(frame = 1 shl 20, maxMessage = 2 shl 20, maxPluginMessage = 1 shl 20)
        val f2 = Side(reg, small).out.writeOneOutbound(FileChunk("w", "f", 0, ByteArray(3 shl 20), true)); 
        assertFalse(f2.isSuccess)
    }

    @Test fun `an announced size over max-message closes the link, a fragment before the limits exchange too`() {
        val b = Side(reg, Limits())
        val e = assertFailsWith<FragmentViolation> { b.inn.writeInbound(encode(Fragment(1, 0, Fragment.FIRST, Limits().maxMessage + 1, bytes(100)))) }
        assertTrue("max-message" in e.message!!)
        val early = Side(reg, null)
        assertTrue("before the limits exchange" in assertFailsWith<FragmentViolation> { early.inn.writeInbound(encode(Fragment(1, 0, Fragment.FIRST or Fragment.LAST, 3, bytes(3)))) }.message!!)
    }

    @Test fun `a receiver that refuses a stream makes the sender stop it and fail the write`() {
        val a = Side(reg, Limits())
        a.out.unsafe().outboundBuffer().setUserDefinedWritability(1, false)       // the channel is full: the message waits in the scheduler
        val f = a.out.write(plugin("x:y", 3_000_000))
        assertFalse(f.isDone); a.out.runPendingTasks(); assertEquals(0, a.out.outboundMessages().size)
        a.inn.writeInbound(encode(FragmentAbort(1, true, "budget")))
        assertTrue(f.isDone && !f.isSuccess && "budget" in f.cause().message!!, "the write fails with the receiver's reason: done=${f.isDone} ok=${f.isSuccess} cause=${f.cause()}")
        a.out.unsafe().outboundBuffer().setUserDefinedWritability(1, true); a.out.runPendingTasks(); a.out.flush()
        assertNull(a.out.readOutbound<ByteBuf>(), "no piece of the refused message is sent")
    }

    private fun encode(m: NodeMessage): ByteBuf { val x = Unpooled.buffer(); reg.encode(m, x); return x }
}

private fun EmbeddedChannel.writeOneOutbound(m: NodeMessage): io.netty.channel.ChannelFuture { val f = write(m); flush(); return f 
    @Test fun `no empty queue is left behind for a finished order key`() {
        val s = FragmentScheduler<String>(100)
        s.enqueue(ByteArray(150), "k", "a"); s.enqueue(ByteArray(150), "k", "b")
        while (s.next() != null) { }
        assertFalse(s.busy("k")); assertTrue(s.waitingKeys() == 0)
    }
}
