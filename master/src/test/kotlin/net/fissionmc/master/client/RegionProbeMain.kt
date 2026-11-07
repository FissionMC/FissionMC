package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.packettype.PacketType
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import io.netty.buffer.Unpooled
import kotlin.system.exitProcess

/**
 * Dev tool: one scripted offline client (ProbeA, admin) against a master with TWO backends and the static region map
 * `regions.overworld=min,min,0,max:backend-a;0,min,max,max:backend-b` (phase 2). Usage: RegionProbe <port> <outDir>.
 * The backends' consoles are driven through marker lines on stdout (`[probe] CONSOLE a|b <command>`) answered by one line on stdin, like SwitchProbeMain.
 */
object RegionProbe {
    private var failures = 0
    private fun check(name: String, ok: Boolean, detail: String = "") { println("[probe] ${if (ok) "PASS" else "FAIL"} $name${if (detail.isNotEmpty()) " ($detail)" else ""}"); if (!ok) failures++ }
    private fun await(ms: Long, cond: () -> Boolean): Boolean { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(25) }; return cond() }
    private fun quiet(ms: Long) { Thread.sleep(ms) }

    @JvmStatic fun main(args: Array<String>) {
        FissionPacketEventsAPI.install(Side.MASTER)
        val port = args[0].toInt(); Bot.outDir = args[1]; Files.createDirectories(Path.of(args[1]))
        val bots = ArrayList<Bot>()
        try { scenario(port, bots) } catch (t: Throwable) { println("[probe] FAIL scenario aborted: $t"); t.printStackTrace(System.out); failures++ }
        bots.forEach { b -> check("${b.name}: no StartConfiguration after the initial configuration", b.startConfigs == 0, "${b.startConfigs}"); check("${b.name}: connection stayed healthy", b.failed == null, b.failed?.toString() ?: "") }
        bots.forEach { runCatching { it.close() } }
        println("[probe] DONE failures=$failures")
        exitProcess(if (failures == 0) 0 else 1)
    }

    private val root = System.getProperty("user.dir")
    private fun logPath(node: String) = Path.of(root, if (node == "a") "run-a/backend.log" else "run-b/backend.log")
    private fun console(node: String, cmd: String) { println("[probe] CONSOLE $node $cmd"); System.out.flush(); readlnOrNull() }
    /** Runs a console command on a backend and returns what the backend log gained meanwhile. */
    private fun run(node: String, cmd: String): String {
        val f = logPath(node); val before = Files.size(f).toInt(); console(node, cmd)
        val all = Files.readAllBytes(f); return String(all, before, all.size - before, Charsets.ISO_8859_1)
    }
    private fun passes(out: String) = out.contains("Test passed")
    private fun count(out: String): Int = Regex("Count: (\\d+)").find(out)?.groupValues?.get(1)?.toInt() ?: 0
    private fun near(p: DoubleArray?, x: Double, z: Double) = p != null && Math.abs(p[0] - x) < 0.6 && Math.abs(p[2] - z) < 0.6
    /** Completed backend switches of ProbeA so far (master log): seamless switches send no Login, so the Login count cannot tell. */
    private fun switches() = Regex("\\[client\\] ProbeA now on ").findAll(masterLog()).count()
    private fun masterLog() = String(Files.readAllBytes(Path.of(root, "run-master/master.log")), Charsets.ISO_8859_1)

    private fun scenario(port: Int, bots: MutableList<Bot>) {
        val a = Bot("ProbeA", port).also { bots += it }.connect()
        val reached = await(120_000) { a.logins >= 2 }
        check("ProbeA reaches a backend", reached, "logins=${a.logins}")
        if (!reached) throw IllegalStateException("the player never got to a backend: the rest of the scenario is pointless")
        quiet(9000)   // the spawn (9,8) is in b's region: the master may move the player once right after the first attach; let that finish
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:ProbeA".toByteArray())
        val ownerInts = listOf((uuid.mostSignificantBits shr 32).toInt(), uuid.mostSignificantBits.toInt(), (uuid.leastSignificantBits shr 32).toInt(), uuid.leastSignificantBits.toInt()).joinToString(",")
        fun sys(from: Int) = a.sysTexts(from)

        // ---- world setup on both backends: nobody is at a border yet. Fast ticks for crops, daylight, creative so the bot may hover.
        for (n in listOf("a", "b")) { run(n, "gamerule random_tick_speed 3000"); run(n, "gamerule randomTickSpeed 3000"); run(n, "time set noon") }
        a.command("gamemode creative ProbeA")   // as the admin on whichever backend hosts the player
        // stand deep inside a's region: this also moves the player to a when the first attach put it on b
        fun tp(x: Double, z: Double, y: Double = 100.0) = a.command("tp ProbeA $x $y $z")
        val startLogins = a.logins
        tp(-40.5, 4.5)
        quiet(6000)
        val settled = await(60_000) { a.pos != null && near(a.pos, -40.5, 4.5) }
        check("start: the player stands at -40.5, 4.5 (region of backend-a)", settled, "${a.pos?.toList()}")
        // which backend is the player on? ask the master command
        var mk = a.events.size
        a.command("fission regions")
        check("/fission regions: owner at the position and the table", await(8000) { sys(mk).any { it.contains("owner backend-a") } && sys(mk).any { it.contains("-> backend-b") } && sys(mk).any { it.contains("-> backend-a") } }, sys(mk).toString())
        check("/fission regions: the player is on its owner (backend-a)", sys(mk).any { it.contains("on backend-a") }, sys(mk).toString())

        // ---- the inventory, a pet, a platform and a test crop set up on backend-a around the player (pet radius 24: the wolf follows)
        run("a", "give ProbeA minecraft:diamond 7")
        for (n in listOf("a", "b")) run(n, "forceload add -64 -32 64 32")
        // a stone platform at y 99 over the whole walk (the wolf and the items need ground); retried until the forceloaded chunks are there on both backends
        for (n in listOf("a", "b")) check("setup: platform on backend-$n", await(240_000) { val o = run(n, "fill -60 99 -3 60 99 10 minecraft:stone"); o.contains("Successfully filled") || o.contains("blocks filled") || o.contains("Changed") || o.contains("No blocks were filled") }.also { quiet(500) })
        for (n in listOf("a", "b")) run(n, "fill -60 100 -3 60 106 10 minecraft:air")
        run("a", "execute at ProbeA run summon minecraft:wolf ~ ~ ~ {Tags:[\"rwolf\"],CustomName:'\"Rex\"',Owner:[I;$ownerInts],CollarColor:11b}")
        // chunks across the border reach the client as ghost chunks: at x=-40.5 (view distance 4) chunk x>=0 are NOT in view, so walk to the near side first
        fun chunkCount(pred: (Int) -> Boolean) = (a.chunkSets.lastOrNull()?.count { pred((it shr 32).toInt()) } ?: 0)

        // ---- walk across x=0 in steps: no switch before the hysteresis margin (8 blocks), exactly one at the border
        val baseLogins = a.logins; val baseSw = switches(); val baseRespawns = a.respawns.get()
        fun moves(from: String, to: String) = Regex("\\[regions\\] ProbeA at \\S+ -?\\d+,-?\\d+ leaves $from for $to").findAll(masterLog()).count()
        val abBefore = moves("backend-a", "backend-b"); val baBefore = moves("backend-b", "backend-a")
        val steps = listOf(-24.5, -16.5, -8.5, -4.5, -0.5, 0.5, 3.5, 6.5)
        for (x in steps) { tp(x, 4.5); quiet(900) }
        check("walk: no switch while the player is less than the margin (8) inside b's region (steps up to x=6.5)", switches() == baseSw && a.logins == baseLogins, "switches=${switches()} (was $baseSw)")
        check("border view: chunks of the other side (cx >= 0) were received while the player was on backend-a at x<=6.5", chunkCount { it >= 0 } > 20, "chunks with cx>=0: ${chunkCount { it >= 0 }}")
        val ghostPlayerChunks = chunkCount { it >= 0 }
        mk = a.events.size
        // the world clock is master-owned: add time on A (day stays day: night mobs would kill the test pet), the player crosses to B and must find the same time there (the new backend applied the master's state before it ticked)
        run("a", "time add 3000"); quiet(2500)
        fun statusMinutes(node: String): Int { val line = run(node, "fission status").lines().lastOrNull { it.contains("world settings (master owned") } ?: return -1; val m = Regex("day (\\d+) (\\d+):(\\d+)").find(line) ?: return -1; return m.groupValues[1].toInt() * 1440 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt() }
        val timeA0 = statusMinutes("a"); val timeB0 = statusMinutes("b")
        check("world settings: /time add 3000 on backend-a is the same time on backend-b before the switch (A $timeA0 B $timeB0 minutes)", timeA0 > 0 && Math.abs(timeA0 - timeB0) <= 2)
        val timePackets0 = a.events.count { it.type == PacketType.Play.Server.TIME_UPDATE }
        tp(9.5, 4.5)
        val entBefore = a.entities.size; val bundlesBefore = a.bundleDelimiters.get(); val removesBefore = a.removePackets.get(); val forgottenBefore = a.forgotten.get()
        val selfBefore = a.selfId
        check("border: crossing x=9.5 completes exactly one switch", await(60_000) { switches() == baseSw + 1 }, "switches=${switches()}")
        check("seamless: NO new Login and NO Respawn for the same-level switch (the client keeps its level and player, no loading screen)", a.logins == baseLogins && a.respawns.get() == baseRespawns, "logins=${a.logins} (was $baseLogins), respawns=${a.respawns.get()} (was $baseRespawns)")
        check("seamless: the old backend's entities were removed in the cut-over (RemoveEntities) inside a bundle", await(10_000) { a.removePackets.get() > removesBefore && a.bundleDelimiters.get() >= bundlesBefore + 2 }, "removes ${a.removePackets.get() - removesBefore}, bundle delimiters ${a.bundleDelimiters.get() - bundlesBefore}")
        check("world settings: the switch sent the client the new backend's time (a TIME_UPDATE after the cut-over)", await(10_000) { a.events.count { it.type == PacketType.Play.Server.TIME_UPDATE } > timePackets0 })
        quiet(1500)
        val timeA1 = statusMinutes("a"); val timeB1 = statusMinutes("b")
        check("world settings: after the switch both backends still show one time (A $timeA1 B $timeB1; before $timeA0)", timeB1 >= timeA0 && Math.abs(timeA1 - timeB1) <= 2)
        check("seamless: the client's own entity id is unchanged", a.selfId == selfBefore, "${a.selfId} (was $selfBefore)")
        check("border: the player arrives at the same coordinates (9.5, 4.5), no spawn teleport", await(15_000) { near(a.pos, 9.5, 4.5) }, "${a.pos?.toList()}")
        quiet(2500)
        check("border: still exactly one switch after settling (no flapping)", switches() == baseSw + 1, "switches=${switches()}")
        check("border: no StartConfiguration", a.startConfigs == 0)
        check("master log: exactly one new 'leaves backend-a for backend-b' line for the crossing", moves("backend-a", "backend-b") == abBefore + 1, "${moves("backend-a", "backend-b")} (was $abBefore)")
        mk = a.events.size
        a.command("fission regions")
        check("/fission regions after the switch: owner backend-b and on backend-b", await(8000) { sys(mk).any { it.contains("owner backend-b") && it.contains("on backend-b") } }, sys(mk).toString())
        check("inventory: the 7 diamonds arrived on backend-b", await(10_000) { run("b", "clear ProbeA minecraft:diamond 0").contains("Found 7") })
        check("pet: the wolf Rex exists exactly once on backend-b", await(20_000) { count(run("b", "execute if entity @e[type=minecraft:wolf,tag=rwolf]")) == 1 })
        check("pet: ... and is gone from backend-a (no duplicate)", await(20_000) { !passes(run("a", "execute if entity @e[type=minecraft:wolf,tag=rwolf]")) })
        check("border view: after the switch the client received chunks of a's side (cx < 0) as ghost chunks from backend-b", chunkCount { it < 0 } > 10, "chunks with cx<0: ${chunkCount { it < 0 }}")

        // ---- the transplant kept the client's world right: pet entity, ids, chunks, interaction
        val petOut = run("b", "data get entity @e[type=minecraft:wolf,tag=rwolf,limit=1] UUID")
        val ints = Regex("\\[I;([^\\]]*)\\]").find(petOut)!!.groupValues[1].split(',').map { it.trim().toInt() }
        val petUuid = UUID(((ints[0].toLong() and 0xFFFFFFFFL) shl 32) or (ints[1].toLong() and 0xFFFFFFFFL), ((ints[2].toLong() and 0xFFFFFFFFL) shl 32) or (ints[3].toLong() and 0xFFFFFFFFL))
        fun petIds() = a.entityUuids.entries.filter { it.value == petUuid }.map { it.key }
        check("seamless: the client holds exactly one entity with the pet's UUID after the transplant (the pet arrived as a normal AddEntity)", await(20_000) { petIds().size == 1 }, "entities ${a.entities.size}, pet entities ${petIds().size}")
        val wolfId = petIds().firstOrNull() ?: -1
        check("seamless: the wolf has a master-assigned client id (>= 1000000), not the backend's own", wolfId >= 1_000_000, "id $wolfId")
        quiet(1500)
        check("seamless: the wolf keeps the same client id (no re-adds) while the player stays", petIds().firstOrNull() == wolfId)
        run("b", "data modify entity @e[type=minecraft:wolf,tag=rwolf,limit=1] Sitting set value 0b")
        run("b", "execute at ProbeA run tp @e[type=minecraft:wolf,tag=rwolf,limit=1] ~1 ~ ~")
        quiet(500)
        val ib = Unpooled.buffer(); VarInts.write(ib, wolfId); VarInts.write(ib, 0); ib.writeByte(0); ib.writeBoolean(false)
        a.c.sendRaw(PlayIds.frame(net.fissionmc.master.seamless.PlayPacketIds.s("Interact"), ByteArray(ib.readableBytes()).also { ib.readBytes(it) }))
        check("seamless: an interaction with the pet by its CLIENT id reaches the right backend (the tamed wolf sits)", await(10_000) { run("b", "data get entity @e[type=minecraft:wolf,tag=rwolf,limit=1] Sitting").contains("1b") })
        val here = a.chunkSets.lastOrNull().orEmpty()
        val cx = Math.floor(a.pos!![0] / 16).toInt(); val cz = Math.floor(a.pos!![2] / 16).toInt()
        val wanted = (-4..4).flatMap { dx -> (-4..4).map { dz -> ((cx + dx).toLong() shl 32) or ((cz + dz).toLong() and 0xffffffffL) } }
        check("seamless: the client holds every chunk of the new backend's view around the player (${wanted.size}) and nothing far away", await(20_000) { val h = a.chunkSets.lastOrNull().orEmpty(); wanted.all { it in h } && h.size <= 130 }, "have ${a.chunkSets.lastOrNull()?.size}, missing ${wanted.count { it !in a.chunkSets.last() }}")

        // ---- hysteresis the other way: wiggling around the border changes nothing, then walking far inside a's region moves it back
        val l1 = a.logins; val sw1 = switches()
        for (x in listOf(2.5, -3.5, 3.5, -4.5, 1.5, -7.5)) { tp(x, 4.5); quiet(900) }
        quiet(1500)
        check("hysteresis: wiggling between x=-7.5 and 3.5 on b's side causes no switch", switches() == sw1, "switches=${switches()} (was $sw1)")
        tp(-14.5, 4.5)
        check("walk back: at x=-14.5 the player returns to backend-a (one more switch, again without Login/Respawn)", await(60_000) { switches() == sw1 + 1 } && await(15_000) { near(a.pos, -14.5, 4.5) } && a.logins == baseLogins && a.respawns.get() == baseRespawns, "switches=${switches()} logins=${a.logins} ${a.pos?.toList()}")
        quiet(2500)
        check("walk back: exactly one more switch, no flapping", switches() == sw1 + 1, "switches=${switches()}")
        check("master log: exactly one new 'leaves backend-b for backend-a' line for the way back", moves("backend-b", "backend-a") == baBefore + 1, "${moves("backend-b", "backend-a")} (was $baBefore)")
        check("pet again: still exactly one Rex in total after the way back", run("a", "execute if entity @e[type=minecraft:wolf,tag=rwolf]").let { count(it) } + run("b", "execute if entity @e[type=minecraft:wolf,tag=rwolf]").let { count(it) } == 1)

        // ---- ghost chunks: crops grow only where the chunk is owned; natural spawning off in ghosts (counters in the backends' stats lines)
        for (x in listOf(-20, 20)) for (n in listOf("a", "b")) { run(n, "setblock $x 99 0 minecraft:farmland[moisture=7]"); run(n, "setblock $x 100 0 minecraft:wheat[age=0]") }
        check("ghost: wheat on a's own side (x=-20) grows on backend-a, the owner", await(40_000) { passes(run("a", "execute if block -20 100 0 minecraft:wheat[age=7]")) })
        check("ghost: wheat on b's side (x=20) grows on backend-b, the owner", await(40_000) { passes(run("b", "execute if block 20 100 0 minecraft:wheat[age=7]")) })
        // (the block change of the owner replicates to the other backend's ghost chunk, so the other side shows it too: the counters below prove who ticked)

        // ---- block changes replicate to ghost chunks in both directions
        run("b", "setblock 24 101 0 minecraft:gold_block"); run("a", "setblock -24 101 0 minecraft:iron_block")
        check("replication: a block set on backend-b in its own region appears in backend-a's ghost chunk", await(15_000) { passes(run("a", "execute if block 24 101 0 minecraft:gold_block")) })
        check("replication: a block set on backend-a appears in backend-b's ghost chunk", await(15_000) { passes(run("b", "execute if block -24 101 0 minecraft:iron_block")) })
        run("a", "setblock 26 101 0 minecraft:emerald_block")   // set in a ghost chunk on a (b's region): still replicates to the owner
        check("replication: a block set in a ghost chunk reaches the owner", await(15_000) { passes(run("b", "execute if block 26 101 0 minecraft:emerald_block")) })

        // ---- entity handover: an item flies over the border, a pig is teleported over it, an item on b's side flies back; counts by tag, one copy in total
        run("a", "summon minecraft:pig -4 100 0 {Tags:[\"hpig\"],NoGravity:1b,NoAI:1b,CustomName:'\"Hp\"'}")
        run("a", "summon minecraft:item -2 100 1 {Item:{id:\"minecraft:diamond\",count:2},Tags:[\"hitem\"],Motion:[2.5d,0.0d,0.0d],PickupDelay:32767s}")
        quiet(1500)
        check("handover (item flying across): exactly one copy in total, on backend-b", await(25_000) { count(run("b", "execute if entity @e[type=minecraft:item,tag=hitem]")) == 1 && !passes(run("a", "execute if entity @e[type=minecraft:item,tag=hitem]")) },
            "a=${count(run("a", "execute if entity @e[type=minecraft:item,tag=hitem]"))} b=${count(run("b", "execute if entity @e[type=minecraft:item,tag=hitem]"))}")
        run("a", "tp @e[type=minecraft:pig,tag=hpig] 6 100 0")
        check("handover (pig teleported over the border): spawned on backend-b", await(25_000) { count(run("b", "execute if entity @e[type=minecraft:pig,tag=hpig]")) == 1 })
        check("handover: the pig is removed from backend-a after the ack, one copy in total", await(25_000) { !passes(run("a", "execute if entity @e[type=minecraft:pig,tag=hpig]")) })
        quiet(2500)
        val pigA = count(run("a", "execute if entity @e[type=minecraft:pig,tag=hpig]")); val pigB = count(run("b", "execute if entity @e[type=minecraft:pig,tag=hpig]"))
        check("handover: still exactly one pig after 2.5 s (no flip back, no duplicate)", pigA + pigB == 1, "a=$pigA b=$pigB")
        val u1 = run("b", "data get entity @e[type=minecraft:pig,tag=hpig,limit=1] UUID")
        run("b", "tp @e[type=minecraft:pig,tag=hpig] -6 100 0")
        check("handover back (b -> a): the pig is on backend-a again, once", await(25_000) { count(run("a", "execute if entity @e[type=minecraft:pig,tag=hpig]")) == 1 && !passes(run("b", "execute if entity @e[type=minecraft:pig,tag=hpig]")) })
        val u2 = run("a", "data get entity @e[type=minecraft:pig,tag=hpig,limit=1] UUID")
        check("handover: same UUID before and after", u1.contains("[I;") && Regex("\\[I;[^\\]]*\\]").find(u1)?.value == Regex("\\[I;[^\\]]*\\]").find(u2)?.value, "$u1 | $u2")

        // ---- cleanup of the test world (the platform is in the user's worlds)
        for (n in listOf("a", "b")) { run(n, "kill @e[tag=hpig]"); run(n, "kill @e[tag=hitem]"); run(n, "kill @e[tag=rwolf]"); run(n, "fill -60 99 -3 60 106 10 minecraft:air"); run(n, "setblock 24 101 0 minecraft:air"); run(n, "setblock -24 101 0 minecraft:air") }
        run("a", "setblock 26 101 0 minecraft:air"); run("b", "setblock 26 101 0 minecraft:air")
        for (n in listOf("a", "b")) { run(n, "forceload remove all"); run(n, "gamerule random_tick_speed 3"); run(n, "gamerule randomTickSpeed 3") }
        quiet(1000)
        quiet(7000)   // the backends print their region counters every 5 s when they changed
        println("[probe] ghost chunks seen by the client on the first side: $ghostPlayerChunks")
    }
}
