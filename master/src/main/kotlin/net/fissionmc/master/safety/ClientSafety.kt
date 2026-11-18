package net.fissionmc.master.safety

import com.github.retrooper.packetevents.protocol.ConnectionState
import net.fissionmc.master.seamless.PlayPacketIds
import java.util.zip.Deflater

/** The live `safety:` settings (replaced on a config reload). */
object Safety { @Volatile var config: SafetyConfig = SafetyConfig() }

/** What to do with one backend frame: forward [frame] (maybe changed), or [frame] == null and [kick] == null: drop it, or [kick] = disconnect the player with that message. */
class Verdict(val frame: ByteArray?, val kick: String? = null) {
    companion object { val DROP = Verdict(null); fun pass(f: ByteArray) = Verdict(f) }
}

/**
 * Layers 1 and 2 of the client safety layer (`.dev/docs/design/client-safety.md`): every clientbound frame (packet id + payload, uncompressed) a backend relays to a real client passes
 * through [check] first. Frames a vanilla client could not decode are never forwarded: the ones that can be repaired are sanitised in place (strings cut, oversized components,
 * items and block entity tags replaced), the rest are dropped or kick the player, by policy. A frame this code cannot read is passed on as it is (fail open: a reader bug
 * must not break legitimate traffic), only proven violations are acted on. One instance per client session.
 */
class ClientSafety(private val player: () -> String, private val backend: () -> String?, private val log: (String) -> Unit, private val items: ItemSanitizer? = null,
                   private val cfgOf: () -> SafetyConfig = { Safety.config }) {
    private val lastLog = HashMap<String, Long>()
    private val nameCache = HashMap<Int, String>()

    private companion object {
        fun id(n: String) = PlayPacketIds.c(n)
        val login by lazy { id("Login") }; val respawn by lazy { id("Respawn") }; val tags by lazy { id("UpdateTags") }; val commands by lazy { id("Commands") }
        val recipes by lazy { id("UpdateRecipes") }; val chunk by lazy { id("LevelChunkWithLight") }; val blockEntity by lazy { id("BlockEntityData") }
        val payload by lazy { id("CustomPayload") }
        val chatLike by lazy { mapOf(id("SystemChat") to 0, id("SetActionBarText") to 1, id("SetTitleText") to 1, id("SetSubtitleText") to 1, id("TabList") to 2, id("Disconnect") to 3, id("PlayerCombatKill") to 4,
            id("BossEvent") to 5, id("SetObjective") to 6, id("SetPlayerTeam") to 7, id("SetScore") to 8, id("PlayerInfoUpdate") to 9, id("DisguisedChat") to 10, id("OpenScreen") to 11, id("ServerData") to 12, id("MapItemData") to 13) }
        val cfgPayload by lazy { com.github.retrooper.packetevents.protocol.packettype.PacketType.Configuration.Server.PLUGIN_MESSAGE.getId(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_26_3) }
        const val WIRE_SAFE = 2_000_000
        const val MAX_COMPRESSED = 2_097_151
    }

    private fun idOf(f: ByteArray): Int { var v = 0; for (i in 0 until 5) { if (i >= f.size) return -1; val x = f[i].toInt(); v = v or ((x and 0x7F) shl (7 * i)); if (x and 0x80 == 0) return v }; return -1 }
    private fun idLen(f: ByteArray): Int { var i = 0; while (i < f.size && f[i].toInt() and 0x80 != 0) i++; return i + 1 }

    /** True when a frame of this size would not get through the client's frame splitter / decompressor (checked by compressing, only for frames above [WIRE_SAFE]). */
    fun tooBigForWire(f: ByteArray, cfg: SafetyConfig): Boolean {
        if (f.size > cfg.maxFrameBytes) return true
        if (f.size <= WIRE_SAFE) return false
        val d = Deflater(); try {
            d.setInput(f); d.finish(); val tmp = ByteArray(65536); var total = 0
            while (!d.finished()) { total += d.deflate(tmp); if (total + 8 > MAX_COMPRESSED) return true }
            return false
        } finally { d.end() }
    }

    fun check(state: ConnectionState, frame: ByteArray): Verdict {
        val cfg = cfgOf()
        if (!cfg.enabled || frame.isEmpty()) return Verdict.pass(frame)
        val id = idOf(frame)
        var out = frame
        val name = if (state == ConnectionState.PLAY) nameCache.getOrPut(id) { snake(PlayPacketIds.nameC(id)) } else snake("${state.name.lowercase()}:$id")
        try {
            if (frame.size > 4096) SafetyStats.checked()
            if (state == ConnectionState.PLAY) out = play(frame, id, cfg, name)
            else if (state == ConnectionState.CONFIGURATION && id == cfgPayload) out = customPayload(frame, cfg, name)
            if (tooBigForWire(out, cfg)) throw Violation("frame.too_big", "${out.size} bytes")
            return Verdict.pass(out)
        } catch (v: Violation) {
            val critical = state != ConnectionState.PLAY || id == login || id == respawn || id == tags || id == commands || id == recipes
            val policy = when { state == ConnectionState.PLAY && (id == chunk || id == blockEntity) -> cfg.chunkPolicy; critical -> cfg.criticalPolicy; else -> cfg.otherPolicy }
            val reason = "$name.${v.reason}"
            note(reason, "${frame.size} bytes: ${v.reason} ${v.detail}, ${policy.name.lowercase()}", force = policy == Policy.DISCONNECT)
            return if (policy == Policy.DISCONNECT) { SafetyStats.disconnect(reason); Verdict(null, cfg.disconnectMessage) } else { SafetyStats.drop(reason); Verdict.DROP }
        } catch (u: Unparseable) {
            // not understood: forwarded untouched unless the size rule says it cannot get through anyway
            SafetyStats.unparsed(); note("unparsed.$name", "${frame.size} bytes not understood, forwarded as they are: ${u.message}")
            if (tooBigForWire(frame, cfg)) { SafetyStats.drop("$name.frame_too_big"); note("$name.frame_too_big", "${frame.size} bytes, unparsed, dropped"); return Verdict.DROP }
            return Verdict.pass(frame)
        }
    }

    /**
     * For PLAY frames the master built itself from backend data (ghost entity data/equipment, tab lines, global chat): the frame a client may get, or null
     * (dropped). A proven violation only drops the frame here: the master must not kick a player for what ANOTHER player's backend sent.
     */
    fun checkMasterFrame(frame: ByteArray): ByteArray? = check(ConnectionState.PLAY, frame).frame

    private fun snake(n: String) = n.replace(':', '_').replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()

    private fun note(reason: String, text: String, force: Boolean = false) {
        val now = System.currentTimeMillis(); val gap = cfgOf().logIntervalSeconds * 1000L
        val last = lastLog[reason]
        if (!force && last != null && now - last < gap) return
        lastLog[reason] = now
        log("[safety] ${player()} on ${backend() ?: "-"}: $reason: $text")
    }

    // ---- per packet ----

    private fun play(f: ByteArray, id: Int, cfg: SafetyConfig, name: String): ByteArray {
        if (id == payload) return customPayload(f, cfg, name)
        if (cfg.sanitizeChunks) {
            if (id == chunk) return chunkData(f, cfg, name)
            if (id == blockEntity) return blockEntityData(f, cfg, name)
        }
        if (cfg.sanitizeText) { val k = chatLike[id]; if (k != null) return text(f, k, cfg, name) }
        if (cfg.sanitizeItems && items != null && f.size >= cfg.itemScanBytes) return items.sanitize(f, id, cfg) { reason, saved -> SafetyStats.sanitise("$name.$reason", saved); note("$name.$reason", "item data sanitised ($saved bytes removed)") } ?: f
        return f
    }

    private fun customPayload(f: ByteArray, cfg: SafetyConfig, name: String): ByteArray {
        val c = Cur(f, idLen(f)); c.utf()
        val n = c.left
        if (n > cfg.maxPayloadBytes) throw Violation("too_big", "payload $n bytes > ${cfg.maxPayloadBytes}")
        return f
    }

    /** Fixes one component (network NBT) at the cursor, recording the replacement in [e]. */
    private fun component(c: Cur, e: Edit, cfg: SafetyConfig, name: String) {
        val s = c.p
        if (s >= c.b.size) throw Unparseable("no component")
        val info = NbtScan.scan(c.b, s, c.b.size, cfg.maxStringBytes)
        if (info.end < 0) { if (info.depth > cfg.maxNbtDepth) throw Violation("component_too_deep", "depth ${info.depth}"); throw Unparseable("component at $s") }
        c.p = info.end
        val size = info.end - s
        val reason = when {
            info.depth > cfg.maxNbtDepth -> "component_too_deep"
            info.heap > cfg.maxNbtBytes || size > cfg.maxComponentBytes -> "component_too_big"
            else -> null
        }
        if (reason != null) { e.replace(s, info.end, NbtScan.textTag(cfg.placeholder)); SafetyStats.sanitise("$name.$reason", size); note("$name.$reason", "component of $size bytes (depth ${info.depth}) replaced by a placeholder") }
        else if (info.rewritten != null) { e.replace(s, info.end, info.rewritten); SafetyStats.sanitise("$name.string_cut", size - info.rewritten.size); note("$name.string_cut", "a string of the component was cut to ${cfg.maxStringBytes} bytes") }
    }

    private fun optComponent(c: Cur, e: Edit, cfg: SafetyConfig, name: String) { if (c.bool()) component(c, e, cfg, name) }

    private fun text(f: ByteArray, kind: Int, cfg: SafetyConfig, name: String): ByteArray {
        val c = Cur(f, idLen(f)); val e = Edit(f)
        fun comp() = component(c, e, cfg, name)
        when (kind) {
            0 -> comp()                                              // SystemChat: component, overlay
            1, 3 -> comp()                                           // action bar, titles, disconnect
            2 -> { comp(); comp() }                                  // TabList header, footer
            4 -> { c.varInt(); comp() }                              // PlayerCombatKill: player id, message
            5 -> { c.skip(16); when (c.varInt()) { 0, 3 -> comp(); else -> {} } }   // BossEvent: add / update name
            6 -> { c.utf(); val m = c.byte(); if (m == 0 || m == 2) comp() }          // SetObjective
            7 -> {                                                   // SetPlayerTeam
                c.utf(); val m = c.byte()
                if (m == 0 || m == 2) { comp(); c.byte(); c.utf(); c.utf(); c.varInt(); comp(); comp() }
            }
            8 -> { c.utf(); c.utf(); c.varInt(); optComponent(c, e, cfg, name) }      // SetScore: display name
            9 -> playerInfo(c, e, cfg, name)
            10 -> { comp(); c.varInt(); comp(); optComponent(c, e, cfg, name) }        // DisguisedChat
            11 -> { c.varInt(); c.varInt(); comp() }                                   // OpenScreen: title
            12 -> comp()                                                               // ServerData: motd
            13 -> mapData(c, e, cfg, name)
        }
        return if (e.changed) e.result() else f
    }

    private fun playerInfo(c: Cur, e: Edit, cfg: SafetyConfig, name: String) {
        val actions = c.byte(); val n = c.varInt()
        if (n < 0 || n > 100_000) throw Unparseable("entries $n")
        repeat(n) {
            c.skip(16)
            if (actions and 1 != 0) { c.utf(); val np = c.varInt(); if (np < 0 || np > 4096) throw Unparseable("properties $np"); repeat(np) { c.utf(); c.utf(); if (c.bool()) c.utf() } }
            if (actions and 2 != 0 && c.bool()) { c.skip(16); c.long(); c.byteArray(); c.byteArray() }
            if (actions and 4 != 0) c.varInt()
            if (actions and 8 != 0) c.bool()
            if (actions and 16 != 0) c.varInt()
            if (actions and 32 != 0) optComponent(c, e, cfg, name)
            if (actions and 64 != 0) c.varInt()
            if (actions and 128 != 0) c.bool()
        }
    }

    private fun mapData(c: Cur, e: Edit, cfg: SafetyConfig, name: String) {
        c.varInt(); c.byte(); c.bool()
        if (!c.bool()) return
        val n = c.varInt(); if (n < 0 || n > 4096) throw Unparseable("decorations $n")
        repeat(n) { c.varInt(); c.byte(); c.byte(); c.byte(); optComponent(c, e, cfg, name) }
    }

    /** Chunk data: block entity tags above the limits are replaced by an empty tag (the block entity then loads with defaults). */
    private fun chunkData(f: ByteArray, cfg: SafetyConfig, name: String): ByteArray {
        val c = Cur(f, idLen(f)); val e = Edit(f)
        c.int(); c.int()
        val hm = c.varInt(); if (hm < 0 || hm > 64) throw Unparseable("heightmaps $hm")
        repeat(hm) { c.varInt(); val n = c.varInt(); if (n < 0) throw Unparseable("heightmap"); c.skip(n * 8) }
        val buf = c.varInt()
        if (buf > 2_097_152) throw Violation("buffer_too_big", "$buf bytes of section data (client maximum 2097152)")
        c.skip(buf)
        val n = c.varInt(); if (n < 0 || n > 1_000_000) throw Unparseable("block entities $n")
        repeat(n) { c.byte(); c.short(); c.varInt(); blockEntityTag(c, e, cfg, name) }
        return if (e.changed) e.result() else f
    }

    private fun blockEntityData(f: ByteArray, cfg: SafetyConfig, name: String): ByteArray {
        val c = Cur(f, idLen(f)); val e = Edit(f)
        c.long(); c.varInt(); blockEntityTag(c, e, cfg, name)
        return if (e.changed) e.result() else f
    }

    private fun blockEntityTag(c: Cur, e: Edit, cfg: SafetyConfig, name: String) {
        val s = c.p
        val info = NbtScan.scan(c.b, s)
        if (info.end < 0) { if (info.depth > cfg.maxNbtDepth) throw Violation("block_entity_too_deep", "depth ${info.depth}"); throw Unparseable("block entity nbt at $s") }
        c.p = info.end
        if (info.heap > cfg.maxBlockEntitiesNbt || info.depth > cfg.maxNbtDepth) {
            e.replace(s, info.end, NbtScan.EMPTY_COMPOUND)
            val r = if (info.depth > cfg.maxNbtDepth) "block_entity_too_deep" else "block_entity_too_big"
            SafetyStats.sanitise("$name.$r", info.end - s); note("$name.$r", "block entity tag of ${info.end - s} bytes (estimate ${info.heap}, depth ${info.depth}) replaced by an empty tag")
        }
    }
}

/** Item layer (PacketEvents based, see ItemSanitizer.kt). Returns the new frame, or null when nothing had to change. [report] (reason, bytes removed). */
interface ItemSanitizer { fun sanitize(frame: ByteArray, id: Int, cfg: SafetyConfig, report: (String, Int) -> Unit): ByteArray? }
