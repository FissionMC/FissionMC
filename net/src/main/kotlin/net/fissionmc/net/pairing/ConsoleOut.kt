package net.fissionmc.net.pairing

import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Writes a line to the process's real standard output, past the logging framework (a server's System.out is redirected into its logger and so into `logs/latest.log`). A pairing code is a one-time
 * secret that must be on the console only; this is the one place that prints one.
 */
object ConsoleOut {
    @Volatile var override: ((String) -> Unit)? = null   // tests
    fun secretLine(line: String) {
        override?.let { it(line); return }
        try { FileOutputStream(FileDescriptor.out).apply { write((line + "\n").toByteArray()); flush() } } catch (_: Exception) { }
    }
}
