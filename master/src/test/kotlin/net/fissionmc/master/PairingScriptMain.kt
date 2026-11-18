package net.fissionmc.master

import net.fissionmc.net.Keys
import net.fissionmc.net.pairing.PairingCode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * The driver of `.dev/scripts/pairing-test.sh` (all waiting lives here, the script only prepares and calls it): a fresh master and a fresh backend with `node.security: true` and no keys.
 * Checks: the backend prints a code (console, not latest.log), a wrong and an expired code are refused, the right code pairs and the link comes up, keys are owner-only, both restart and reconnect
 * with pinned keys (no new code), `node remove` drops the link and the backend is refused. Usage: PairingScriptMainKt <repo root> <scratch dir>
 */
private const val BACKEND_PORT = 25587
private const val ID = "pair-be"

private class Server(val dir: File, val log: File, val build: () -> ProcessBuilder, val pattern: String) {
    var proc: Process? = null
    fun start() {
        proc = build().directory(File("/")).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).redirectInput(ProcessBuilder.Redirect.PIPE).start()
    }
    fun send(line: String) { proc!!.outputStream.apply { write((line + "\n").toByteArray()); flush() } }
    fun text(): String = if (log.exists()) log.readText() else ""
    /** The JVM of this server (found by working directory and command line, never by name). */
    fun jvm(): ProcessHandle? = ProcessHandle.allProcesses().filter { h ->
        runCatching { Files.readSymbolicLink(Path.of("/proc/${h.pid()}/cwd")).toFile().canonicalPath == dir.canonicalPath && (h.info().commandLine().orElse("")).contains(pattern) }.getOrDefault(false)
    }.findFirst().orElse(null)
    fun stop() {
        val j = jvm(); val p = proc
        if (j != null) { j.destroy(); if (!runCatching { j.onExit().get(90, TimeUnit.SECONDS) }.isSuccess) j.destroyForcibly() }
        if (p != null && !p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
    }
}

private fun waitFor(what: String, seconds: Long, cond: () -> Boolean): Boolean {
    val end = System.nanoTime() + seconds * 1_000_000_000L
    while (System.nanoTime() < end) { if (cond()) return true; LockSupport.parkNanos(200_000_000L) }
    println("[pairing-test] gave up waiting for: $what")
    return false
}

fun main(args: Array<String>) {
    val root = File(args[0]); val scr = File(args[1]); scr.mkdirs()
    val java = "/usr/lib/jvm/java-25-openjdk/bin/java"
    val cp = File(root, "master/build/probe.classpath").readText().trim()
    val masterDir = File(root, "run-master"); val beDir = File(root, "run-pairing")
    var failed = 0
    fun check(name: String, ok: Boolean) { println("[pairing-test] ${if (ok) "PASS" else "FAIL"}: $name"); if (!ok) failed++ }

    val master = Server(masterDir, File(scr, "master.log"), { ProcessBuilder("bash", "-c", "cd ${masterDir.path} && exec $java -cp '$cp' net.fissionmc.master.MasterMainKt fission.yml") }, "MasterMainKt")
    val backend = Server(beDir, File(scr, "backend.log"), { ProcessBuilder("bash", "-c", "cd ${root.path} && JAVA_HOME=/usr/lib/jvm/java-25-openjdk bash gradlew :backend:run -PrunDir=run-pairing -Pport=$BACKEND_PORT --no-daemon -q") }, "net.fissionmc.loader.Bootstrap")
    try {
        master.start()
        if (!waitFor("master up", 120) { master.text().contains("Done (") }) { println(master.text().takeLast(1500)); throw IllegalStateException("master did not start") }
        check("master prints its key fingerprint and pairing on", Regex("master key fingerprint [0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}; pairing on").containsMatchIn(master.text()))
        backend.start()
        val codeLine = Regex("FP1-[0-9A-Z-]+")
        check("backend prints a pairing code", waitFor("pairing code", 400) { codeLine.containsMatchIn(backend.text()) })
        val code = codeLine.find(backend.text())?.value ?: throw IllegalStateException("no code")
        check("the code line is on the console", backend.text().lines().any { it.contains("PAIRING CODE") && it.contains("valid") })
        val latest = File(beDir, "logs/latest.log")
        check("the code is NOT in logs/latest.log", !latest.exists() || !latest.readText().contains(code.take(20)))

        master.send("node add garbage")
        check("a garbage code is refused", waitFor("garbage refused", 20) { master.text().contains("not added: this is not a FissionMC pairing code") })
        val mangled = code.substring(0, 12) + (if (code[12] == 'A') 'B' else 'A') + code.substring(13)
        master.send("node add $mangled")
        check("a code with a copy error is refused (checksum)", waitFor("checksum", 20) { master.text().contains("checksum mismatch") })
        val expired = PairingCode(ID, Keys.generate().public, ByteArray(16) { 9 }, System.currentTimeMillis() - 10 * 60_000).encode()
        master.send("node add $expired")
        check("an expired code is refused", waitFor("expired", 20) { master.text().contains("not added: the code expired") })
        val authorized = File(masterDir, "fission-authorized.keys")
        check("nothing was authorized by the bad codes", !authorized.exists() || authorized.readText().isBlank())

        master.send("node add $code")
        check("the right code is accepted with node id and fingerprint", waitFor("accepted", 20) { master.text().contains("node $ID authorized, key fingerprint") })
        check("the backend pairs", waitFor("paired", 300) { backend.text().contains("PAIRED with the master") })
        check("the link comes up", waitFor("joined", 120) { master.text().contains("$ID joined") })
        check("the backend is fully up", waitFor("backend done", 600) { backend.text().contains("Done (") })
        check("master audit says paired", master.text().contains("[audit] paired node $ID"))
        val pin = File(beDir, ".fission-master-key")
        check("the backend pinned the master key into .fission-master-key", pin.exists() && pin.readText().lines().any { it.isNotBlank() && !it.startsWith("#") })
        fun ownerOnly(f: File) = Files.getPosixFilePermissions(f.toPath()).none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }
        check("key files and the authorized file are owner-only", listOf(pin, authorized, File(beDir, "fission-node.key"), File(masterDir, "fission-node.key")).all { it.exists() && ownerOnly(it) })
        check("the fingerprints on both consoles agree", Regex("master key fingerprint ([0-9A-Z-]{14})").find(master.text())?.groupValues?.get(1).let { fp -> fp != null && backend.text().contains("fingerprint $fp") })
        master.send("node list"); check("node list shows it online", waitFor("list", 20) { Regex("$ID +[0-9A-Z-]{14} +online").containsMatchIn(master.text()) })
        master.send("node add $code"); check("the same code cannot be used twice", waitFor("reuse", 20) { master.text().contains("already used") })

        // restart both: pinned keys, no pairing
        backend.stop(); master.stop(); master.log.delete(); backend.log.delete()
        master.start(); check("master restarts", waitFor("master up again", 120) { master.text().contains("Done (") })
        backend.start()
        check("the backend reconnects with the pinned keys", waitFor("rejoined", 400) { master.text().contains("$ID joined") })
        check("no new pairing code after the restart", !codeLine.containsMatchIn(backend.text()) && backend.text().contains("Master key pinned"))
        check("the backend is up again", waitFor("backend done 2", 600) { backend.text().contains("Done (") })

        // revoke
        master.send("node remove $ID")
        check("node remove closes the live link", waitFor("left", 30) { master.text().contains("$ID left") && master.text().contains("node $ID removed") })
        check("the backend is refused and prints a fresh code to pair again", waitFor("refused", 120) { backend.text().contains("not authorized") && codeLine.containsMatchIn(backend.text()) })
        check("the master audits the refusal", waitFor("audit", 60) { master.text().contains("[audit] failed handshake") && master.text().contains("unknown node $ID") })
        master.send("node list"); check("node list is empty", waitFor("empty", 20) { master.text().contains("no authorized nodes") })
    } catch (t: Throwable) { println("[pairing-test] ERROR: $t"); failed++ }
    finally { backend.stop(); master.stop() }
    println("[pairing-test] ${if (failed == 0) "ALL PASSED" else "$failed FAILED"} (logs in $scr)")
    System.exit(if (failed == 0) 0 else 1)
}
