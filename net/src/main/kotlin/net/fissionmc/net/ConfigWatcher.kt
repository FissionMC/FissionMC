package net.fissionmc.net

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.Closeable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Watches a config file and calls [onChange] (old text, new text) a moment after it was written (editors write in several steps, so events are debounced).
 * Only real content changes fire; [onChange] runs on the watcher's own thread and must not throw (exceptions are logged and swallowed).
 */
class ConfigWatcher(private val file: Path, private val log: (String) -> Unit, private val onChange: (String, String) -> Unit) : Closeable {
    private val dir = file.toAbsolutePath().parent
    private val name = file.fileName
    @Volatile private var last: String = read() ?: ""
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "config-watcher").also { it.isDaemon = true } }
    private val service = FileSystems.getDefault().newWatchService()
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var open = true

    private fun read(): String? = try { Files.readString(file) } catch (_: Exception) { null }

    fun start(): ConfigWatcher {
        dir.register(service, ENTRY_CREATE, ENTRY_MODIFY)
        Thread({
            while (open) {
                val key = try { service.take() } catch (_: Exception) { break }
                if (key.pollEvents().any { (it.context() as? Path)?.fileName == name }) schedule()
                if (!key.reset()) break
            }
        }, "config-watcher-events").also { it.isDaemon = true }.start()
        return this
    }

    @Synchronized private fun schedule() {
        pending?.cancel(false)
        pending = exec.schedule({ check() }, 400, TimeUnit.MILLISECONDS)
    }

    private fun check() {
        val now = read() ?: return
        val old = last
        if (now == old) return
        last = now
        try { onChange(old, now) } catch (t: Throwable) { log("config reload failed: $t") }
    }

    override fun close() { open = false; runCatching { service.close() }; exec.shutdownNow() }

    companion object {
        /** Every leaf of the YAML as `a.b.c -> value text` (lists count as one value). */
        fun flatten(text: String): Map<String, String> {
            val out = java.util.TreeMap<String, String>()
            fun walk(p: String, v: Any?) {
                if (v is Map<*, *>) v.forEach { (k, x) -> walk(if (p.isEmpty()) "$k" else "$p.$k", x) } else out[p] = v.toString()
            }
            walk("", Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text))
            return out
        }

        /** The dotted keys whose values differ between two texts (present in one only counts as a change). */
        fun changedKeys(old: String, new: String): Set<String> {
            val a = flatten(old); val b = flatten(new)
            return (a.keys + b.keys).filter { a[it] != b[it] }.toSortedSet()
        }
    }
}
