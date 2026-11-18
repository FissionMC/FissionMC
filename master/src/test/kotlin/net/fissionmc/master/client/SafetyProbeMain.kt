package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.packettype.PacketType
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Dev tool for `.dev/scripts/safety-test.sh` (`java -cp <probe classpath> SafetyProbeMainKt <master port> <outDir> <backend-a log>`): one scripted offline client against a master (client safety layer ON) and a
 * backend with the test plugin. It joins, asks the caller (stdin protocol of switch-test.sh: a `[probe] CONSOLE a <cmd>` line, answered by a line on stdin) to make a poisoned item and a poisoned sign chunk on the
 * backend, and checks that it stays connected and gets the sanitised data. The recorded stream is replayed through vanilla's client decoders by the script (backend -PcheckStream).
 */
private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") { println("[probe] ${if (ok) "PASS" else "FAIL"} $name${if (detail.isNotEmpty()) " ($detail)" else ""}"); if (!ok) failures++ }
private fun await(ms: Long, cond: () -> Boolean): Boolean { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(25) }; return cond() }

fun main(args: Array<String>) {
    FissionPacketEventsAPI.install(Side.MASTER)
    val port = args[0].toInt(); Bot.outDir = args[1]; Files.createDirectories(Path.of(args[1])); val backendLog = Path.of(args[2])
    val stdin = System.`in`.bufferedReader()
    fun console(cmd: String, marker: String): Boolean {
        println("[probe] CONSOLE a $cmd"); System.out.flush(); stdin.readLine()
        return await(90_000) { Files.exists(backendLog) && Files.readString(backendLog).contains(marker) }
    }
    val bot = Bot("ProbeA", port)
    try {
        bot.connect()
        check("joins a backend (limbo Login, then the backend's Login)", await(120_000) { bot.logins >= 2 }, "logins=${bot.logins}")
        await(5_000) { bot.chunkSets.lastOrNull()?.isNotEmpty() == true }
        val before = bot.events.size
        check("poisoned items made on the backend", console("fissiontest poison ProbeA", "FISSION-POISON-DONE items"))
        val slot = await(30_000) { bot.events.drop(before).any { it.type == PacketType.Play.Server.SET_SLOT || it.type == PacketType.Play.Server.WINDOW_ITEMS } }
        check("the client got the inventory update", slot)
        Thread.sleep(2000)
        check("still connected after the poisoned items", bot.failed == null && bot.events.drop(before).isNotEmpty(), "${bot.failed}")
        val big = bot.events.drop(before).filter { it.type == PacketType.Play.Server.SET_SLOT || it.type == PacketType.Play.Server.WINDOW_ITEMS }.maxOfOrNull { it.body.size } ?: 0
        check("no inventory packet near the 3 MB of the poisoned item", big in 1..100_000, "largest $big bytes")

        val chunksBefore = bot.chunkSets.lastOrNull()?.size ?: 0; val mid = bot.events.size
        check("poisoned sign made on the backend", console("fissiontest poisonsign ProbeA", "FISSION-POISON-DONE sign"))
        val chunk = await(60_000) { bot.events.drop(mid).any { it.type == PacketType.Play.Server.CHUNK_DATA } }
        check("the client got chunk data around the poisoned sign", chunk, "chunks ${bot.chunkSets.lastOrNull()?.size} (before $chunksBefore)")
        Thread.sleep(4000)
        check("still connected after the poisoned chunk", bot.failed == null && bot.events.size > mid, "${bot.failed}")
        val bigChunk = bot.events.drop(mid).filter { it.type == PacketType.Play.Server.CHUNK_DATA }.maxOfOrNull { it.body.size } ?: 0
        println("[probe] largest chunk packet after the sign: $bigChunk bytes")
        check("no chunk packet carries the 320 KB sign tag", bigChunk in 1..300_000, "largest $bigChunk bytes")
        val ka = await(40_000) { bot.events.drop(mid).any { it.type == PacketType.Play.Server.KEEP_ALIVE } }
        check("keep alives keep coming", ka)
    } catch (t: Throwable) { println("[probe] FAIL scenario aborted: $t"); t.printStackTrace(System.out); failures++ }
    runCatching { bot.close() }
    println("[probe] DONE failures=$failures")
    exitProcess(if (failures == 0) 0 else 1)
}
