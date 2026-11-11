package net.fissionmc.master.client

import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Orchestrator of .dev/scripts/net-test.sh (all waiting lives here, not in shell): starts the standalone offline backend (`gradlew :backend:run`, run dir
 * run-net-test), waits for "Done (", runs the NetBot session against it, asks the NetProbe plugin for its event counts and prints
 * "NETCHECK <event> FIRED n | MISSING" for every line of the expected-events file. Arguments: <root> <scratch dir> <expected file> <port>.
 */
fun main(args: Array<String>) {
    FissionPacketEventsAPI.install(Side.MASTER)
    val root = File(args[0]); val scr = File(args[1]).also { it.mkdirs() }; val expected = File(args[2]); val port = args[3].toInt()
    val log = File(scr, "net.log")
    val pb = ProcessBuilder("bash", "gradlew", ":backend:run", "-PrunDir=run-net-test", "-Pport=$port", "--no-daemon", "-q")
        .directory(root).redirectErrorStream(true).redirectOutput(log)
    val proc = pb.start()
    val stdin = proc.outputStream.bufferedWriter()
    fun send(cmd: String) { stdin.write(cmd + "\n"); stdin.flush() }
    fun waitLog(text: String, ms: Long): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) { if (log.exists() && log.readText(Charsets.ISO_8859_1).contains(text)) return true; if (!proc.isAlive) return false; Thread.sleep(250) }
        return false
    }
    var failures = 0
    try {
        if (!waitLog("Done (", 600_000)) { println("backend did not start: ${log.readLines().filter { it.contains("Exception") || it.contains("injection") }.take(4)}"); failures++ }
        else {
            Thread.sleep(1000)
            if (args.getOrNull(4) == "dump") Thread {   // diagnosis: thread dump of the backend 10 s into the bot session
                Thread.sleep(10_000)
                runCatching {
                    val jcmd = File(System.getProperty("java.home"), "bin/jcmd").path
                    val list = ProcessBuilder(jcmd, "-l").redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
                    val pid = list.firstOrNull { it.startsWith("net.fissionmc.loader.Bootstrap", 0) || it.substringAfter(" ").startsWith("net.fissionmc.loader.Bootstrap") }?.substringBefore(' ')
                    File(scr, "threads.txt").writeText(list.joinToString("\n") + "\n")
                    if (pid != null) ProcessBuilder(jcmd, pid, "Thread.print").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(scr, "threads.txt"))).start().waitFor()
                    if (pid != null) ProcessBuilder(jcmd, pid, "Thread.dump_to_file", "-format=text", File(scr, "threads-vt.txt").path).redirectErrorStream(true).start().waitFor()
                }
            }.also { it.isDaemon = true }.start()
            try { runNetBot(port, scr.path) } catch (t: Throwable) { println("[nettest] bot failed: $t") }
            Thread.sleep(1500)
            send("fissiontest netreport")
            waitLog("NETREPORT DONE", 60_000)
            val counts = log.readLines(Charsets.ISO_8859_1).filter { it.contains("NETCOUNT ") }.associate { val p = it.substringAfter("NETCOUNT ").trim().split(' '); p[0] to (p[1].toIntOrNull() ?: 0) }
            File(scr, "net.counts").writeText(counts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" })
            var missing = 0
            for (ev in expected.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()) {
                val n = counts[ev] ?: 0
                if (n > 0) println("NETCHECK $ev FIRED $n") else { println("NETCHECK $ev MISSING"); missing++ }
            }
            println("NETCHECK missing=$missing")
            println("NETSEEN " + counts.filterKeys { !it.contains(':') }.entries.joinToString(" ") { "${it.key}=${it.value}" }.take(3000))
            val bad = Regex("Could not pass event|Exception in server tick|ClassCastException|NoSuchMethodError|NoSuchFieldError|AbstractMethodError|VerifyError|IncompatibleClassChangeError|Encountered an unexpected exception|Critical injection failure|InvalidInjectionException")
            val badLines = log.readLines(Charsets.ISO_8859_1).withIndex().filter { bad.containsMatchIn(it.value) }
            if (badLines.isNotEmpty()) { println("FAIL ${badLines.size} exception lines"); badLines.take(4).forEach { println(it.value.take(300)) }; failures++ }
        }
    } finally {
        runCatching { send("stop") }
        if (!proc.waitFor(120, TimeUnit.SECONDS)) { proc.descendants().forEach { it.destroyForcibly() }; proc.destroyForcibly() }
        File(scr, "net.keep.log").writeText(log.readText(Charsets.ISO_8859_1))
    }
    println("failures=$failures")
    kotlin.system.exitProcess(0)
}
