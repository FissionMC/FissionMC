package net.fissionmc.net

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

/** A bad `fission.yml`: the message names the key path (`chat.global.max-length`) and what was wrong. */
class ConfigException(message: String) : IllegalArgumentException(message)

/**
 * One mapping of a `fission.yml` (the root or a nested section). Typed getters CONSUME their key; whatever nobody consumed is reported by [finish] as an unknown key with
 * its full path, so a typo or a key that no longer exists stops the process at start instead of being ignored. A value of the wrong type fails with the path, too.
 * Missing and `null` values mean "use the default".
 */
class ConfigSection internal constructor(val path: String, private val map: Map<String, Any?>) {
    private val consumed = HashSet<String>()
    private val children = ArrayList<ConfigSection>()
    private val cache = HashMap<String, ConfigSection>()

    private fun full(k: String) = if (path.isEmpty()) k else "$path.$k"
    private fun raw(k: String): Any? { consumed += k; return map[k] }
    private fun bad(k: String, expected: String, v: Any): Nothing = throw ConfigException("${full(k)}: expected $expected, got ${describe(v)}")
    private fun describe(v: Any) = when (v) { is String -> "the text '$v'"; is Map<*, *> -> "a section"; is List<*> -> "a list"; else -> "$v" }

    /** Does the key exist with a value (does not consume it)? */
    fun has(k: String): Boolean = map[k] != null

    fun string(k: String, default: String): String = optString(k) ?: default

    /** A text value; numbers and booleans are accepted as their text; null when missing. An empty string stays an empty string. */
    fun optString(k: String): String? = when (val v = raw(k)) { null -> null; is String -> v; is Number, is Boolean -> v.toString(); else -> bad(k, "a text", v) }

    fun int(k: String, default: Int): Int = when (val v = raw(k)) { null -> default; is Int -> v; is Long -> v.toInt(); else -> bad(k, "a whole number", v) }
    fun long(k: String, default: Long): Long = when (val v = raw(k)) { null -> default; is Int -> v.toLong(); is Long -> v; else -> bad(k, "a whole number", v) }
    fun bool(k: String, default: Boolean): Boolean = when (val v = raw(k)) { null -> default; is Boolean -> v; else -> bad(k, "true or false", v) }

    /** A list of texts; a single scalar counts as a list of one. Empty when missing. */
    fun stringList(k: String): List<String> = when (val v = raw(k)) {
        null -> emptyList(); is List<*> -> v.mapIndexed { i, e -> when (e) { is String -> e; is Number, is Boolean -> e.toString(); else -> throw ConfigException("${full(k)}[$i]: expected a text, got ${describe(e ?: "nothing")}") } }
        is String -> listOf(v); is Number, is Boolean -> listOf(v.toString()); else -> bad(k, "a list of texts", v)
    }

    /** A raw list (elements are String/Number/Boolean), for values such as `[min, min, 0, max]`; null when missing. */
    fun anyList(k: String): List<Any?>? = when (val v = raw(k)) { null -> null; is List<*> -> v; else -> bad(k, "a list", v) }

    /** The nested section [k] (an empty one when missing). Asking twice gives the same object, so several readers share what was consumed. */
    fun section(k: String): ConfigSection = cache.getOrPut(k) {
        when (val v = raw(k)) {
            null -> ConfigSection(full(k), emptyMap())
            is Map<*, *> -> ConfigSection(full(k), v.entries.associate { (a, b) -> a.toString() to b })
            else -> bad(k, "a section", v)
        }
    }.also { if (it !in children) children += it }

    /** A list of sections (`- owner: backend-a` items). Empty when missing. */
    fun sectionList(k: String): List<ConfigSection> = when (val v = raw(k)) {
        null -> emptyList()
        is List<*> -> v.mapIndexed { i, e -> if (e is Map<*, *>) ConfigSection("${full(k)}[$i]", e.entries.associate { (a, b) -> a.toString() to b }).also { children += it } else throw ConfigException("${full(k)}[$i]: expected a section, got ${describe(e ?: "nothing")}") }
        else -> bad(k, "a list of sections", v)
    }

    /** The names of the keys of this section (does not consume anything): for sections whose keys are data (dimension names). */
    fun keys(): List<String> = map.keys.toList()

    /** Full paths of every key nobody read (this section and the sections read from it). */
    fun unknownKeys(): List<String> = map.keys.filter { it !in consumed }.map { full(it) } + children.flatMap { it.unknownKeys() }

    /** Fails with the path of every key that was not read: a typo, or a key this version does not have. */
    fun finish() { val u = unknownKeys(); if (u.isNotEmpty()) throw ConfigException("unknown key${if (u.size > 1) "s" else ""}: ${u.joinToString(", ")}") }
}

/** Reads `fission.yml` (YAML only: there is no `fission.properties` support). */
object FissionConfig {
    fun parse(text: String, source: String = "fission.yml"): ConfigSection {
        val data = try { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) } catch (e: Exception) { throw ConfigException("$source: not valid YAML: ${e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: e}") }
        return when (data) {
            null -> ConfigSection("", emptyMap())
            is Map<*, *> -> ConfigSection("", data.entries.associate { (a, b) -> a.toString() to b })
            else -> throw ConfigException("$source: the top level must be a mapping of sections (node:, client:, ...)")
        }
    }

    /** The file's sections; a missing file is an empty configuration. */
    fun load(file: Path): ConfigSection = if (Files.isRegularFile(file)) parse(Files.readString(file), file.fileName.toString()) else ConfigSection("", emptyMap())

    /** Writes [template] (the commented default file from the resources) when [file] does not exist. Returns true if it was written. */
    fun writeDefaultIfMissing(file: Path, template: String): Boolean {
        if (Files.exists(file)) return false
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(file, template); return true
    }
}
