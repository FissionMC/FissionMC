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

class Bot(val name: String, private val port: Int, private val holdLoaded: Boolean = false, private val fileTag: String = name) {
    val c = TestClient(port)
    val events = CopyOnWriteArrayList<Ev>()
    @Volatile var logins = 0; @Volatile var startConfigs = 0; @Volatile var failed: Throwable? = null
    val loginIndexes = CopyOnWriteArrayList<Int>()      // index in [events] of each JOIN_GAME
    @Volatile private var stop = false
    /** Chunks received since the latest Login (x,z packed), like the client's chunk cache of its current level. */
    val chunkSets = CopyOnWriteArrayList<MutableSet<Long>>()
    @Volatile var pos: DoubleArray? = null
    @Volatile var loadedSent = false
    @Volatile var batches = 0
    @Volatile var look = floatArrayOf(0f, 0f)
    val positionPackets = java.util.concurrent.atomic.AtomicInteger()     // ClientboundPlayerPosition packets since the latest Login
    val teleportIds = CopyOnWriteArrayList<Int>()
    val loadedAfterChunks = CopyOnWriteArrayList<Boolean>()   // per Login: PlayerLoaded was sent only once the player's own chunk was there

    /** ClientPacketListener.notifyPlayerLoaded: the level is ready once the chunk at the player's position is loaded (LevelLoadTracker). */
    private fun maybeLoaded() {
        val p = pos ?: return; val set = chunkSets.lastOrNull() ?: return
        if (holdLoaded || loadedSent) return
        val key = (Math.floor(p[0] / 16).toLong() shl 32) or (Math.floor(p[2] / 16).toLong() and 0xffffffffL)
        if (key in set) { loadedSent = true; loadedAfterChunks += true; c.send(WrapperPlayClientPlayerLoaded()) }
    }

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
        // LocalPlayer.sendPosition each client tick: a status-only packet every tick while standing still on the ground (the periodic full position is left out: two in one server tick are a kick)
        Thread({
            var tick = 0
            try { while (!stop) { Thread.sleep(50); tick++
                val p = pos ?: continue; if (!loadedSent) continue
                val loc = com.github.retrooper.packetevents.protocol.world.Location(p[0], p[1], p[2], look[0], look[1])
                c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying(false, false, true, loc))
            } } catch (_: Throwable) {}
        }, "move-$name").also { it.isDaemon = true }.start()
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
                    PacketType.Play.Server.JOIN_GAME -> { logins++; loginIndexes += events.size; chunkSets += java.util.concurrent.ConcurrentHashMap.newKeySet<Long>(); pos = null; loadedSent = false; positionPackets.set(0); teleportIds.clear() }
                    PacketType.Play.Server.CHUNK_DATA -> {
                        val b = Unpooled.wrappedBuffer(body); VarInts.tryRead(b); val x = b.readInt(); val z = b.readInt()
                        chunkSets.lastOrNull()?.add((x.toLong() shl 32) or (z.toLong() and 0xffffffffL)); maybeLoaded()
                    }
                    PacketType.Play.Server.CHUNK_BATCH_END -> {   // ClientPacketListener.handleChunkBatchFinished: answer with the wanted chunks per tick
                        batches++
                        c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChunkBatchAck(9.0f))
                    }
                    PacketType.Play.Server.PLAYER_POSITION_AND_LOOK -> {   // id, teleport id, x y z, delta x y z (doubles), yRot xRot (floats), relatives (int)
                        val b = Unpooled.wrappedBuffer(body); VarInts.tryRead(b); val tp = VarInts.tryRead(b)!!
                        val p = doubleArrayOf(b.readDouble(), b.readDouble(), b.readDouble()); b.skipBytes(24)
                        val yaw = b.readFloat(); val pitch = b.readFloat()
                        pos = p; look = floatArrayOf(yaw, pitch); positionPackets.incrementAndGet(); teleportIds += tp
                        // 26.3: ServerboundAcceptTeleportation carries the client's position and rotation (the server applies them)
                        c.send(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm(tp, p[0], p[1], p[2], yaw, pitch))
                        maybeLoaded()
                    }
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

    fun joinLines(from: Int = 0) = events.toList().drop(from).count { it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE && it.bodyHas("multiplayer.player.joined") }
    fun leaveLines(from: Int = 0) = events.toList().drop(from).count { it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE && it.bodyHas("multiplayer.player.left") }
    fun sysTexts(from: Int = 0) = events.drop(from).mapNotNull { if (it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) it.text else null }
    fun playerChats(from: Int = 0, containing: String? = null) = events.drop(from).filter { it.type == PacketType.Play.Server.CHAT_MESSAGE && (containing == null || it.bodyHas(containing)) }
    fun close() { stop = true; Files.write(Path.of(outDir, "$fileTag-s2c.bin"), c.received.toByteArray()); c.close() }
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
    fun newBot(n: String, hold: Boolean = false, tag: String = n) = Bot(n, port, hold, tag).also { bots += it }.connect()
    val a = newBot("ProbeA")
    check("A reaches a backend (limbo Login, then the backend's Login)", await(60_000) { a.logins >= 2 }, "logins=${a.logins}")
    val b = newBot("ProbeB")
    check("B reaches a backend", await(60_000) { b.logins >= 2 }, "logins=${b.logins}")
    quiet(1500)
    // --- network announcements: exactly one join line per login, from the master only (the backends stay silent)
    check("announcements: A saw exactly one 'joined' line for B (and its own), none from a backend", a.joinLines() == 2, "joined lines at A: ${a.joinLines()}")
    // --- the command tree has /chat with its three children
    val tree = a.events.lastOrNull { it.type == PacketType.Play.Server.DECLARE_COMMANDS }
    check("/chat global|local|toggle is in the relayed command tree", tree != null && listOf("chat", "global", "local", "toggle").all { w -> tree.bodyHas(String(byteArrayOf(w.length.toByte())) + w) })

    val treeB = b.events.lastOrNull { it.type == PacketType.Play.Server.DECLARE_COMMANDS }
    check("admin tree: /fission send <player> <backend> and status are in the admin's command tree", tree != null && listOf("fission", "send", "status", "player", "backend").all { w -> tree.bodyHas(String(byteArrayOf(w.length.toByte())) + w) })
    check("non-admin tree: no master-only /fission send node", treeB != null && !treeB.bodyHas(String(byteArrayOf(4)) + "send") && treeB.bodyHas(String(byteArrayOf(4)) + "chat"))
    // tab completion of the master's own command (ask_server suggestions): players, then backends
    fun suggestions(bot: Bot, text: String): String? {
        val before = bot.events.size
        val body = Unpooled.buffer(); VarInts.write(body, 77); val t = text.toByteArray(); VarInts.write(body, t.size); body.writeBytes(t)
        bot.c.sendRaw(PlayIds.frame(PlayIds.commandSuggestion, ByteArray(body.readableBytes()).also { body.readBytes(it) }))
        if (!await(5000) { bot.events.drop(before).any { it.type == PacketType.Play.Server.TAB_COMPLETE } }) return null
        return String(bot.events.drop(before).first { it.type == PacketType.Play.Server.TAB_COMPLETE }.body, Charsets.ISO_8859_1)
    }
    val sug1 = suggestions(a, "/fission send Pro"); val sug2 = suggestions(a, "/fission send ProbeA backend-")
    check("tab completion: player names", sug1 != null && sug1.contains("ProbeA") && sug1.contains("ProbeB"), sug1 ?: "none")
    check("tab completion: backend ids", sug2 != null && sug2.contains("backend-a") && sug2.contains("backend-b"), sug2 ?: "none")

    // --- admin == operator level 4 on every backend (granted by the master, ops.json untouched)
    fun timeAnswer(bot: Bot, from: Int) = bot.events.toList().drop(from).any { it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE && (it.bodyHas("commands.seed.success") || it.bodyHas("Seed: ")) }
    val gm = String(byteArrayOf(8)) + "gamemode"
    fun treeHasOpCommands(bot: Bot) = bot.events.lastOrNull { it.type == PacketType.Play.Server.DECLARE_COMMANDS }?.bodyHas(gm) == true
    var markA = a.events.size; var markB = b.events.size
    a.command("seed"); b.command("seed")
    check("admin: an operator-only command (seed) works for the admin", await(8000) { timeAnswer(a, markA) })
    quiet(1500)
    check("admin: the same command is refused for a non-admin on the other backend", !timeAnswer(b, markB))
    check("admin: the admin's command tree contains operator commands, the non-admin's does not", treeHasOpCommands(a) && !treeHasOpCommands(b))
    a.command("fission admin add ProbeB")
    check("admin add: B (resolved to its uuid) gets operator commands in a fresh tree", await(10_000) { treeHasOpCommands(b) })
    markB = b.events.size; b.command("seed")
    check("admin add: and the operator-only command works for B", await(8000) { timeAnswer(b, markB) })
    a.command("fission admin remove ProbeB")
    check("admin remove: B loses the operator commands again", await(10_000) { !treeHasOpCommands(b) })
    markB = b.events.size; b.command("seed"); quiet(1500)
    check("admin remove: the command is refused again", !timeAnswer(b, markB))

    // --- entity ids: the client's id is the backend's Login id, and later packets use it
    fun selfIdOk(bot: Bot, k: Int): Boolean {
        val start = bot.loginIndexes[k]; val id = bot.events[start].entityIdOfLogin()
        val next = bot.loginIndexes.getOrNull(k + 1) ?: bot.events.size
        return bot.events.toList().subList(start + 1, next).any { (it.type == PacketType.Play.Server.UPDATE_ATTRIBUTES || it.type == PacketType.Play.Server.ENTITY_METADATA) && it.firstVarInt() == id }
    }
    check("A: packets about the player use the id of the backend's Login", selfIdOk(a, 1), "login ids ${a.loginIndexes.map { a.events[it].entityIdOfLogin() }}")
    check("B: packets about the player use the id of the backend's Login", selfIdOk(b, 1))

    // --- a faithful client gets its world: the chunk batches are acknowledged, so the backend keeps sending, and PlayerLoaded follows only
    //     once the chunk under the player is there. (Real-client report: "stuck in a state of falling" after the seamless Login.)
    fun worldOk(bot: Bot, label: String, minChunks: Int) {
        val ok = await(40_000) { (bot.chunkSets.lastOrNull()?.size ?: 0) >= minChunks && bot.loadedSent }
        check("$label: the new level gets its chunks (${bot.chunkSets.lastOrNull()?.size ?: 0} >= $minChunks, batches acked ${bot.batches}) and the client reports it loaded", ok,
            "position ${bot.pos?.toList()}, loaded=${bot.loadedSent}")
    }
    worldOk(a, "A after limbo -> backend", 60); worldOk(b, "B after limbo -> backend", 60)
    // the player must settle: the backend sends ClientboundPlayerPosition once (the join teleport) and then leaves the player alone
    fun settled(bot: Bot, label: String) {
        quiet(4000)
        check("$label: the position is not corrected again (rubber banding): ${bot.positionPackets.get()} PlayerPosition packet(s) since the Login, teleport ids ${bot.teleportIds}", bot.positionPackets.get() <= 1, "position ${bot.pos?.toList()}")
    }
    settled(a, "A on its first backend"); settled(b, "B on its first backend")

    fun respawnFollowsLogin(bot: Bot) = bot.loginIndexes.drop(1).all { i -> bot.events.getOrNull(i + 1)?.type == PacketType.Play.Server.RESPAWN }
    check("A: every Login after the first is followed by a Respawn (new LocalPlayer on the new level)", respawnFollowsLogin(a))
    check("B: every Login after the first is followed by a Respawn", respawnFollowsLogin(b))

    // --- LOCAL (default): stays on one backend
    var ma = a.events.size; var mb = b.events.size
    a.say("hello-local-A")
    check("local: the sender's backend delivers the line to the sender (the master-pushed local format: a system line)", await(8000) { a.sysTexts(ma).any { it.contains("hello-local-A") } })
    val localLine = a.events.drop(ma).firstOrNull { it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE && it.bodyHas("hello-local-A") }
    check("local format: the line is rendered by the backend's chat.local.format (server id, #b0d8d8 colour, player head object), not by vanilla",
        localLine != null && localLine.bodyHas("backend-") && localLine.bodyHas("player") && String(localLine.body, Charsets.ISO_8859_1).lowercase().contains("#b0d8d8"), localLine?.let { String(it.body, Charsets.ISO_8859_1).filter { c -> c.code in 32..126 }.take(200) } ?: "no line")
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
    check("global format: the line carries the player head object of chat.global.format (<head:{uuid}>)", a.events.drop(ma).firstOrNull { it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE && it.text == "<ProbeA> hello-global-A" }?.bodyHas("player") == true)
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
    check("limbo: nothing was delivered to the limbo player while it waited", c.events.toList().subList(limboSince, limboEnd).none { it.type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE || it.type == PacketType.Play.Server.CHAT_MESSAGE },
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
    check("local again: the sender's backend delivers", await(8000) { a.sysTexts(ma).any { it.contains("local-again-A") } })
    quiet(1200)
    check("local again: nothing on the other backend", b.playerChats(mb).isEmpty() && b.sysTexts(mb).none { it.contains("local-again-A") })

    // --- /fission send (admin only)
    mb = b.events.size
    b.command("fission send ProbeA backend-a")
    check("non-admin cannot use /fission send", await(5000) { b.sysTexts(mb).any { it.contains("not allowed") } })
    val bLogins = b.logins; mb = b.events.size
    a.command("fission send ProbeB backend-a")      // A is on backend-a (first attach), B moves over
    check("send: the player receives a new Login (no StartConfiguration)", await(40_000) { b.logins == bLogins + 1 }, "logins=${b.logins}")
    check("send: tab cleanup (PlayerInfoRemove) precedes the new Login", b.events.toList().subList(mb, b.loginIndexes.last()).any { it.type == PacketType.Play.Server.PLAYER_INFO_REMOVE })
    worldOk(b, "send: B on the new backend", 60); settled(b, "send: B on the new backend")
    check("send: packets about the player use the new backend's Login id", await(8000) { selfIdOk(b, b.loginIndexes.size - 1) })
    quiet(1500)
    ma = a.events.size; mb = b.events.size
    b.command("chat local")
    quiet(300)
    a.say("same-backend-now")
    check("send: after the move both players share a backend (local chat reaches B)", await(8000) { b.sysTexts(mb).any { it.contains("same-backend-now") } })
    val aLogins = a.logins
    b.command("fission send ProbeA backend-b")      // refused for B, then A itself moves
    a.command("fission send ProbeA backend-b")
    check("send: backend to backend (A moves to the other backend)", await(40_000) { a.logins == aLogins + 1 }, "logins=${a.logins}")
    markA = a.events.size; quiet(1500); a.command("seed")
    check("admin: still operator level 4 after the move to the other backend", await(8000) { timeAnswer(a, markA) })
    quiet(1500)
    // --- announcements: backend switches announced nothing, the reconnect below announces once each way
    val joinsBefore = a.joinLines(); val leavesBefore = a.leaveLines()
    // --- master-owned player data: position and inventory follow a /fission send and survive a reconnect
    fun console(node: String, cmd: String) { println("[probe] CONSOLE $node $cmd"); System.out.flush(); readlnOrNull() }
    fun near(p: DoubleArray?, x: Double, z: Double) = p != null && Math.abs(p[0] - x) < 0.01 && Math.abs(p[2] - z) < 0.01
    console("a", "give ProbeB minecraft:diamond 7")                                   // B is on backend-a since the earlier send
    console("a", "tp ProbeB 321.5 72.0 -123.5")
    check("data: the teleport command reached the client", await(8000) { near(b.pos, 321.5, -123.5) }, "${b.pos?.toList()}")
    quiet(1500)
    val bLoginsData = b.logins
    a.command("fission send ProbeB backend-b")
    check("data: B moved to backend-b", await(40_000) { b.logins == bLoginsData + 1 }, "logins=${b.logins}")
    check("data: B arrives at the same coordinates (not at spawn)", await(10_000) { near(b.pos, 321.5, -123.5) }, "${b.pos?.toList()}")
    console("b", "data get entity ProbeB Pos"); console("b", "data get entity ProbeB Inventory")
    quiet(1000)
    b.close()                                                                         // disconnect: the backend saves, the master keeps the data
    quiet(2500)
    val b2 = newBot("ProbeB", tag = "ProbeB2")
    check("data: after a reconnect B is back where it left", await(60_000) { b2.logins >= 2 && near(b2.pos, 321.5, -123.5) }, "logins=${b2.logins} ${b2.pos?.toList()}")
    check("data: the reconnect has the right Login flow (limbo, backend, Respawn)", b2.startConfigs == 0)
    worldOk(b2, "reconnected B", 30)

    val gotJoin = await(5000) { a.joinLines() >= joinsBefore + 1 }
    check("announcements: /fission send and the move to the other backend produced no join/leave lines; B's disconnect exactly one 'left', its reconnect one 'joined'",
        gotJoin && a.joinLines() == joinsBefore + 1 && a.leaveLines() == leavesBefore + 1, "joins ${a.joinLines()} (was $joinsBefore), leaves ${a.leaveLines()} (was $leavesBefore)")
    // --- traveling entities and stats: pets, riders, a mount, advancements and stats follow B2 between backends
    val root = System.getProperty("user.dir")
    fun logPath(node: String) = Path.of(root, if (node == "a") "run-a/backend.log" else "run-b/backend.log")
    fun runConsole(node: String, cmd: String): String {
        val f = logPath(node); val before = Files.size(f).toInt(); console(node, cmd)
        val all = Files.readAllBytes(f); return String(all, before, all.size - before, Charsets.ISO_8859_1)
    }
    fun passes(out: String) = out.contains("Test passed")
    fun moveB2(to: String) {
        val l0 = b2.logins; val m0 = a.events.size
        a.command("fission send ProbeB backend-$to")
        await(30_000) { b2.logins == l0 + 1 || a.sysTexts(m0).any { it.contains("already on") } }
        quiet(1500)
    }
    val bu = UUID.nameUUIDFromBytes("OfflinePlayer:ProbeB".toByteArray()); val ownerInts = listOf((bu.mostSignificantBits shr 32).toInt(), bu.mostSignificantBits.toInt(), (bu.leastSignificantBits shr 32).toInt(), bu.leastSignificantBits.toInt()).joinToString(",")
    moveB2("a"); moveB2("b"); moveB2("a")                                       // B2 is on backend-a now, whatever it was before
    // a stone platform at y 99 on both backends (the area is forceloaded there), the player stands on it: pets must not spawn inside terrain
    fun cleanTestWorld() { for (n in listOf("a", "b")) { for (t in listOf("rex", "tom", "sitty", "pup", "dkey", "piggy", "vill", "boaty", "bpig")) runConsole(n, "kill @e[tag=$t]"); runConsole(n, "fill 0 99 0 8 99 8 minecraft:air"); runConsole(n, "forceload remove all") } }
    for (n in listOf("a", "b")) { for (t in listOf("rex", "tom", "sitty", "pup", "dkey", "piggy", "vill", "boaty", "bpig")) runConsole(n, "kill @e[tag=$t]") }   // leftovers of an earlier run
    for (n in listOf("a", "b")) { runConsole(n, "forceload add 0 0 16 16"); runConsole(n, "fill 0 99 0 8 99 8 minecraft:stone"); runConsole(n, "fill 0 100 0 8 104 8 minecraft:air") }
    runConsole("a", "tp ProbeB 4.5 100 4.5"); quiet(1500)
    // pets with real NBT: collar colour, custom name, a potion effect, health below max, a leash held by the player, a baby, a chested donkey with a saddle, a plugin PDC value
    val own = "Owner:[I;$ownerInts]"
    runConsole("a", "execute at ProbeB run summon minecraft:wolf ~ ~ ~ {Tags:[\"rex\"],CustomName:'\"Rex\"',$own,Health:5.0f,CollarColor:11b,active_effects:[{id:\"minecraft:speed\",amplifier:1b,duration:99999}]}")
    runConsole("a", "execute at ProbeB run summon minecraft:cat ~ ~ ~ {Tags:[\"tom\"],CustomName:'\"Tom\"',$own,Sitting:0b,CollarColor:3b}")
    runConsole("a", "execute at ProbeB run summon minecraft:wolf ~ ~ ~ {Tags:[\"sitty\"],CustomName:'\"Sitty\"',$own,Sitting:1b}")
    runConsole("a", "execute at ProbeB run summon minecraft:wolf ~ ~ ~ {Tags:[\"pup\"],CustomName:'\"Pup\"',$own,Age:-24000}")
    runConsole("a", "execute at ProbeB run summon minecraft:donkey ~ ~ ~ {Tags:[\"dkey\"],CustomName:'\"Dk\"',$own,ChestedHorse:1b,Items:[{Slot:2b,id:\"minecraft:diamond\",count:3}],equipment:{saddle:{id:\"minecraft:saddle\",count:1}}}")
    runConsole("a", "fissiontest rider ProbeB")
    runConsole("a", "fissiontest rider ProbeB villager")
    runConsole("a", "fissiontest shoulder ProbeB")
    runConsole("a", "fissiontest leash ProbeB rex")
    runConsole("a", "fissiontest pdc rex mark hello-pdc")
    runConsole("a", "advancement grant ProbeB only minecraft:story/root")
    val subjects = linkedMapOf("wolf Rex (collar, effect, health, leash, pdc)" to "@e[type=minecraft:wolf,tag=rex,limit=1]", "cat Tom" to "@e[type=minecraft:cat,tag=tom,limit=1]",
        "baby wolf Pup" to "@e[type=minecraft:wolf,tag=pup,limit=1]", "donkey with saddle and chest" to "@e[type=minecraft:donkey,tag=dkey,limit=1]",
        "pig riding the player" to "@e[type=minecraft:pig,tag=piggy,limit=1]", "villager riding the player" to "@e[type=minecraft:villager,tag=vill,limit=1]")
    fun dump(node: String, selector: String): Map<String, String>? = Snbt.fromConsole(runConsole(node, "data get entity $selector"))
    val shoulderBefore = Snbt.fromConsole(runConsole("a", "data get entity ProbeB ShoulderEntityLeft").replace("has the following entity data", "x entity data"))
    val dumpsBefore = subjects.mapValues { (_, sel) -> dump("a", sel) }
    check("travel setup: every subject exists on backend-a and its NBT can be read", dumpsBefore.all { it.value != null && it.value!!.size > 10 } && shoulderBefore != null, dumpsBefore.filter { it.value == null || it.value!!.size <= 10 }.keys.toString() + " shoulder=${shoulderBefore != null}")
    check("travel setup: the tamed wolf, a cat, the sitting wolf and a pig riding the player exist on backend-a",
        passes(runConsole("a", "execute if entity @e[type=minecraft:wolf,tag=rex]")) && passes(runConsole("a", "execute if entity @e[type=minecraft:cat,tag=tom]")) && passes(runConsole("a", "execute as @e[type=minecraft:pig,tag=piggy] on vehicle if entity @s[type=minecraft:player,name=ProbeB]")))
    println("[probe] NBT before: Rex has ${dumpsBefore.values.first()?.size} keys, e.g. ${dumpsBefore.values.first()?.keys?.take(40)}")
    runConsole("a", "save-all flush")
    val statsA = Files.readString(Path.of(root, "run-a/world/players/stats/$bu.json"))
    moveB2("b")
    val travelled = await(10_000) { passes(runConsole("b", "execute if entity @e[type=minecraft:wolf,tag=rex]")) }
    check("travel: the tamed wolf arrived on backend-b", travelled)
    check("travel: the cat arrived too", passes(runConsole("b", "execute if entity @e[type=minecraft:cat,tag=tom]")))
    check("travel: exactly one Rex on the target (no duplicates)", runConsole("b", "execute if entity @e[type=minecraft:wolf,tag=rex]").contains("Count: 1"))
    check("travel: the pig that rode the player rides it again", passes(runConsole("b", "execute as @e[type=minecraft:pig,tag=piggy] on vehicle if entity @s[type=minecraft:player,name=ProbeB]")))
    check("travel: the villager that rode the player rides it again", passes(runConsole("b", "execute as @e[type=minecraft:villager,tag=vill] on vehicle if entity @s[type=minecraft:player,name=ProbeB]")))
    // the full NBT before and after: every key must be identical except the documented allowlist (NbtDiff.allowed)
    for ((name, sel) in subjects) {
        val after = dump("b", sel); val before = dumpsBefore[name]
        if (before == null || after == null) { check("travel NBT: $name is readable on both backends", false); continue }
        val d = NbtDiff.diff(before, after)
        println("[probe] NBTDIFF $name: ${d.describe()}")
        check("travel NBT: $name arrives with identical data (UUID, name, owner, collar, effects, health, age, items, leash, BukkitValues ...)", d.clean, d.describe())
    }
    val rexAfter = dump("b", subjects.values.first())
    check("travel NBT: the PDC value set by a plugin survived", rexAfter?.entries?.any { it.key.startsWith("BukkitValues") && it.value.contains("hello-pdc") } == true, rexAfter?.keys?.filter { it.startsWith("BukkitValues") }.toString())
    check("travel NBT: the leash to the player survived", rexAfter?.keys?.any { it.startsWith("Leash") || it.startsWith("leash") } == true && dumpsBefore.values.first()?.keys?.any { it.startsWith("Leash") || it.startsWith("leash") } == true)
    val shoulderAfter = Snbt.fromConsole(runConsole("b", "data get entity ProbeB ShoulderEntityLeft").replace("has the following entity data", "x entity data"))
    val sd = if (shoulderBefore != null && shoulderAfter != null) NbtDiff.diff(shoulderBefore, shoulderAfter) else null
    println("[probe] NBTDIFF shoulder parrot: ${sd?.describe()}")
    check("travel NBT: the shoulder parrot arrives with identical data", sd?.clean == true, sd?.describe() ?: "not readable")
    check("travel: the originals are gone from the source backend", await(10_000) { !passes(runConsole("a", "execute if entity @e[type=minecraft:wolf,tag=rex]")) && !passes(runConsole("a", "execute if entity @e[type=minecraft:cat,tag=tom]")) && !passes(runConsole("a", "execute if entity @e[type=minecraft:pig,tag=piggy]")) && !passes(runConsole("a", "execute if entity @e[type=minecraft:donkey,tag=dkey]")) })
    check("travel: the sitting wolf stays on the source", passes(runConsole("a", "execute if entity @e[type=minecraft:wolf,tag=sitty]")) && !passes(runConsole("b", "execute if entity @e[type=minecraft:wolf,tag=sitty]")))
    check("travel: advancements follow (story/root is done on backend-b)", passes(runConsole("b", "execute if entity @a[name=ProbeB,advancements={minecraft:story/root=true}]")))
    runConsole("b", "save-all flush")
    fun playTime(j: String) = Regex("\"minecraft:play_time\"\\s*:\\s*(\\d+)").find(j)?.groupValues?.get(1)?.toLong() ?: -1
    val statsB = Files.readString(Path.of(root, "run-b/world/players/stats/$bu.json"))
    check("travel: stats follow (play time on backend-b continues from backend-a's ${playTime(statsA)})", playTime(statsA) > 0 && playTime(statsB) >= playTime(statsA), "a=${playTime(statsA)} b=${playTime(statsB)}")
    // a mount with a second passenger: boat on backend-b, a pig on the boat, B2 rides it to backend-a
    runConsole("b", "execute at ProbeB run summon minecraft:oak_boat ~ ~ ~ {Tags:[\"boaty\"],CustomName:'\"Boaty\"'}")
    runConsole("b", "execute at ProbeB run summon minecraft:pig ~ ~ ~ {Tags:[\"bpig\"],CustomName:'\"Bp\"'}")
    runConsole("b", "ride @e[type=minecraft:pig,tag=bpig,limit=1] mount @e[type=minecraft:oak_boat,tag=boaty,limit=1]")
    runConsole("b", "ride ProbeB mount @e[type=minecraft:oak_boat,tag=boaty,limit=1]")
    check("travel setup: B2 rides the boat on backend-b", passes(runConsole("b", "execute as @e[type=minecraft:oak_boat,tag=boaty] on passengers if entity @s[type=minecraft:player,name=ProbeB]")))
    val boatBefore = dump("b", "@e[type=minecraft:oak_boat,tag=boaty,limit=1]"); val bpigBefore = dump("b", "@e[type=minecraft:pig,tag=bpig,limit=1]")
    moveB2("a")
    check("travel: the player arrives riding the same boat on backend-a", await(10_000) { passes(runConsole("a", "execute as @e[type=minecraft:oak_boat,tag=boaty] on passengers if entity @s[type=minecraft:player,name=ProbeB]")) })
    check("travel: the boat's other passenger (a pig) arrives on the boat too", passes(runConsole("a", "execute as @e[type=minecraft:pig,tag=bpig] on vehicle if entity @s[type=minecraft:oak_boat,tag=boaty]")))
    for ((name, before, sel) in listOf(Triple("boat", boatBefore, "@e[type=minecraft:oak_boat,tag=boaty,limit=1]"), Triple("boat passenger pig", bpigBefore, "@e[type=minecraft:pig,tag=bpig,limit=1]"))) {
        val after = dump("a", sel)
        val d = if (before != null && after != null) NbtDiff.diff(before, after) else null
        println("[probe] NBTDIFF $name: ${d?.describe()}")
        check("travel NBT: $name arrives with identical data", d?.clean == true, d?.describe() ?: "not readable")
    }
    check("travel: the boat is gone from the source", !passes(runConsole("b", "execute if entity @e[type=minecraft:oak_boat,tag=boaty]")))
    runConsole("a", "ride ProbeB dismount"); runConsole("a", "gamemode creative ProbeB"); cleanTestWorld(); runConsole("a", "save-all flush"); runConsole("b", "save-all flush")

    val before = a.logins; val mk = a.events.size
    println("[probe] PHASE1-DONE")
    System.out.flush()
    readlnOrNull()   // the caller stops A's backend (backend-b) and writes a line
    check("backend death: the client is put back into the limbo (a new Login in PLAY)", await(30_000) { a.logins > before }, "logins=${a.logins}")
    check("backend death: the limbo position follows, no StartConfiguration", await(10_000) { a.events.drop(mk).any { it.type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK } })
    check("backend death: the player moves on to the surviving backend", await(60_000) { a.logins >= before + 2 }, "logins=${a.logins}")
    check("backend death: the new Login follows the limbo Login (no StartConfiguration anywhere)", a.startConfigs == 0)
    check("backend death: the limbo Login and the next backend Login are each followed by a Respawn", respawnFollowsLogin(a))
    quiet(1500)
}
