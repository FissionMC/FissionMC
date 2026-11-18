package net.fissionmc.master.client

import net.fissionmc.master.seamless.GhostHub
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/**
 * Dev tool (`.dev/scripts/vanish-test.sh`): "going between servers makes entities vanish". Two scripted clients against a master with the static region map
 * (backend-a: x < 0, backend-b: x >= 0). A ring of recognisable entities (armor stands, cows, husks with NoAI, dropped items) stands on both sides of x=0; ProbeB stands on backend-b
 * (a ghost player for ProbeA while ProbeA is on backend-a), ProbeA walks (or, with the argument `tp`, teleports) across the border back and forth. A sampler thread records, for BOTH clients, which
 * entities (by uuid: the real one or the ghost the master shows, mapped to one logical label) the client knows, every 15 ms. For every crossing it prints what the client knew just before, right after,
 * +1 s, +3 s, +10 s, and every entity that was known before and went away afterwards, with how long it was gone. FAIL = gone for longer than [FLICKER_MS], or missing 10 s after the switch.
 * Usage: VanishProbe <port> <outDir> [walk|tp] [cycles]. Console commands for the backends go through marker lines like RegionProbe (`[probe] CONSOLE a|b <command>`, one line back on stdin).
 */
object VanishProbe {
    private var failures = 0
    private fun check(name: String, ok: Boolean, detail: String = "") { println("[probe] ${if (ok) "PASS" else "FAIL"} $name${if (detail.isNotEmpty()) " ($detail)" else ""}"); if (!ok) failures++ }
    private fun await(ms: Long, cond: () -> Boolean): Boolean { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(20) }; return cond() }
    private fun quiet(ms: Long) { Thread.sleep(ms) }
    private fun nowMs() = System.nanoTime() / 1_000_000

    /** A gap shorter than this is not a vanish (one tick of the real client, bot polling jitter). */
    private const val FLICKER_MS = 250L
    private const val WINDOW_MS = 12_000L
    /** Entities within this many blocks of every spot the client stands on are expected to be visible at all times (the master's ghost range is 128; dropped items track 96 blocks in vanilla). */
    private const val EXPECT_RANGE = 118.0
    private const val EXPECT_ITEM_RANGE = 92.0

    @JvmStatic fun main(args: Array<String>) {
        FissionPacketEventsAPI.install(Side.MASTER)
        val port = args[0].toInt(); Bot.outDir = args[1]; Files.createDirectories(Path.of(args[1]))
        val mode = args.getOrNull(2) ?: "walk"; val cycles = args.getOrNull(3)?.toInt() ?: 3
        val bots = ArrayList<Bot>()
        try { scenario(port, bots, mode, cycles) } catch (t: Throwable) { println("[probe] FAIL scenario aborted: $t"); t.printStackTrace(System.out); failures++ }
        bots.forEach { b -> check("${b.name}: no StartConfiguration after the initial configuration", b.startConfigs == 0, "${b.startConfigs}"); check("${b.name}: connection stayed healthy", b.failed == null, b.failed?.toString() ?: "") }
        bots.forEach { runCatching { it.close() } }
        println("[probe] DONE failures=$failures")
        exitProcess(if (failures == 0) 0 else 1)
    }

    private val root = System.getProperty("user.dir")
    private fun logPath(node: String) = Path.of(root, if (node == "a") "run-a/backend.log" else "run-b/backend.log")
    private fun console(node: String, cmd: String) { println("[probe] CONSOLE $node $cmd"); System.out.flush(); readlnOrNull() }
    private fun run(node: String, cmd: String): String {
        val f = logPath(node); val before = Files.size(f).toInt(); console(node, cmd)
        val all = Files.readAllBytes(f); return String(all, before, all.size - before, Charsets.ISO_8859_1)
    }
    private fun masterLog() = String(Files.readAllBytes(Path.of(root, "run-master/master.log")), Charsets.ISO_8859_1)
    private fun switches(name: String) = Regex("\\[client\\] $name now on ").findAll(masterLog()).count()
    private fun lastNode(name: String) = Regex("\\[client\\] $name now on (\\S+)").findAll(masterLog()).lastOrNull()?.groupValues?.get(1)
    private fun near(p: DoubleArray?, x: Double, z: Double) = p != null && Math.abs(p[0] - x) < 1.0 && Math.abs(p[2] - z) < 1.0

    // ---- the entities of the ring
    class Label(val key: String, val kind: String, val name: String, val x: Double, val z: Double, val real: UUID, val item: Boolean)
    private fun ints(u: UUID) = listOf((u.mostSignificantBits shr 32).toInt(), u.mostSignificantBits.toInt(), (u.leastSignificantBits shr 32).toInt(), u.leastSignificantBits.toInt()).joinToString(",")
    private val xs = listOf(-100.0, -70.0, -45.0, -25.0, -8.0, 8.0, 25.0, 45.0, 70.0, 100.0)
    private val labels: List<Label> by lazy {
        val out = ArrayList<Label>(); var i = 0
        fun uuid(kind: Int) = UUID(0x56414e4953480000L or (i.toLong() and 0xffff), 0x1000L + kind)
        for (x in xs) {
            val tag = if (x < 0) "W${(-x).toInt()}" else "E${x.toInt()}"
            out += Label("armor_stand $tag", "armor_stand", "AS-$tag", x, -10.5, uuid(0), false); i++
            out += Label("cow $tag", "cow", "COW-$tag", x, 6.5, uuid(1), false); i++
            out += Label("husk $tag", "husk", "HUSK-$tag", x, 12.5, uuid(2), false); i++
            if (Math.abs(x) <= 70) { out += Label("item $tag", "item", "ITEM-$tag", x + 2.0, 0.5, uuid(3), true); i++ }
        }
        out
    }
    private fun summonCmd(l: Label): String {
        val u = "UUID:[I;${ints(l.real)}]"
        val common = "$u,Tags:[\"vanish\"],NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b"
        val nbt = when (l.kind) {
            "armor_stand" -> "{$common,Marker:1b,CustomName:'\"${l.name}\"',CustomNameVisible:1b}"
            "item" -> "{$common,Item:{id:\"minecraft:diamond\",count:1},PickupDelay:32767s,Age:-32768s}"
            else -> "{$common,NoAI:1b,Silent:1b,CustomName:'\"${l.name}\"',CustomNameVisible:1b}"
        }
        return "summon minecraft:${l.kind} ${l.x} 100.0 ${l.z} $nbt"
    }

    /** What a client knows over time: every change of the set of client entity ids per logical label (the real entity or its ghost, any id), sampled every 15 ms. */
    class Timeline(private val bot: Bot, private val labelOf: Map<UUID, String>) {
        class Change(val t: Long, val label: String, val ids: Set<Int>)
        val changes = CopyOnWriteArrayList<Change>()
        private val cur = HashMap<String, Set<Int>>()
        @Volatile private var stop = false
        private val thread = Thread({ while (!stop) { sample(); try { Thread.sleep(15) } catch (_: InterruptedException) { } } }, "timeline-${bot.name}").also { it.isDaemon = true }
        fun start() { thread.start() }
        fun stop() { stop = true }
        private fun sample() {
            val t = nowMs(); val by = HashMap<String, MutableSet<Int>>()
            for ((id, u) in bot.entityUuids) labelOf[u]?.let { by.getOrPut(it) { java.util.TreeSet() } += id }
            for (l in cur.keys.toList() + by.keys) {
                val now: Set<Int> = by[l] ?: emptySet(); val had = cur[l] ?: emptySet()
                if (now != had) { changes += Change(t, l, now); if (now.isEmpty()) cur.remove(l) else cur[l] = now }
            }
        }
        fun idsAt(label: String, t: Long): Set<Int> { var r: Set<Int> = emptySet(); for (c in changes) { if (c.t > t) break; if (c.label == label) r = c.ids }; return r }
        fun known(t: Long): Map<String, Set<Int>> { val m = HashMap<String, Set<Int>>(); for (c in changes) { if (c.t > t) break; if (c.ids.isEmpty()) m.remove(c.label) else m[c.label] = c.ids }; return m }
    }

    private class Gap(val label: String, val from: Long, val to: Long, val stillGone: Boolean)

    private fun gapsOf(tl: Timeline, label: String, from: Long, to: Long): List<Gap> {
        val out = ArrayList<Gap>(); var goneSince: Long? = if (tl.idsAt(label, from).isEmpty()) from else null
        for (c in tl.changes) {
            if (c.t <= from || c.t > to || c.label != label) continue
            if (c.ids.isEmpty() && goneSince == null) goneSince = c.t
            else if (c.ids.isNotEmpty() && goneSince != null) { out += Gap(label, goneSince, c.t, false); goneSince = null }
        }
        if (goneSince != null) out += Gap(label, goneSince, to, true)
        return out
    }

    private fun scenario(port: Int, bots: MutableList<Bot>, mode: String, cycles: Int) {
        val a = Bot("ProbeA", port).also { bots += it }.connect()
        check("ProbeA reaches a backend", await(120_000) { a.logins >= 2 }, "logins=${a.logins}")
        val b = Bot("ProbeB", port).also { bots += it }.connect()
        check("ProbeB reaches a backend", await(120_000) { b.logins >= 2 }, "logins=${b.logins}")
        quiet(9000)   // the first attach may be followed by a region move
        val uA = UUID.nameUUIDFromBytes("OfflinePlayer:ProbeA".toByteArray()); val uB = UUID.nameUUIDFromBytes("OfflinePlayer:ProbeB".toByteArray())

        // ---- world: calm, a platform for the two players, the ring of entities on the owners (x < 0: backend-a, x >= 0: backend-b)
        for (n in listOf("a", "b")) { run(n, "gamerule random_tick_speed 0"); run(n, "gamerule doMobSpawning false"); run(n, "gamerule spawn_mobs false"); run(n, "time set noon"); run(n, "kill @e[tag=vanish]") }
        for (n in listOf("a", "b")) { run(n, "forceload add -128 -32 128 32"); run(n, "gamemode creative ProbeA"); run(n, "gamemode creative ProbeB") }
        for (n in listOf("a", "b")) check("setup: platform on backend-$n", await(240_000) { val o = run(n, "fill -48 99 -12 64 99 16 minecraft:stone"); o.contains("Successfully filled") || o.contains("blocks filled") || o.contains("Changed") || o.contains("No blocks were filled") }.also { quiet(500) })
        var summoned = 0
        for (l in labels) {
            val node = if (l.x < 0) "a" else "b"
            if (await(120_000) { run(node, summonCmd(l)).contains("Summoned new") }) summoned++ else println("[probe] setup: could not summon ${l.key} on backend-$node")
        }
        check("setup: all ${labels.size} ring entities summoned on their owners", summoned == labels.size, "$summoned")

        // ---- the clients: ProbeB stands on backend-b at x=40.5, ProbeA starts on backend-a at x=-20.5
        fun tpBoth(who: String, x: Double, z: Double) { for (n in listOf("a", "b")) run(n, "tp $who $x 100.0 $z") }
        tpBoth("ProbeB", 40.5, -4.5); tpBoth("ProbeA", -20.5, 4.5)
        check("start: ProbeB stands at 40.5 on backend-b", await(60_000) { near(b.pos, 40.5, -4.5) && lastNode("ProbeB") == "backend-b" }, "${b.pos?.toList()} ${lastNode("ProbeB")}")
        check("start: ProbeA stands at -20.5 on backend-a", await(60_000) { near(a.pos, -20.5, 4.5) && lastNode("ProbeA") == "backend-a" }, "${a.pos?.toList()} ${lastNode("ProbeA")}")

        // logical label of every uuid a client can see: the real one, the master's ghost of the entity, and the players (real and ghost)
        val labelOf = HashMap<UUID, String>()
        for (l in labels) { labelOf[l.real] = l.key; labelOf[GhostHub.entityGhostUuidOf(l.real)] = l.key }
        labelOf[uA] = "player ProbeA"; labelOf[GhostHub.ghostUuidOf(uA)] = "player ProbeA"; labelOf[uB] = "player ProbeB"; labelOf[GhostHub.ghostUuidOf(uB)] = "player ProbeB"
        val tlA = Timeline(a, labelOf); val tlB = Timeline(b, labelOf); tlA.start(); tlB.start()
        quiet(6000)   // the ghosts of the other side settle
        val known0 = tlA.known(nowMs()).keys; val knownB0 = tlB.known(nowMs()).keys
        println("[probe] STATE before the first crossing: ProbeA knows ${known0.size} labelled entities (of ${labels.size} + 1 player), ProbeB knows ${knownB0.size}; ids ${a.entities.size}/${b.entities.size} total")

        val snapshots = StringBuilder()
        var legNo = 0
        val logins0 = a.logins; val respawns0 = a.respawns.get()
        val legsSummary = ArrayList<String>()
        for (cycle in 1..cycles) for (dir in listOf(1, -1)) {
            legNo++
            val from = if (dir > 0) "backend-a" else "backend-b"; val to = if (dir > 0) "backend-b" else "backend-a"
            val target = 20.5 * dir
            val base = switches("ProbeA"); val tStart = nowMs()
            if (mode == "tp") a.command("tp ProbeA $target 100.0 4.5") else a.moveV = doubleArrayOf(0.215 * dir, 0.0, 0.0)
            val switched = await(90_000) { switches("ProbeA") == base + 1 }; val tSw = nowMs()
            check("leg $legNo ($from -> $to): the crossing completes exactly one switch", switched, "switches ${switches("ProbeA") - base}")
            if (mode != "tp") await(30_000) { a.pos?.let { if (dir > 0) it[0] >= target else it[0] <= target } == true }
            a.moveV = null
            // the window runs on: wait until tSw + WINDOW_MS
            val remaining = tSw + WINDOW_MS - nowMs(); if (remaining > 0) quiet(remaining)
            val tEnd = tSw + WINDOW_MS
            check("leg $legNo: still exactly one switch after the window (no flapping)", switches("ProbeA") == base + 1, "switches ${switches("ProbeA") - base}")
            check("leg $legNo: ProbeA ended on $to", lastNode("ProbeA") == to, lastNode("ProbeA") ?: "?")
            check("leg $legNo: seamless (no Login, no Respawn)", a.logins == logins0 && a.respawns.get() == respawns0, "logins=${a.logins} (was $logins0) respawns=${a.respawns.get()} (was $respawns0)")

            for ((who, tl, bot, xsStood) in listOf(Quad("ProbeA", tlA, a, doubleArrayOf(-20.5, 20.5)), Quad("ProbeB", tlB, b, doubleArrayOf(40.5)))) {
                val expected = labels.filter { l -> xsStood.all { sx -> Math.hypot(l.x - sx, l.z - 4.5) <= (if (l.item) EXPECT_ITEM_RANGE else EXPECT_RANGE) } }.map { it.key } +
                    (if (who == "ProbeA") "player ProbeB" else "player ProbeA")
                val offsets = listOf("before" to -300L, "after" to 300L, "+1s" to 1000L, "+3s" to 3000L, "+10s" to 10_000L)
                println("[probe] LEG $legNo $from -> $to, $who's view (expected ${expected.size} labelled entities; ${bot.entities.size} entity ids known at the end)")
                snapshots.append("== leg $legNo $from -> $to, $who\n")
                for ((nm, off) in offsets) {
                    val k = tl.known(tSw + off); val miss = expected.filter { it !in k }
                    println("[probe]   %-6s knows %d/%d expected%s".format(nm, expected.count { it in k }, expected.size, if (miss.isEmpty()) "" else ", missing: " + miss.joinToString(", ")))
                    snapshots.append("$nm: ").append(k.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.joinToString("/")}" }).append('\n')
                }
                var bad = 0; var idChanged = 0
                for (lab in expected) {
                    val before = tl.idsAt(lab, tSw - 300); val after = tl.idsAt(lab, tEnd)
                    val gaps = gapsOf(tl, lab, tSw - 300, tEnd).filter { before.isNotEmpty() || it.from > tSw - 300 }
                    val wasKnown = before.isNotEmpty()
                    if (wasKnown) for (g in gaps) {
                        val len = g.to - g.from
                        if (len >= FLICKER_MS || g.stillGone) {
                            bad++
                            println("[probe]   VANISHED $lab: gone ${if (g.stillGone) "from" else "for ${len} ms,"} t${fmt(g.from - tSw)} ${if (g.stillGone) "and not back at the end of the window" else "to t${fmt(g.to - tSw)}"} (ids before ${before.joinToString("/")}, after ${after.joinToString("/").ifEmpty { "-" }})")
                        } else if (len > 0) println("[probe]   flicker $lab: gone ${len} ms at t${fmt(g.from - tSw)} (below the ${FLICKER_MS} ms threshold)")
                    }
                    if (after.isEmpty() && !wasKnown) { bad++; println("[probe]   MISSING $lab: not known before the switch and still not known ${WINDOW_MS / 1000} s after it") }
                    else if (after.isEmpty() && wasKnown && gaps.none { it.stillGone }) { bad++; println("[probe]   MISSING $lab at the end of the window") }
                    if (wasKnown && after.isNotEmpty() && before != after) idChanged++
                }
                println("[probe]   client ids of $idChanged labelled entities changed over the switch (a ghost has another id and uuid than the real entity: expected for the entities that cross sides)")
                check("leg $legNo $who: no entity that was known vanished or is missing (gone >= $FLICKER_MS ms)", bad == 0, "$bad problems")
                legsSummary += "leg $legNo $who: $bad problems"
            }
            if (legNo < cycles * 2) quiet(3000)
        }
        tlA.stop(); tlB.stop()
        Files.writeString(Path.of(Bot.outDir, "vanish-snapshots.txt"), snapshots.toString())
        val csv = StringBuilder("client,t_ms,label,ids\n")
        for ((who, tl) in listOf("ProbeA" to tlA, "ProbeB" to tlB)) for (c in tl.changes) csv.append("$who,${c.t},${c.label},${c.ids.joinToString("/")}\n")
        Files.writeString(Path.of(Bot.outDir, "vanish-timeline.csv"), csv.toString())
        println("[probe] SUMMARY ${legsSummary.joinToString("; ")}")
        for (n in listOf("a", "b")) run(n, "kill @e[tag=vanish]")
    }

    private data class Quad(val who: String, val tl: Timeline, val bot: Bot, val xs: DoubleArray)
    private fun fmt(ms: Long) = "%+.2fs".format(ms / 1000.0)
}
