package net.fissionmc.net

/**
 * If the JVM has not exited [seconds] after shutdown began (a shutdown hook or a non-daemon thread is stuck), prints every thread's stack, so the hang can be diagnosed
 * from the log, and halts the process so a panel's "stop"/"restart" does not wait for ever. Pick [seconds] longer than the slowest normal shutdown (world saves).
 */
object ShutdownWatchdog {
    fun install(label: String, seconds: Long) {
        Runtime.getRuntime().addShutdownHook(Thread({
            try { Thread.sleep(seconds * 1000) } catch (_: InterruptedException) { return@Thread }
            System.err.println("[$label] shutdown has taken more than ${seconds}s: thread dump, then forcing the exit")
            Thread.getAllStackTraces().forEach { (t, st) -> System.err.println("\"${t.name}\" daemon=${t.isDaemon} state=${t.state}"); st.forEach { System.err.println("\tat $it") } }
            System.err.flush()
            Runtime.getRuntime().halt(1)
        }, "shutdown-watchdog").also { it.isDaemon = true })
    }
}
