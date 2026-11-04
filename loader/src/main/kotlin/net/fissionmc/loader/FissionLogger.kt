package net.fissionmc.loader

import org.spongepowered.asm.logging.ILogger
import org.spongepowered.asm.logging.Level

/** Minimal stderr logger for Mixin; swapped for the server's real logger once it exists. */
class FissionLogger(private val id: String) : ILogger {
    override fun getId() = id
    override fun getType() = "fission"

    private fun out(level: String, msg: String, args: Array<out Any?>) {
        System.err.println("[Mixin/$level] [$id] " + format(msg, args))
    }

    private fun format(msg: String, args: Array<out Any?>): String {
        var s = msg
        for (a in args) {
            if (a is Throwable) continue
            s = s.replaceFirst("{}", a.toString())
        }
        return s
    }

    private fun out(level: String, msg: String, t: Throwable) {
        System.err.println("[Mixin/$level] [$id] $msg")
        t.printStackTrace()
    }

    override fun catching(level: Level, t: Throwable) = out(level.name, "caught", t)
    override fun catching(t: Throwable) = out("ERROR", "caught", t)
    override fun debug(message: String, vararg params: Any?) {}
    override fun debug(message: String, t: Throwable) {}
    override fun error(message: String, vararg params: Any?) = out("ERROR", message, params)
    override fun error(message: String, t: Throwable) = out("ERROR", message, t)
    override fun fatal(message: String, vararg params: Any?) = out("FATAL", message, params)
    override fun fatal(message: String, t: Throwable) = out("FATAL", message, t)
    override fun info(message: String, vararg params: Any?) = out("INFO", message, params)
    override fun info(message: String, t: Throwable) = out("INFO", message, t)
    override fun log(level: Level, message: String, vararg params: Any?) = out(level.name, message, params)
    override fun log(level: Level, message: String, t: Throwable) = out(level.name, message, t)
    override fun <T : Throwable> throwing(t: T): T = t
    override fun trace(message: String, vararg params: Any?) {}
    override fun trace(message: String, t: Throwable) {}
    override fun warn(message: String, vararg params: Any?) = out("WARN", message, params)
    override fun warn(message: String, t: Throwable) = out("WARN", message, t)
}
