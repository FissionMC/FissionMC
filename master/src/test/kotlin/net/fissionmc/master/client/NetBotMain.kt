package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.fissionmc.master.seamless.PlayPacketIds
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.util.UUID

/**
 * Packet-level test bot for the network side of the Paper port (`gradlew :master:netBot -Pport=25584`, normally started by .dev/scripts/net-test.sh).
 * Joins an OFFLINE standalone backend directly over TCP (no master) and drives the serverbound packets that Paper's packet-listener hunks turn into
 * Bukkit events: chat, commands, movement, swing/interact/use item/dig/place, inventory clicks, custom payloads, sign/book edits, resource pack status, respawn.
 * Server-side setup (items, entities, sign, op) comes from the NetProbe plugin (`fissiontest net*` commands); this bot only parses its `NETENT` answer.
 * Packets are hand-encoded (id from play-packet-ids.txt, fields from the 26.3 StreamCodecs); wire mistakes show up as missing events in the NETCHECK list.
 */
class NetBot(val port: Int) {
    val bot = Bot("NetBot", port).connect()
    private fun pkt(name: String, f: ByteBuf.() -> Unit) {
        val b = Unpooled.buffer(); VarInts.write(b, PlayPacketIds.s(name)); b.f()
        bot.c.sendRaw(ByteArray(b.readableBytes()).also { b.readBytes(it) })
    }
    private fun ByteBuf.str(s: String) { val t = s.toByteArray(); VarInts.write(this, t.size); writeBytes(t) }
    private fun ByteBuf.vi(v: Int) = VarInts.write(this, v)
    private fun ByteBuf.pos(x: Int, y: Int, z: Int) { writeLong(((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)) }
    private var seq = 1
    fun sleep(ms: Long) = Thread.sleep(ms)
    fun await(ms: Long, cond: () -> Boolean): Boolean { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(25) }; return cond() }

    // ---- chat / commands
    fun say(msg: String) = bot.say(msg)
    fun command(cmd: String) = bot.command(cmd)
    fun suggest(text: String) = pkt("CommandSuggestion") { vi(1); str(text) }
    // ---- movement (client tick: position + ClientTickEnd, like LocalPlayer)
    fun moveTo(x: Double, y: Double, z: Double, onGround: Boolean = true) {
        pkt("MovePlayerPos") { writeDouble(x); writeDouble(y); writeDouble(z); writeByte(if (onGround) 1 else 0) }; pkt("ClientTickEnd") {}
        bot.pos = doubleArrayOf(x, y, z)
    }
    fun look(yaw: Float, pitch: Float) { pkt("MovePlayerRot") { writeFloat(yaw); writeFloat(pitch); writeByte(1) }; pkt("ClientTickEnd") {} }
    /** ServerboundPlayerInput flags: forward 1, backward 2, left 4, right 8, jump 16, shift 32, sprint 64 */
    fun input(flags: Int) = pkt("PlayerInput") { writeByte(flags) }
    /** ServerboundPlayerCommand actions: 0 stop sleeping, 1 start sprint, 2 stop sprint, 5 open inventory, 6 start fall flying */
    fun playerCommand(entityId: Int, action: Int, data: Int = 0) = pkt("PlayerCommand") { vi(entityId); vi(action); vi(data) }
    // ---- world interaction
    fun swing() = pkt("Punch") {}
    fun useItemOn(x: Int, y: Int, z: Int, face: Int = 1, hand: Int = 0) = pkt("UseItemOn") {
        vi(hand); pos(x, y, z); vi(face); writeFloat(0.5f); writeFloat(if (face == 1) 1.0f else 0.5f); writeFloat(0.5f); writeBoolean(false); writeBoolean(false); vi(seq++)
    }
    fun useItem(hand: Int = 0, yaw: Float = 0f, pitch: Float = 0f) = pkt("UseItem") { vi(hand); vi(seq++); writeFloat(yaw); writeFloat(pitch) }
    /** ServerboundPlayerAction (26.3): 0 start destroy, 1 change destroy direction, 2 abort, 3 stop destroy, 4 drop all, 5 drop item, 6 release use item, 7 swap offhand, 8 stab */
    fun action(action: Int, x: Int = 0, y: Int = 0, z: Int = 0, face: Int = 0) = pkt("PlayerAction") { vi(action); pos(x, y, z); writeByte(face); vi(seq++) }
    fun interact(entityId: Int, hand: Int = 0, secondary: Boolean = false) = pkt("Interact") { vi(entityId); vi(hand); writeByte(0) /* LpVec3 zero */; writeBoolean(secondary) }
    fun attack(entityId: Int) = pkt("Attack") { vi(entityId) }
    fun held(slot: Int) = pkt("SetCarriedItem") { writeShort(slot) }
    // ---- inventory
    /** ClickType order of ContainerInput: 0 pickup, 1 quick move, 2 swap, 3 clone, 4 throw, 5 quick craft (drag), 6 pickup all. No changed slots / carried item hashes: the server resyncs after the event. */
    fun click(containerId: Int, slot: Int, button: Int, type: Int, stateId: Int = 0) = pkt("ContainerClick") { vi(containerId); vi(stateId); writeShort(slot); writeByte(button); vi(type); vi(0); writeBoolean(false) }
    fun closeContainer(id: Int) = pkt("ContainerClose") { vi(id) }
    /** creative: stack of 1 item with registry id [itemId] (1 = stone) and an empty component patch */
    fun creativeSlot(slot: Int, itemId: Int = 1) = pkt("SetCreativeModeSlot") { writeShort(slot); vi(1); vi(itemId); vi(0); vi(0) }
    // ---- misc
    fun payload(channel: String, data: ByteArray) = pkt("CustomPayload") { str(channel); writeBytes(data) }
    fun signUpdate(x: Int, y: Int, z: Int, front: Boolean, lines: List<String>) = pkt("SignUpdate") { pos(x, y, z); lines.forEach { str(it) }; vi(if (front) 0 else 1) }
    fun editBook(slot: Int, pages: List<String>, title: String?) = pkt("EditBook") { vi(slot); vi(pages.size); pages.forEach { str(it) }; writeBoolean(title != null); if (title != null) str(title) }
    /** ServerboundResourcePack: 0 loaded, 1 declined, 2 failed download, 3 accepted, 4 downloaded */
    fun resourcePack(id: UUID, action: Int) = pkt("ResourcePack") { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits); vi(action) }
    /** ServerboundClientCommand: 0 perform respawn, 1 request stats */
    fun clientCommand(action: Int) = pkt("ClientCommand") { vi(action) }

    fun lastOpenWindow(): Int? { val e = bot.events.lastOrNull { it.type == PacketType.Play.Server.OPEN_WINDOW } ?: return null; val b = Unpooled.wrappedBuffer(e.body); VarInts.tryRead(b); return VarInts.tryRead(b) }
    fun sysLine(prefix: String): String? = bot.sysTexts().lastOrNull { it.startsWith(prefix) }
    fun close() = bot.close()
}

fun main(args: Array<String>) {
    FissionPacketEventsAPI.install(Side.MASTER)
    runNetBot(args[0].toInt(), args.getOrNull(1) ?: System.getProperty("java.io.tmpdir"))
    kotlin.system.exitProcess(0)
}

/** The scripted session (returns when the bot disconnected). */
fun runNetBot(port: Int, out: String) {
    Bot.outDir = out
    val n = NetBot(port)
    val b = n.bot
    fun step(name: String, ms: Long = 400, f: () -> Unit) { println("[netbot] $name"); try { f() } catch (t: Throwable) { println("[netbot] $name failed: $t") }; n.sleep(ms) }
    try {
        if (!n.await(120_000) { b.loadedSent && b.pos != null }) { println("[netbot] never finished loading: failed=${b.failed}"); n.close(); return }
        println("[netbot] joined at ${b.pos!!.toList()} entity ${b.selfId}")
        n.sleep(1500)
        step("setup") { n.command("fissiontest netsetup") }
        val setup = n.await(15_000) { n.sysLine("NETENT") != null }
        if (!setup) { println("[netbot] no NETENT answer (is /fissiontest net* available and the bot op?): ${b.sysTexts().takeLast(5)}"); n.close(); return }
        n.sleep(800)
        val ent = n.sysLine("NETENT")!!
        fun kv(k: String) = Regex("$k=([-\\d,]+)").find(ent)!!.groupValues[1].split(',').map { it.toInt() }
        val cow = kv("cow")[0]; val stand = kv("stand")[0]; val chest = kv("chest"); val sign = kv("sign"); val floor = kv("floor")
        println("[netbot] $ent")

        step("chat") { n.say("hello from the bot") }
        step("command") { n.command("help 1") }
        step("tab complete") { n.suggest("/gamemo") }
        step("swing") { n.swing() }
        step("held slot") { n.held(1); n.held(0) }
        step("place block") { n.useItemOn(floor[0] + 1, floor[1], floor[2], 1) }
        step("use item (snowball)") { n.held(2); n.sleep(150); n.useItem() }
        step("drop item") { n.held(4); n.sleep(150); n.action(5) }
        step("swap hands") { n.held(1); n.sleep(150); n.action(7) }
        step("sneak/sprint") { n.input(32); n.sleep(120); n.input(0); n.playerCommand(b.selfId, 1); n.sleep(120); n.playerCommand(b.selfId, 2) }
        step("jump input") { n.input(16); n.sleep(120); n.input(0) }
        step("interact cow") { n.interact(cow) }
        step("interact armor stand") { n.held(1); n.sleep(150); n.interact(stand) }
        step("attack cow") { n.attack(cow) }
        step("inventory click") { n.click(0, 36, 0, 0); n.click(0, 37, 0, 0); n.click(0, 9, 0, 1); n.click(0, 38, 1, 4) }
        step("inventory drag") { n.click(0, 10, 0, 0); n.click(0, -999, 0, 5); n.click(0, 12, 1, 5); n.click(0, 13, 1, 5); n.click(0, -999, 2, 5) }
        step("open chest") { n.useItemOn(chest[0], chest[1], chest[2], 1) }
        n.await(5000) { n.lastOpenWindow() != null }
        val win = n.lastOpenWindow()
        println("[netbot] chest window $win")
        if (win != null) { step("chest click") { n.click(win, 0, 0, 0); n.click(win, 1, 0, 1) }; step("close chest") { n.closeContainer(win) } }
        step("plugin channel register") { n.payload("minecraft:register", "fissiontest:net\u0000".toByteArray()) }
        step("plugin message") { n.payload("fissiontest:net", byteArrayOf(1, 2, 3)) }
        step("brand") { n.payload("minecraft:brand", "netbot".let { s -> byteArrayOf(s.length.toByte()) + s.toByteArray() }) }
        step("open sign") { n.command("fissiontest netsign") }
        step("sign edit", 600) { n.signUpdate(sign[0], sign[1], sign[2], true, listOf("net", "bot", "", "")) }
        step("book edit") { n.held(3); n.sleep(150); n.editBook(3, listOf("page one"), null) }
        step("resource pack") { n.command("fissiontest netpack"); n.sleep(800); n.resourcePack(UUID(0, 5), 3); n.resourcePack(UUID(0, 5), 0) }
        step("dig (survival)") { n.held(1); n.sleep(150); n.action(0, floor[0] + 2, floor[1], floor[2], 1); n.sleep(150); n.action(2, floor[0] + 2, floor[1], floor[2], 1) }
        step("gamemode creative") { n.command("gamemode creative") }
        step("creative slot") { n.creativeSlot(40, 1); n.creativeSlot(-1, 1) }
        step("dig (creative)") { n.action(0, floor[0] + 1, floor[1] + 1, floor[2], 1); n.sleep(100); n.action(3, floor[0] + 1, floor[1] + 1, floor[2], 1) }
        step("teleport command") { n.command("tp NetBot ${floor[0] + 0.5} ${floor[1] + 1} ${floor[2] + 2.5}") }
        step("jump move") { val p = b.pos!!; n.moveTo(p[0], p[1] + 0.42, p[2], false); n.sleep(60); n.moveTo(p[0], p[1], p[2], true) }
        step("move") { val p = b.pos!!; repeat(6) { n.moveTo(p[0] + 0.1 * (it + 1), p[1], p[2]); n.sleep(60) } }
        step("move rot") { n.look(90f, 10f) }
        step("kill + respawn", 1500) { n.command("kill NetBot") }
        step("respawn") { n.clientCommand(0) }
        n.sleep(1000)
    } catch (t: Throwable) { println("[netbot] stopped: $t") }
    println("[netbot] disconnecting")
    n.close()
    println("NETBOT done")
}
