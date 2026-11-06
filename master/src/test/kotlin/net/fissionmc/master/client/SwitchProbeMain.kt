package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientConfigurationEndAck
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSelectKnownPacks
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake.ConnectionIntention
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginSuccessAck
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerSetCompression
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommandUnsigned
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientConfigurationAck
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerLoaded
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import io.netty.buffer.Unpooled
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/**
 * Dev tool (`gradlew :master:probeClasspath` then java -cp ... SwitchProbeMainKt <port> <outDir>): three scripted offline
 * clients against a master with TWO backends. Checks, with PASS/FAIL lines:
 * no StartConfiguration after the initial configuration, local chat stays on one backend, global chat reaches clients of both backends,
 * the limbo receives nothing, `/chat` is in the command tree, `/fission send` (admin only) moves a player to the other backend, entity ids follow
 * the backend's Login, tab cleanup precedes the second Login. After "PHASE1-DONE" it waits for a line on stdin (the caller kills the backend the
 * first player is on), then checks the return to the limbo in the PLAY state and the move to the surviving backend.
 * Writes each client's raw server->client stream to <outDir>/<name>-s2c.bin for the vanilla decoder replay (backend -PcheckStream).
 */
class Ev(val t: Long, val type: PacketTypeCommon?, val id: Int, val text: String?, val body: ByteArray) {
    fun bodyHas(s: String) = String(body, Charsets.ISO_8859_1).contains(String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))
    /** First varint after the packet id (entity id of most entity packets). */
    fun firstVarInt(): Int { val b = Unpooled.wrappedBuffer(body); VarInts.tryRead(b); return VarInts.tryRead(b) ?: -1 }
    fun entityIdOfLogin(): Int { val b = Unpooled.wrappedBuffer(body); VarInts.tryRead(b); return b.readInt() }
}

class Bot(val name: String, private val port: Int, private val holdLoaded: Boolean = false) {
    val c = TestClient(port)
    val events = CopyOnWriteArrayList<Ev>()
    @Volatile var logins = 0; @Volatile var startConfigs = 0; @Volatile var failed: Throwable? = null
    val loginIndexes = CopyOnWriteArrayList<Int>()      // index in [events] of each JOIN_GAME
    @Volatile private var stop = false

    fun connect(): Bot {
        c.readTimeout(120_000)
        c.send(WrapperHandshakingClientHandshake(777, "localhost", port, ConnectionIntention.LOGIN)); c.setState(ConnectionState.LOGIN)
        c.send(WrapperLoginClientLoginStart(ClientVersion.V_26_3, name, null, UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())))
        c.setCompression(c.expect(PacketType.Login.Server.SET_COMPRESSION) { WrapperLoginServerSetCompression(it) }.threshold)
        c.skip(PacketType.Login.Server.LOGIN_SUCCESS)
        c.send(WrapperLoginClientLoginSuccessAck()); c.setState(ConnectionState.CONFIGURATION)
        c.send(com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSettings("en_us", 10,
            com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSettings.ChatVisibility.FULL, true, 127.toByte(),
            com.github.retrooper.packetevents.protocol.player.HumanoidArm.RIGHT, false, true))
        Thread({ run() }, "bot-$name").also { it.isDaemon = true }.start()
        return this
    }

    private fun run() {
        try {
            while (!stop) {
                val (type, id) = c.readAny(); val body = c.lastBody
                var text: String? = null
                when (type) {
                    PacketType.Configuration.Server.SELECT_KNOWN_PACKS -> c.send(WrapperConfigClientSelectKnownPacks(listOf(com.github.retrooper.packetevents.util.KnownPack("minecraft", "core", net.fissionmc.protocol.MC_VERSION))))
                    PacketType.Configuration.Server.CONFIGURATION_END -> { c.send(WrapperConfigClientConfigurationEndAck()); c.setState(ConnectionState.PLAY) }
                    PacketType.Play.Server.CONFIGURATION_START -> { startConfigs++; c.send(WrapperPlayClientConfigurationAck()); c.setState(ConnectionState.CONFIGURATION) }
                    PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> text = runCatching { ChatFormatter.plain(WrapperPlayServerSystemChatMessage(c.lastEvent!!).message) }.getOrNull()
                    PacketType.Play.Server.KEEP_ALIVE -> c.send(WrapperPlayClientKeepAlive(Unpooled.wrappedBuffer(body).also { VarInts.tryRead(it) }.readLong()))
                    PacketType.Play.Server.JOIN_GAME -> { logins++; loginIndexes += events.size }
                    PacketType.Play.Server.CHUNK_DATA -> if (!holdLoaded) c.send(WrapperPlayClientPlayerLoaded())   // ClientPacketListener.notifyPlayerLoaded once the level is ready
                    else -> {}
                }
                events += Ev(System.nanoTime() / 1_000_000, type, id, text, body)
            }
        } catch (t: Throwable) { if (!stop) failed = t }
    }

    fun say(msg: String) {   // ServerboundChatPacket: string, timestamp, salt, no signature, LastSeenMessages.Update(offset 0, 20-bit set, checksum 0)
        val b = Unpooled.buffer(); VarInts.write(b, PlayIds.chat)
        val t = msg.toByteArray(); VarInts.write(b, t.size); b.writeBytes(t); b.writeLong(System.currentTimeMillis()); b.writeLong(0); b.writeBoolean(false)
        VarInts.write(b, 0); b.writeBytes(byteArrayOf(0, 0, 0)); b.writeByte(0)
        c.sendRaw(ByteArray(b.readableBytes()).also { b.readBytes(it) })
    }
    fun command(cmd: String) = c.send(WrapperPlayClientChatCommandUnsigned(cmd))

    fun sysTexts(from: Int = 0) = events.drop(from).mapNotNull { if (it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) it.text else null }
    fun playerChats(from: Int = 0, containing: String? = null) = events.drop(from).filter { it.type == PacketType.Play.Server.CHAT_MESSAGE && (containing == null || it.bodyHas(containing)) }
    fun close() { stop = true; Files.write(Path.of(outDir, "$name-s2c.bin"), c.received.toByteArray()); c.close() }
    companion object { var outDir = "." }
}

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") { println("[probe] ${if (ok) "PASS" else "FAIL"} $name${if (detail.isNotEmpty()) " ($detail)" else ""}"); if (!ok) failures++ }
private fun await(ms: Long, cond: () -> Boolean): Boolean { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(25) }; return cond() }
private fun quiet(ms: Long) { Thread.sleep(ms) }

fun main(args: Array<String>) {
    FissionPacketEventsAPI.install(Side.MASTER)
    val port = args[0].toInt(); Bot.outDir = args[1]; Files.createDirectories(Path.of(args[1]))
    val bots = ArrayList<Bot>()
    try { scenario(port, bots) } catch (t: Throwable) { println("[probe] FAIL scenario aborted: $t"); t.printStackTrace(System.out); failures++ }
    bots.forEach { b -> check("${b.name}: no StartConfiguration after the initial configuration", b.startConfigs == 0, "${b.startConfigs}"); check("${b.name}: connection stayed healthy", b.failed == null, b.failed?.toString() ?: "") }
    bots.forEach { runCatching { it.close() } }
    println("[probe] DONE failures=$failures")
    exitProcess(if (failures == 0) 0 else 1)
}

private fun scenario(port: Int, bots: MutableList<Bot>) {
    fun newBot(n: String, hold: Boolean = false) = Bot(n, port, hold).also { bots += it }.connect()
    val a = newBot("ProbeA")
    check("A reaches a backend (limbo Login, then the backend's Login)", await(60_000) { a.logins >= 2 }, "logins=${a.logins}")
    val b = newBot("ProbeB")
    check("B reaches a backend", await(60_000) { b.logins >= 2 }, "logins=${b.logins}")
    quiet(1500)
    // --- the command tree has /chat with its three children
    val tree = a.events.lastOrNull { it.type == PacketType.Play.Server.DECLARE_COMMANDS }
    check("/chat global|local|toggle is in the relayed command tree", tree != null && listOf("chat", "global", "local", "toggle").all { w -> tree.bodyHas(String(byteArrayOf(w.length.toByte())) + w) })

    // --- entity ids: the client's id is the backend's Login id, and later packets use it
    fun selfIdOk(bot: Bot, k: Int): Boolean {
        val start = bot.loginIndexes[k]; val id = bot.events[start].entityIdOfLogin()
        val next = bot.loginIndexes.getOrNull(k + 1) ?: bot.events.size
        return bot.events.subList(start + 1, next).any { (it.type == PacketType.Play.Server.UPDATE_ATTRIBUTES || it.type == PacketType.Play.Server.ENTITY_METADATA) && it.firstVarInt() == id }
    }
    check("A: packets about the player use the id of the backend's Login", selfIdOk(a, 1), "login ids ${a.loginIndexes.map { a.events[it].entityIdOfLogin() }}")
    check("B: packets about the player use the id of the backend's Login", selfIdOk(b, 1))

    // --- LOCAL (default): stays on one backend
    var ma = a.events.size; var mb = b.events.size
    a.say("hello-local-A")
    check("local: the sender's backend delivers vanilla player chat to the sender", await(8000) { a.playerChats(ma, "hello-local-A").isNotEmpty() })
    quiet(1500)
    check("local: the other backend's player receives nothing", b.playerChats(mb).isEmpty() && b.sysTexts(mb).none { it.contains("hello-local-A") })

    // --- GLOBAL
    ma = a.events.size; mb = b.events.size
    a.command("chat global"); b.command("chat global")
    check("/chat global answers the sender", await(5000) { a.sysTexts(ma).any { it.contains("global") } })
    check("/chat global answers the second player", await(5000) { b.sysTexts(mb).any { it.contains("global") } })
    // a third client sits in the limbo (never reports its level loaded) while global messages fly
    val c = newBot("ProbeC", hold = true)
    check("C is in the limbo (first Login)", await(20_000) { c.logins >= 1 && c.events.any { it.type == PacketType.Play.Server.CHUNK_DATA } })
    val limboSince = c.events.size
    c.say("limbo-chat-must-vanish"); c.command("chat global"); c.say("limbo-chat-2")
    quiet(500)
    ma = a.events.size; mb = b.events.size
    a.say("hello-global-A")
    check("global: sender receives the unsigned system line", await(8000) { a.sysTexts(ma).any { it == "<ProbeA> hello-global-A" } }, a.sysTexts(ma).toString())
    check("global: a player on the OTHER backend receives it", await(8000) { b.sysTexts(mb).any { it == "<ProbeA> hello-global-A" } }, b.sysTexts(mb).toString())
    check("global: no vanilla player-chat packet was delivered by the backend", a.playerChats(ma, "hello-global-A").isEmpty() && b.playerChats(mb, "hello-global-A").isEmpty())
    ma = a.events.size; mb = b.events.size
    b.say("hi-from-B")
    check("global: both directions", await(8000) { a.sysTexts(ma).contains("<ProbeB> hi-from-B") && b.sysTexts(mb).contains("<ProbeB> hi-from-B") })
    ma = a.events.size; mb = b.events.size
    a.say("shout: loud words")
    check("global: a plugin that modifies the message changes what everybody reads", await(8000) { a.sysTexts(ma).contains("<ProbeA> LOUD WORDS") && b.sysTexts(mb).contains("<ProbeA> LOUD WORDS") }, a.sysTexts(ma).toString())
    val secondLoginOfC = c.loginIndexes.getOrNull(1)
    val limboEnd = secondLoginOfC ?: c.events.size
    check("limbo: nothing was delivered to the limbo player while it waited", c.events.subList(limboSince, limboEnd).none { it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE || it.type == PacketType.Play.Server.CHAT_MESSAGE },
        "limbo window ${limboEnd - limboSince} frames, second login ${if (secondLoginOfC == null) "not yet" else "seen"}")
    check("limbo: the player's own chat/commands were dropped without disconnect", c.failed == null)
    ma = a.events.size; mb = b.events.size
    a.say("BLOCKME now")
    quiet(2500)
    check("global: a plugin that cancels the event stops the broadcast", a.sysTexts(ma).none { it.contains("BLOCKME") } && b.sysTexts(mb).none { it.contains("BLOCKME") })
    check("C leaves the limbo for a backend", await(40_000) { c.logins >= 2 }, "logins=${c.logins}")

    // --- back to LOCAL
    a.command("chat local"); b.command("chat toggle")
    quiet(800)
    ma = a.events.size; mb = b.events.size
    a.say("local-again-A")
    check("local again: the sender's backend delivers", await(8000) { a.playerChats(ma, "local-again-A").isNotEmpty() })
    quiet(1200)
    check("local again: nothing on the other backend", b.playerChats(mb).isEmpty() && b.sysTexts(mb).none { it.contains("local-again-A") })

    // --- /fission send (admin only)
    mb = b.events.size
    b.command("fission send ProbeA backend-a")
    check("non-admin cannot use /fission send", await(5000) { b.sysTexts(mb).any { it.contains("not allowed") } })
    val bLogins = b.logins; mb = b.events.size
    a.command("fission send ProbeB backend-a")      // A is on backend-a (first attach), B moves over
    check("send: the player receives a new Login (no StartConfiguration)", await(40_000) { b.logins == bLogins + 1 }, "logins=${b.logins}")
    check("send: tab cleanup (PlayerInfoRemove) precedes the new Login", b.events.subList(mb, b.loginIndexes.last()).any { it.type == PacketType.Play.Server.PLAYER_INFO_REMOVE })
    check("send: packets about the player use the new backend's Login id", await(8000) { selfIdOk(b, b.loginIndexes.size - 1) })
    quiet(1500)
    ma = a.events.size; mb = b.events.size
    b.command("chat local")
    quiet(300)
    a.say("same-backend-now")
    check("send: after the move both players share a backend (local chat reaches B)", await(8000) { b.playerChats(mb, "same-backend-now").isNotEmpty() })
    val aLogins = a.logins
    b.command("fission send ProbeA backend-b")      // refused for B, then A itself moves
    a.command("fission send ProbeA backend-b")
    check("send: backend to backend (A moves to the other backend)", await(40_000) { a.logins == aLogins + 1 }, "logins=${a.logins}")
    quiet(1500)
    val before = a.logins; val mk = a.events.size
    println("[probe] PHASE1-DONE")
    System.out.flush()
    readlnOrNull()   // the caller stops A's backend (backend-b) and writes a line
    check("backend death: the client is put back into the limbo (a new Login in PLAY)", await(30_000) { a.logins > before }, "logins=${a.logins}")
    check("backend death: the limbo position follows, no StartConfiguration", await(10_000) { a.events.drop(mk).any { it.type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK } })
    check("backend death: the player moves on to the surviving backend", await(60_000) { a.logins >= before + 2 }, "logins=${a.logins}")
    check("backend death: the new Login follows the limbo Login (no StartConfiguration anywhere)", a.startConfigs == 0)
    quiet(1500)
}
