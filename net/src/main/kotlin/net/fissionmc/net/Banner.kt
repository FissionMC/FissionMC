package net.fissionmc.net

import net.fissionmc.protocol.MC_VERSION
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The startup banner of the master/backend: logo, role, versions, Java, pid and a few lines of what is configured. */
object Banner {
    private val art = listOf(
        """ _____ _          _             __  __  ____""",
        """|  ___(_)___ ___ (_) ___  _ __ |  \/  |/ ___|""",
        """| |_  | / __/ __|| |/ _ \| '_ \| |\/| | |""",
        """|  _| | \__ \__ \| | (_) | | | | |  | | |___""",
        """|_|   |_|___/___/|_|\___/|_| |_|_|  |_|\____|""",
    )

    /** Version of this distribution: `version=` in the bundled fission-dist.properties, "dev" when run from the build tree. */
    fun version(): String = Banner::class.java.getResourceAsStream("/fission-dist.properties")?.use { s -> java.util.Properties().also { it.load(s) }.getProperty("version") } ?: "dev"

    fun print(role: String, lines: List<String> = emptyList(), out: PrintStream = System.out) {
        art.forEach(out::println)
        out.println("  $role | FissionMC ${version()} | Minecraft $MC_VERSION | Java ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}) | pid ${ProcessHandle.current().pid()}")
        lines.forEach { out.println("  $it") }
        out.println()
    }
}

/** Prefixes every console line with a time and level like Paper does (`[14:05:09 INFO]: [master] ...`); stderr lines are WARN. Call once, after the banner. */
object LogFormat {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    private class Lines(private val target: PrintStream, private val level: String) : OutputStream() {
        private val buf = java.io.ByteArrayOutputStream()
        @Synchronized override fun write(b: Int) { if (b == '\n'.code) flushLine() else if (b != '\r'.code) buf.write(b) }
        @Synchronized private fun flushLine() {
            val line = buf.toString(Charsets.UTF_8); buf.reset()
            target.println(if (line.isEmpty() || line.startsWith("\t") || line.startsWith("[") && line.length > 3 && line[1].isDigit()) line else "[${LocalTime.now().format(clock)} $level]: $line")
        }
        override fun flush() { target.flush() }
    }

    fun install() {
        val out = System.out; val err = System.err
        System.setOut(PrintStream(Lines(out, "INFO"), true, "UTF-8"))
        System.setErr(PrintStream(Lines(err, "WARN"), true, "UTF-8"))
    }
}
