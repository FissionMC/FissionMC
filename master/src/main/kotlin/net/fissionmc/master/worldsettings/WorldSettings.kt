package net.fissionmc.master.worldsettings

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random

/** The `world-settings:` section of fission.yml. */
class WorldSettingsConfig(
    val enabled: Boolean = true, val time: Boolean = true, val weather: Boolean = true, val gamerules: Boolean = true, val difficulty: Boolean = true,
    val border: Boolean = true, val spawn: Boolean = true, val gamemode: Boolean = true, val syncIntervalTicks: Int = 20,
) {
    /** The enabled categories (all empty when the whole feature is off). */
    val categories: Set<String> get() = if (!enabled) emptySet() else buildSet {
        if (time) add("time"); if (weather) add("weather"); if (gamerules) add("gamerules"); if (difficulty) add("difficulty"); if (border) add("border"); if (spawn) add("spawn"); if (gamemode) add("gamemode")
    }

    companion object {
        fun from(root: ConfigSection): WorldSettingsConfig {
            val s = root.section("world-settings")
            val interval = s.int("sync-interval-ticks", 20).also { if (it !in 1..1200) throw ConfigException("world-settings.sync-interval-ticks: expected 1-1200, got $it") }
            return WorldSettingsConfig(s.bool("enabled", true), s.bool("time", true), s.bool("weather", true), s.bool("gamerules", true), s.bool("difficulty", true),
                s.bool("border", true), s.bool("spawn", true), s.bool("gamemode", true), interval)
        }
        fun categoryOf(key: String): String? = when {
            key.startsWith("clock:") || key == "gametime" -> "time"
            key == "weather" -> "weather"
            key.startsWith("rule:") -> "gamerules"
            key == "difficulty" -> "difficulty"
            key == "gametype" -> "gamemode"
            key == "spawn" -> "spawn"
            key.startsWith("border:") -> "border"
            else -> null
        }
    }
}

/**
 * The canonical world settings of the network (clocks, weather, game rules, difficulty, border, spawn, default game mode): a flat `key -> text` map plus a small simulation of
 * the parts that move on their own (clock ticks, game time, the weather cycle), advanced by the master at 20 tps. Pure logic (no I/O except [save]/[load]); the hub drives it.
 * Key formats: see WorldSettingsMessages.kt.
 */
class WorldSettingsModel(val cfg: WorldSettingsConfig, private val rng: Random = Random.Default) {
    class Clock(var total: Long, var partial: Float, var rate: Float, var paused: Boolean) {
        fun text() = "$total,$partial,$rate,$paused"
        companion object { fun parse(s: String): Clock? = s.split(',').takeIf { it.size == 4 }?.let { p -> runCatching { Clock(p[0].toLong(), p[1].toFloat(), p[2].toFloat(), p[3].toBooleanStrict()) }.getOrNull() } }
    }
    class Weather(var clear: Int, var rain: Int, var thunder: Int, var raining: Boolean, var thundering: Boolean) {
        fun text() = "$clear,$rain,$thunder,$raining,$thundering"
        companion object { fun parse(s: String): Weather? = s.split(',').takeIf { it.size == 5 }?.let { p -> runCatching { Weather(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toBooleanStrict(), p[4].toBooleanStrict()) }.getOrNull() } }
    }

    private val plain = LinkedHashMap<String, String>()
    val clocks = LinkedHashMap<String, Clock>()
    var gameTime = -1L; private set
    var weather: Weather? = null; private set
    var ticks = 0L; private set
    @Volatile var dirty = false

    fun enabled(key: String): Boolean = WorldSettingsConfig.categoryOf(key)?.let { it in cfg.categories } == true

    private fun rule(id: String, default: Boolean) = plain["rule:$id"]?.let { it == "true" } ?: default

    /** Stores [value] under [key] (parsed into the simulation where the key moves on its own). False = disabled category, unknown key or an unparsable value (nothing changed). */
    fun set(key: String, value: String): Boolean {
        if (!enabled(key)) return false
        when {
            key.startsWith("clock:") -> { clocks[key.removePrefix("clock:")] = Clock.parse(value) ?: return false }
            key == "gametime" -> { gameTime = value.toLongOrNull() ?: return false }
            key == "weather" -> { weather = Weather.parse(value) ?: return false }
            else -> plain[key] = value
        }
        dirty = true
        return true
    }

    /** The current text of [key] (null when the master has no value yet). */
    fun get(key: String): String? = when {
        key.startsWith("clock:") -> clocks[key.removePrefix("clock:")]?.text()
        key == "gametime" -> gameTime.takeIf { it >= 0 }?.toString()
        key == "weather" -> weather?.text()
        else -> plain[key]
    }

    /** Adopts the keys of [state] the master has no value for yet (first start: the first backend seeds the network). Returns the adopted keys. */
    fun seed(state: Map<String, String>): List<String> = state.entries.filter { enabled(it.key) && get(it.key) == null && set(it.key, it.value) }.map { it.key }

    /** Every enabled key with its current text (the state backends adopt). */
    fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        clocks.forEach { (id, c) -> if (enabled("clock:$id")) out["clock:$id"] = c.text() }
        if (gameTime >= 0 && enabled("gametime")) out["gametime"] = gameTime.toString()
        weather?.let { if (enabled("weather")) out["weather"] = it.text() }
        plain.forEach { (k, v) -> if (enabled(k)) out[k] = v }
        return out
    }

    /** One server tick of the parts that move by themselves. */
    fun tick() {
        ticks++
        if (cfg.time) {
            if (gameTime >= 0) gameTime++
            if (rule("advance_time", true)) for (c in clocks.values) if (!c.paused) { c.partial += c.rate; val full = Math.floor(c.partial.toDouble()).toInt(); c.partial -= full; c.total += full }
        }
        val w = weather
        if (cfg.weather && w != null && rule("advance_weather", true)) {
            if (w.clear > 0) { w.clear--; w.thunder = if (w.thundering) 0 else 1; w.rain = if (w.raining) 0 else 1; w.thundering = false; w.raining = false }
            else {
                if (w.thunder > 0) { if (--w.thunder == 0) w.thundering = !w.thundering } else w.thunder = if (w.thundering) rng.nextInt(3600, 15601) else rng.nextInt(12000, 180001)
                if (w.rain > 0) { if (--w.rain == 0) w.raining = !w.raining } else w.rain = if (w.raining) rng.nextInt(12000, 24001) else rng.nextInt(12000, 180001)
            }
        }
        if (cfg.time || cfg.weather) dirty = true
    }

    /** `key<TAB>value` lines (values never contain a tab or a newline). */
    fun serialize(): String = (snapshotAll()).entries.joinToString("\n", postfix = "\n") { "${it.key}\t${it.value}" }
    private fun snapshotAll(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        clocks.forEach { (id, c) -> out["clock:$id"] = c.text() }
        if (gameTime >= 0) out["gametime"] = gameTime.toString()
        weather?.let { out["weather"] = it.text() }
        out.putAll(plain); return out
    }

    fun load(text: String) {
        for (l in text.lineSequence()) { val i = l.indexOf('\t'); if (i <= 0) continue; val k = l.substring(0, i); val v = l.substring(i + 1)
            when {
                k.startsWith("clock:") -> Clock.parse(v)?.let { clocks[k.removePrefix("clock:")] = it }
                k == "gametime" -> v.toLongOrNull()?.let { gameTime = it }
                k == "weather" -> Weather.parse(v)?.let { weather = it }
                WorldSettingsConfig.categoryOf(k) != null -> plain[k] = v
            } }
        dirty = false
    }

    fun save(file: Path) {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, serialize()); Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); dirty = false
    }

    /** One line for `/fission status`: day count, time of day of the main clock, weather. */
    fun summary(): String {
        val c = clocks["minecraft:overworld"] ?: clocks.values.firstOrNull()
        val clock = if (c == null) "no clock" else "day ${c.total / 24000} ${"%02d:%02d".format(((c.total % 24000) / 1000 + 6) % 24, ((c.total % 1000) * 60 / 1000))}${if (c.paused || !rule("advance_time", true)) " (stopped)" else ""}"
        val w = weather?.let { if (it.thundering) "thunder" else if (it.raining) "rain" else "clear" } ?: "no weather"
        return "$clock, $w"
    }
}
