package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.core.GlobalPos
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.gamerules.GameRule
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.clock.WorldClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Backend side of the master-owned world settings: clocks, game time, weather, game rules, difficulty, default game mode, world spawn, world
 * border. Local changes are reported to the master (clock and game rule changes through two small mixins on the vanilla setters, everything else by comparing with the previous
 * sample every few ticks); the master's changes are applied on the server thread BEFORE the tick. A thread-local style guard keeps applied changes from being reported again.
 */
object WorldSettingsBackend {
    private val log = Logger.getLogger("FissionMC")
    private val inbox = ConcurrentLinkedQueue<NodeMessage>()
    @Volatile private var server: MinecraftServer? = null
    private var categories: Set<String> = emptySet()
    @Volatile private var synced = false
    private var helloSent = false
    private var applying = false
    /** What master and this backend agree on per key (set when we apply or report). */
    private val known = HashMap<String, String>()
    /** Previous local samples of the polled keys: a change versus these is a local change. */
    private val lastLocal = HashMap<String, String>()
    private var ticks = 0L
    val reported = AtomicLong(); val applied = AtomicLong(); val adopted = AtomicLong()
    /** Effective explicit applications per key (the scripted test counts these: every change must be applied exactly once). */
    val appliedByKey = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var lastStateNanos = 0L
    private var lastStats = ""
    /** Keys this backend reported lately: a master state sent BEFORE the master saw the change would revert it, so such keys are not overwritten by snapshots for a while (the master answers within milliseconds). */
    private val holdUntil = HashMap<String, Long>()

    fun onMessage(m: NodeMessage) { if (m is WorldSettingsState || m is WorldSettingApply) inbox.add(m) }
    fun onReconnect() { inbox.clear(); helloSent = false; synced = false }

    /** One line for `/fission status`. */
    fun summary(): String {
        val s = server ?: return "world settings: not started"
        if (!synced) return "world settings: not synced with the master (${if (categories.isEmpty()) "waiting" else "?"})"
        return "world settings (master owned: ${categories.joinToString(",")}): ${describeTime(s)}; last master state ${(System.nanoTime() - lastStateNanos) / 1_000_000} ms ago; reported $reported, applied $applied, drift adoptions $adopted"
    }
    private fun describeTime(s: MinecraftServer): String = try {
        val h = s.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).listElements().toList().firstOrNull()
        val t = h?.let { clockState(s, it).total } ?: 0L
        val w = s.weatherData
        "day ${t / 24000} ${"%02d:%02d".format(((t % 24000) / 1000 + 6) % 24, ((t % 1000) * 60 / 1000))}, ${if (w.isThundering) "thunder" else if (w.isRaining) "rain" else "clear"}"
    } catch (_: Throwable) { "?" }

    // ------------------------------------------------------------------ hooks (server thread)
    @JvmStatic fun onClockChanged(holder: Holder<WorldClock>) {
        val s = server ?: return
        if (applying || !synced || "time" !in categories) return
        val key = "clock:" + clockId(holder)
        val v = clockText(s, holder)
        if (known[key] == v) return
        known[key] = v; report(key, v)
    }

    @JvmStatic fun onRuleSet(rules: GameRules, rule: GameRule<*>, value: Any?) {
        val s = server ?: return
        if (applying || !synced || "gamerules" !in categories || rules !== s.globalGameRules) return
        val key = "rule:" + rule.id()
        @Suppress("UNCHECKED_CAST") val v = try { (rule as GameRule<Any>).serialize(rules.get(rule as GameRule<Any>)) } catch (_: Throwable) { return }   // an invalid rule (not in this level's set): nothing changed
        if (known[key] == v) return
        known[key] = v; report(key, v)
    }

    private fun report(key: String, value: String) {
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        link.send(WorldSettingChange(key, value)); reported.incrementAndGet(); holdUntil[key] = ticks + 60
        log.info("[fission] world setting reported: $key = ${value.take(80)}")
    }

    // ------------------------------------------------------------------ reading local state
    private fun clockId(h: Holder<WorldClock>) = h.unwrapKey().get().identifier().toString()
    /** The compile-only surface does not expose ServerClockManager.ServerClockInstance: read it by reflection (public methods). */
    private class ClockSt(val total: Long, val partial: Float, val rate: Float, val paused: Boolean)
    private val getInstance by lazy { net.minecraft.world.clock.ServerClockManager::class.java.getMethod("getInstance", Holder::class.java) }
    private fun clockState(s: MinecraftServer, h: Holder<WorldClock>): ClockSt {
        val i = getInstance.invoke(s.clockManager(), h); val c = i.javaClass
        fun m(n: String) = c.getMethod(n).also { it.isAccessible = true }.invoke(i)
        return ClockSt(m("totalTicks") as Long, m("partialTick") as Float, m("rate") as Float, m("isPaused") as Boolean)
    }
    private fun clockText(s: MinecraftServer, h: Holder<WorldClock>): String { val i = clockState(s, h); return "${i.total},${i.partial},${i.rate},${i.paused}" }
    private fun clocks(s: MinecraftServer): List<Holder<WorldClock>> = s.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).listElements().toList().map { it as Holder<WorldClock> }

    private fun weatherText(s: MinecraftServer): String { val w = s.weatherData; return "${w.clearWeatherTime},${w.rainTime},${w.thunderTime},${w.isRaining},${w.isThundering}" }
    private fun difficultyText(s: MinecraftServer) = "${s.worldData.difficulty.id},${s.worldData.isDifficultyLocked}"
    private fun spawnText(s: MinecraftServer): String { val r = s.respawnData; val p = r.pos(); return "${r.dimension().identifier()}|${p.x}|${p.y}|${p.z}|${r.yaw()}|${r.pitch()}" }
    private fun borderText(level: net.minecraft.server.level.ServerLevel): String { val b = level.worldBorder; return "${b.centerX},${b.centerZ},${b.lerpTarget},${b.lerpTime},${b.damagePerBlock},${b.safeZone},${b.warningBlocks},${b.warningTime}" }
    private fun borderSig(t: String) = t.split(',').let { listOf(it[0], it[1], it[2], it[4], it[5], it[6], it[7]) }.joinToString(",")

    private fun rules(s: MinecraftServer): Map<String, String> {
        val r = s.globalGameRules; val out = LinkedHashMap<String, String>()
        r.availableRules().forEach { rule -> @Suppress("UNCHECKED_CAST") out["rule:" + rule.id()] = (rule as GameRule<Any>).serialize(r.get(rule as GameRule<Any>)) }
        return out
    }

    private fun readAll(s: MinecraftServer): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        clocks(s).forEach { out["clock:" + clockId(it)] = clockText(s, it) }
        out["gametime"] = s.overworld().gameTime.toString()
        out["weather"] = weatherText(s)
        out.putAll(rules(s))
        out["difficulty"] = difficultyText(s); out["gametype"] = s.defaultGameType.id.toString(); out["spawn"] = spawnText(s)
        s.allLevels.forEach { out["border:" + it.dimension().identifier()] = borderText(it) }
        return out
    }

    // ------------------------------------------------------------------ per tick (before the server ticks)
    fun tick(server: MinecraftServer) {
        this.server = server
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        ticks++
        if (!helloSent) { helloSent = true; link.send(WorldSettingsHello(readAll(server))); lastLocal.clear(); lastLocal.putAll(readAll(server)) }
        if (synced && !inbox.isEmpty()) poll(server)   // local changes made since the last sample go out BEFORE a master snapshot can overwrite them
        while (true) {
            when (val m = inbox.poll() ?: break) {
                is WorldSettingsState -> { categories = m.categories.split(',').filter { it.isNotEmpty() }.toSet(); lastStateNanos = System.nanoTime(); applyState(server, m.state, exact = false); if (!synced) { synced = true; log.info("[fission] world settings synced with the master (${categories.joinToString(",")})") } }
                is WorldSettingApply -> { if ("" != m.key && cat(m.key) in categories) applyKey(server, m.key, m.value, exact = true) }
            }
        }
        if (synced && ticks % 10 == 0L) poll(server)
        if (ticks % 100 == 0L) { val l = "reported=$reported applied=$applied adopted=$adopted synced=$synced"; if (l != lastStats) { lastStats = l; log.info("[fission] world settings: $l (${describeTime(server)})") } }
    }

    private fun cat(key: String): String = when { key.startsWith("clock:") || key == "gametime" -> "time"; key == "weather" -> "weather"; key.startsWith("rule:") -> "gamerules"; key == "difficulty" -> "difficulty"; key == "gametype" -> "gamemode"; key == "spawn" -> "spawn"; key.startsWith("border:") -> "border"; else -> "" }

    /** Reports local changes of the polled keys (weather, difficulty, default game mode, spawn, border). */
    private fun poll(s: MinecraftServer) {
        if (applying) return
        fun changed(key: String, now: String, jump: Boolean) {
            val prev = lastLocal.put(key, now)
            if (prev != null && prev != now && jump) { known[key] = now; report(key, now) }
        }
        if ("weather" in categories) {
            val w = s.weatherData; val now = weatherText(s); val prev = lastLocal["weather"]
            val p = prev?.split(',')
            val jump = p != null && (w.isRaining.toString() != p[3] || w.isThundering.toString() != p[4] || w.clearWeatherTime > p[0].toInt() + 1 || w.rainTime > p[1].toInt() + 1 || w.thunderTime > p[2].toInt() + 1)
            changed("weather", now, jump)
        }
        if ("difficulty" in categories) { val now = difficultyText(s); changed("difficulty", now, true) }
        if ("gamemode" in categories) { val now = s.defaultGameType.id.toString(); changed("gametype", now, true) }
        if ("spawn" in categories) { val now = spawnText(s); changed("spawn", now, true) }
        if ("border" in categories) s.allLevels.forEach { l ->
            val key = "border:" + l.dimension().identifier(); val now = borderText(l); val prev = lastLocal[key]
            val newLerp = prev != null && now.split(',')[3].toLong() > prev.split(',')[3].toLong() + 1
            changed(key, now, prev != null && (borderSig(prev) != borderSig(now) || newLerp))
        }
    }

    // ------------------------------------------------------------------ applying the master's state
    private fun applyState(s: MinecraftServer, state: Map<String, String>, exact: Boolean) {
        for ((k, v) in state) if (cat(k) in categories && (holdUntil[k] ?: 0L) <= ticks) applyKey(s, k, v, exact)
    }

    private fun count(key: String) { applied.incrementAndGet(); appliedByKey.computeIfAbsent(key) { AtomicLong() }.incrementAndGet(); log.info("[fission] world setting applied: $key") }

    private fun applyKey(s: MinecraftServer, key: String, value: String, exact: Boolean) {
        applying = true
        try {
            known[key] = value
            when {
                key.startsWith("clock:") -> applyClock(s, key.removePrefix("clock:"), value, exact, key)
                key == "gametime" -> { val t = value.toLongOrNull() ?: return; val cur = s.overworld().gameTime; if (Math.abs(cur - t) > (if (exact) 0 else 60)) { (s.worldData.overworldData()).setGameTime(t); adopted.incrementAndGet() } }
                key == "weather" -> applyWeather(s, value, exact)
                key.startsWith("rule:") -> applyRule(s, key.removePrefix("rule:"), value, key)
                key == "difficulty" -> { if (difficultyText(s) != value) { val p = value.split(','); s.setDifficulty(Difficulty.byId(p[0].toInt()), true); s.setDifficultyLocked(p[1].toBoolean()); count(key); lastLocal[key] = difficultyText(s) } }
                key == "gametype" -> { if (s.defaultGameType.id.toString() != value) { s.setDefaultGameType(GameType.byId(value.toInt())); count(key); lastLocal[key] = value } }
                key == "spawn" -> applySpawn(s, value)
                key.startsWith("border:") -> applyBorder(s, key.removePrefix("border:"), value, key)
            }
        } catch (t: Throwable) { log.warning("[fission] world setting $key = ${value.take(60)} could not be applied: $t") }
        finally { applying = false }
    }

    private fun applyClock(s: MinecraftServer, id: String, value: String, exact: Boolean, key: String) {
        val p = value.split(','); val total = p[0].toLong(); val rate = p[2].toFloat(); val paused = p[3].toBoolean()
        val holder = clocks(s).firstOrNull { clockId(it) == id } ?: return
        val inst = clockState(s, holder)
        var changed = false
        if (inst.rate != rate) { s.clockManager().setRate(holder, rate); changed = true }
        if (inst.paused != paused) { s.clockManager().setPaused(holder, paused); changed = true }
        val drift = Math.abs(inst.total - total)
        if (drift > (if (exact) 0 else 2)) { s.clockManager().setTotalTicks(holder, total); if (exact || drift > 40) changed = true else adopted.incrementAndGet() }
        if (changed) count(key)
    }

    private fun applyWeather(s: MinecraftServer, value: String, exact: Boolean) {
        val p = value.split(','); val clear = p[0].toInt(); val rain = p[1].toInt(); val thunder = p[2].toInt(); val raining = p[3].toBoolean(); val thundering = p[4].toBoolean()
        val w = s.weatherData
        val stateDiffers = w.isRaining != raining || w.isThundering != thundering
        val timersDiffer = Math.abs(w.clearWeatherTime - clear) > 200 || Math.abs(w.rainTime - rain) > 200 || Math.abs(w.thunderTime - thunder) > 200
        if (!exact && !stateDiffers && !timersDiffer) return
        w.setClearWeatherTime(clear); w.setRainTime(rain); w.setThunderTime(thunder); w.setRaining(raining); w.setThundering(thundering)
        if (stateDiffers || exact) count("weather") else adopted.incrementAndGet()
        lastLocal["weather"] = weatherText(s)
    }

    private fun applyRule(s: MinecraftServer, id: String, value: String, key: String) {
        val rules = s.globalGameRules
        val rule = rules.availableRules().filter { it.id() == id }.findFirst().orElse(null) ?: return
        @Suppress("UNCHECKED_CAST") val r = rule as GameRule<Any>
        val parsed = r.deserialize(value).result().orElse(null) ?: return
        if (r.serialize(rules.get(r)) == value) return
        rules.set(r, parsed, s)
        count(key)
    }

    private fun applySpawn(s: MinecraftServer, value: String) {
        if (spawnText(s) == value) return
        val p = value.split('|')
        val dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(p[0]))
        s.overworld().respawnData = LevelData.RespawnData.of(dim, BlockPos(p[1].toInt(), p[2].toInt(), p[3].toInt()), p[4].toFloat(), p[5].toFloat())
        count("spawn"); lastLocal["spawn"] = spawnText(s)
    }

    private fun applyBorder(s: MinecraftServer, dim: String, value: String, key: String) {
        val level = s.allLevels.firstOrNull { it.dimension().identifier().toString() == dim } ?: return
        val now = borderText(level)
        val p = value.split(','); val b = level.worldBorder
        val sigDiffers = borderSig(now) != borderSig(value)
        val remoteLerp = p[3].toLong()
        if (!sigDiffers && !(remoteLerp > 0 && b.lerpTime == 0L)) return
        b.setCenter(p[0].toDouble(), p[1].toDouble()); b.damagePerBlock = p[4].toDouble(); b.safeZone = p[5].toDouble(); b.warningBlocks = p[6].toInt(); b.warningTime = p[7].toInt()
        val target = p[2].toDouble()
        if (remoteLerp > 0) b.lerpSizeBetween(b.size, target, remoteLerp, level.gameTime) else if (b.size != target || b.lerpTime != 0L) b.size = target
        count(key); lastLocal[key] = borderText(level)
    }
}
