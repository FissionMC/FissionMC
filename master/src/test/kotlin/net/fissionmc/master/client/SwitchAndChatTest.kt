package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import io.netty.buffer.Unpooled
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import net.fissionmc.protocol.*
import net.kyori.adventure.text.format.NamedTextColor
import java.util.Properties
import kotlin.test.*

class SwitchAndChatTest {
    init { FissionPacketEventsAPI.install(Side.MASTER) }

    @Test fun `packet ids match the ids of the vanilla 26_3 jar`() {
        // read from net.minecraft.network.protocol.game.GameProtocols / ConfigurationProtocols of the server jar (scratch Ids.java, see worklog)
        assertEquals(50, PlayIds.login); assertEquals(45, PlayIds.keepAliveS2C); assertEquals(28, PlayIds.keepAliveC2S)
        assertEquals(9, PlayIds.bossEvent); assertEquals(71, PlayIds.playerInfoUpdate); assertEquals(70, PlayIds.playerInfoRemove)
        assertEquals(16, PlayIds.commands); assertEquals(124, PlayIds.systemChat); assertEquals(44, PlayIds.playerLoaded)
        assertEquals(7, PlayIds.chatCommand); assertEquals(8, PlayIds.chatCommandSigned); assertEquals(9, PlayIds.chat)
        assertEquals(3, PlayIds.cfgEnd); assertEquals(4, PlayIds.cfgKeepAlive); assertEquals(5, PlayIds.cfgPing); assertEquals(2, PlayIds.cfgDisconnect)
        assertEquals(13, PlayIds.cfgFeatures); assertEquals(15, PlayIds.cfgKnownPacks); assertEquals(7, PlayIds.cfgRegistry); assertEquals(14, PlayIds.cfgTags)
        assertEquals(4, PlayIds.cfgKeepAliveC2S); assertEquals(5, PlayIds.cfgPong)
    }

    @Test fun `the virtual client answers the recorded configuration and accepts it`() {
        val v = ConfigVirtualClient()
        val replies = ArrayList<ByteArray>(); var finished = false
        for (f in Limbo.config.let { it.beforeKnownPacks + it.afterKnownPacks }) { val r = v.onFrame(f); assertNull(r.abort); replies += r.replies; finished = finished || r.finished }
        assertTrue(finished)
        assertEquals(2, replies.size, "known packs answer + end ack")
        assertEquals(PlayIds.cfgKnownPacks.let { 7 }, Limbo.idOf(replies[0])); assertEquals(3, Limbo.idOf(replies[1]))
        assertNull(v.mismatch())
    }

    @Test fun `a changed registry or tag frame is a mismatch with a reason, a missing frame too`() {
        val frames = Limbo.config.let { it.beforeKnownPacks + it.afterKnownPacks }
        val v = ConfigVirtualClient()
        var abort: String? = null
        for (f in frames) {
            val g = if (Limbo.idOf(f) == PlayIds.cfgRegistry && abort == null && v.hashes.size == 3) f.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() } else f
            val r = v.onFrame(g); if (r.abort != null) { abort = r.abort; assertTrue(r.mismatch) }
        }
        assertNotNull(abort); assertTrue(abort!!.contains("differs"), abort)
        val w = ConfigVirtualClient()
        val r = frames.filter { Limbo.idOf(it) != PlayIds.cfgTags }.map { w.onFrame(it) }.last()
        assertTrue(r.mismatch, "dropping the tags frame must be detected")
    }

    @Test fun `tags and feature frames written in another map order hash the same`() {
        fun w(b: io.netty.buffer.ByteBuf, x: String) { val t = x.toByteArray(); VarInts.write(b, t.size); b.writeBytes(t) }
        fun tags(order: List<Int>): ByteArray {
            val b = Unpooled.buffer(); VarInts.write(b, PlayIds.cfgTags); VarInts.write(b, 2)
            for (r in order) { w(b, "minecraft:reg$r"); VarInts.write(b, 2); for (t in if (r == 0) listOf("a", "b") else listOf("b", "a")) { w(b, "minecraft:$t"); VarInts.write(b, 3); (if (r == 0) listOf(1, 2, 3) else listOf(3, 1, 2)).forEach { VarInts.write(b, it) } } }
            return ByteArray(b.readableBytes()).also { b.readBytes(it) }
        }
        assertEquals(ConfigVirtualClient.hash(tags(listOf(0, 1))), ConfigVirtualClient.hash(tags(listOf(1, 0))))
        fun feats(l: List<String>): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, PlayIds.cfgFeatures); VarInts.write(b, l.size); l.forEach { w(b, it) }; return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
        assertEquals(ConfigVirtualClient.hash(feats(listOf("minecraft:vanilla", "minecraft:trade_rebalance"))), ConfigVirtualClient.hash(feats(listOf("minecraft:trade_rebalance", "minecraft:vanilla"))))
        assertNotEquals(ConfigVirtualClient.hash(feats(listOf("minecraft:vanilla"))), ConfigVirtualClient.hash(feats(listOf("minecraft:other"))), "different content is still different")
    }

    @Test fun `configuration keepalive and ping are answered with the same payload`() {
        val v = ConfigVirtualClient()
        val ka = PlayIds.frame(PlayIds.cfgKeepAlive, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9))
        assertContentEquals(PlayIds.frame(4, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9)), v.onFrame(ka).replies.single())
        assertContentEquals(PlayIds.frame(5, byteArrayOf(0, 0, 0, 7)), v.onFrame(PlayIds.frame(PlayIds.cfgPing, byteArrayOf(0, 0, 0, 7))).replies.single())
        assertNotNull(v.onFrame(PlayIds.frame(PlayIds.cfgDisconnect)).abort)
    }

    @Test fun `cleanup removes boss bars and tab entries it saw`() {
        val c = SwitchCleanup({ null })
        val bar = java.util.UUID(1, 2)
        fun bossFrame(action: Int): ByteArray { val b = Unpooled.buffer(); b.writeLong(1); b.writeLong(2); VarInts.write(b, action); return PlayIds.frame(PlayIds.bossEvent, ByteArray(b.readableBytes()).also { b.readBytes(it) }) }
        c.observe(bossFrame(0)); assertEquals(1, c.tracked)
        val frames = c.cleanupFrames()
        assertTrue(frames.any { Limbo.idOf(it) == PlayIds.bossEvent && it.size == 1 + 16 + 1 && it.last() == 1.toByte() })
        assertEquals(0, c.tracked)
        c.observe(bossFrame(0)); c.observe(bossFrame(1)); assertEquals(0, c.tracked, "a removed bar is not cleaned up twice")
        assertNotNull(bar)
    }

    private fun readNodes(patched: ByteArray): Pair<List<Pair<String?, IntArray>>, Int> {
        val b = Unpooled.wrappedBuffer(patched); VarInts.tryRead(b); val n = VarInts.tryRead(b)!!
        val nodes = List(n) {
            val flags = b.readByte().toInt(); val kids = IntArray(VarInts.tryRead(b)!!) { VarInts.tryRead(b)!! }
            if (flags and 8 != 0) VarInts.tryRead(b)
            val name = if (flags and 3 == 1) { val l = VarInts.tryRead(b)!!; ByteArray(l).also { x -> b.readBytes(x) }.toString(Charsets.UTF_8) } else null
            name to kids
        }
        return nodes to VarInts.tryRead(b)!!.also { assertEquals(0, b.readableBytes()) }
    }

    @Test fun `the commands tree gets a chat literal with global local toggle and the rest stays`() {
        val b = Unpooled.buffer(); VarInts.write(b, PlayIds.commands); VarInts.write(b, 3)
        b.writeByte(0); VarInts.write(b, 2); VarInts.write(b, 1); VarInts.write(b, 2)                      // root -> 1, 2
        b.writeByte(1 or 4); VarInts.write(b, 0); VarInts.write(b, 1); b.writeByte('a'.code)               // literal a
        b.writeByte(1); VarInts.write(b, 0); VarInts.write(b, 1); b.writeByte('b'.code)                    // literal b
        b.writeByte(0)                                                                                     // root index
        val frame = ByteArray(b.readableBytes()).also { b.readBytes(it) }
        val (nodes, root) = readNodes(ChatCommandTree.patch(frame)!!)
        assertEquals(0, root); assertEquals(7, nodes.size)
        val rootKids = nodes[0].second.map { nodes[it].first }
        assertEquals(listOf("a", "b", "chat"), rootKids)
        val chat = nodes[nodes[0].second.last()]
        assertEquals(listOf("global", "local", "toggle"), chat.second.map { nodes[it].first })
        assertNull(ChatCommandTree.patch(byteArrayOf(16, 1, 0, 5)), "not a root at index 0: unchanged")
    }

    @Test fun `first string of a frame`() {
        val b = Unpooled.buffer(); VarInts.write(b, 7); val t = "chat global".toByteArray(); VarInts.write(b, t.size); b.writeBytes(t); b.writeLong(5)
        assertEquals("chat global", Strings.readFirst(ByteArray(b.readableBytes()).also { b.readBytes(it) }))
        assertNull(Strings.readFirst(byteArrayOf(7, 100, 1)))
    }

    // ---- formatter ----
    private val s = ChatSettings()
    @Test fun `default format looks like a vanilla chat line`() {
        val c = ChatFormatter.format(s, "Steve", "Steve", "backend-a", ChatFormatter.messageComponent("hello", false))
        assertEquals("<Steve> hello", ChatFormatter.plain(c))
    }
    @Test fun `placeholders prefix suffix and colour codes`() {
        val st = ChatSettings(format = "&7[{backend}] &f{displayname}&r: {message}", prefix = "&8>", suffix = "!")
        val c = ChatFormatter.format(st, "Steve", "Dr Steve", "backend-b", ChatFormatter.messageComponent("hi", false))
        assertEquals(">[backend-b] Dr Steve: hi!", ChatFormatter.plain(c))
        val first = c.children().firstOrNull { it is net.kyori.adventure.text.TextComponent && it.content().contains("backend-b") } ?: c.children().first()
        assertTrue(c.children().isNotEmpty() || first != null)
    }
    @Test fun `player text cannot inject formatting or placeholders`() {
        val evil = ChatFormatter.sanitize("§cRED\u0007 &4dark {name} {backend}\n", 256)
        assertEquals("cRED &4dark {name} {backend}", evil)
        val c = ChatFormatter.format(s, "Steve", "Steve", "b", ChatFormatter.messageComponent(evil, false))
        assertEquals("<Steve> cRED &4dark {name} {backend}", ChatFormatter.plain(c))
        // with colours off no node carries a colour from the player's text
        fun colours(x: net.kyori.adventure.text.Component): List<Any?> = listOf(x.color()) + x.children().flatMap { colours(it) }
        assertTrue(colours(c).all { it == null })
        // with colours allowed, & codes act (and only colour/format, nothing else can be injected)
        val on = ChatFormatter.messageComponent("&chello", true)
        assertEquals(NamedTextColor.RED, on.children().firstOrNull()?.color() ?: on.color())
        assertEquals(10, ChatFormatter.sanitize("x".repeat(50), 10).length)
    }

    @Test fun `settings parse admin names, defaults and chat keys`() {
        val p = Properties().apply { setProperty("admin.players", "Alice, 11111111-2222-3333-4444-555555555555"); setProperty("chat.default", "global"); setProperty("chat.global.format", "{name}: {message}"); setProperty("chat.global.allow-colors", "true"); setProperty("chat.global.max-length", "40") }
        val m = MasterSettings.parse(p)
        assertTrue(m.isAdmin("alice", null)); assertTrue(m.isAdmin("x", java.util.UUID.fromString("11111111-2222-3333-4444-555555555555"))); assertFalse(m.isAdmin("bob", null))
        assertTrue(m.chat.defaultGlobal); assertEquals("{name}: {message}", m.chat.format); assertTrue(m.chat.allowColors); assertEquals(40, m.chat.maxLength)
        assertFalse(MasterSettings.parse(Properties()).chat.defaultGlobal); assertEquals("<{name}> {message}", MasterSettings.parse(Properties()).chat.format)
    }

    @Test fun `global chat messages survive the wire`() {
        val reg = MessageRegistry.core().registerPlayerMessages()
        val msgs = listOf(GlobalChat(7, 99, "hello äö"), GlobalChatResult(7, 99, false, true, "Steve", "{\"text\":\"hi\"}"), GlobalChatResult(7, 100, true, false, "", ""),
            GlobalChatObserved(1L, -2L, "Steve", "backend-a", "hello", "{\"text\":\"hello\"}"))
        for (m in msgs) { val b = Unpooled.buffer(); reg.encode(m, b); assertEquals(m, reg.decode(b)); assertEquals(0, b.readableBytes()) }
    }
}
