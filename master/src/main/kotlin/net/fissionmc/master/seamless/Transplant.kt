package net.fissionmc.master.seamless

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts

/** What a backend's `ClientboundLogin` says about the level the client is in. Layout: decompiled 26.3 `ClientboundLoginPacket` + `CommonPlayerSpawnInfo`. */
class SpawnInfo(
    val playerId: Int, val hardcore: Boolean, val levels: Set<String>, val maxPlayers: Int, val chunkRadius: Int, val simulationDistance: Int,
    val reducedDebugInfo: Boolean, val showDeathScreen: Boolean, val doLimitedCrafting: Boolean,
    val dimensionType: Int, val dimension: String, val seed: Long, val gameType: Int, val previousGameType: Int, val isDebug: Boolean, val isFlat: Boolean,
    val portalCooldown: Int, val seaLevel: Int, val onlineMode: Boolean, val enforcesSecureChat: Boolean,
) {
    /** Why a client in a level described by [this] cannot simply continue in the level [other] describes (null = it can: same dimension, type, seed, flags). */
    fun incompatibility(other: SpawnInfo): String? = when {
        dimension != other.dimension -> "dimension $dimension != ${other.dimension}"
        dimensionType != other.dimensionType -> "dimension type id $dimensionType != ${other.dimensionType}"
        seed != other.seed -> "hashed seed differs"
        hardcore != other.hardcore -> "hardcore differs"
        isDebug != other.isDebug || isFlat != other.isFlat -> "debug/flat world flags differ"
        seaLevel != other.seaLevel -> "sea level $seaLevel != ${other.seaLevel}"
        onlineMode != other.onlineMode || enforcesSecureChat != other.enforcesSecureChat -> "secure chat flags differ"
        levels != other.levels -> "level list differs"
        else -> null
    }

    companion object {
        private fun vi(b: ByteBuf) = VarInts.tryRead(b) ?: throw IllegalArgumentException("truncated Login")
        private fun str(b: ByteBuf): String { val n = vi(b); return ByteArray(n).also { b.readBytes(it) }.toString(Charsets.UTF_8) }

        /** Parses a Login frame (packet id included). */
        fun parse(login: ByteArray): SpawnInfo {
            val b = Unpooled.wrappedBuffer(login); val pid = vi(b)
            require(pid == PlayPacketIds.c("Login")) { "not a Login frame" }
            val id = b.readInt(); val hardcore = b.readBoolean()
            val levels = LinkedHashSet<String>(); repeat(vi(b)) { levels += str(b) }
            val maxPlayers = vi(b); val radius = vi(b); val sim = vi(b)
            val reduced = b.readBoolean(); val show = b.readBoolean(); val limited = b.readBoolean()
            val dimType = vi(b); val dim = str(b); val seed = b.readLong(); val gameType = vi(b); val prev = vi(b) - 1
            val debug = b.readBoolean(); val flat = b.readBoolean()
            if (b.readBoolean()) { str(b); b.readLong() }               // last death location
            val portal = vi(b); val sea = vi(b)
            val online = b.readBoolean(); val secure = b.readBoolean()
            return SpawnInfo(id, hardcore, levels, maxPlayers, radius, sim, reduced, show, limited, dimType, dim, seed, gameType, prev, debug, flat, portal, sea, online, secure)
        }
    }
}

/** What the client holds that a transplant must keep consistent or clean up (fed with the CLIENT-FACING frames the client receives). */
class ClientView(private val map: EntityIdMap) {
    val chunks = LinkedHashSet<Long>()
    val objectives = LinkedHashSet<String>()
    val teams = LinkedHashSet<String>()
    val effects = LinkedHashSet<Int>()
    var centerX = 0; var centerZ = 0
    private val pChunk = PlayPacketIds.c("LevelChunkWithLight"); private val pForget = PlayPacketIds.c("ForgetLevelChunk"); private val pObjective = PlayPacketIds.c("SetObjective")
    private val pTeam = PlayPacketIds.c("SetPlayerTeam"); private val pEffect = PlayPacketIds.c("UpdateMobEffect"); private val pRemoveEffect = PlayPacketIds.c("RemoveMobEffect")
    private val pCenter = PlayPacketIds.c("SetChunkCacheCenter")

    fun reset() { chunks.clear(); objectives.clear(); teams.clear(); effects.clear() }

    private fun pack(x: Int, z: Int) = (x.toLong() and 0xFFFFFFFFL) or ((z.toLong() and 0xFFFFFFFFL) shl 32)

    fun observe(frame: ByteArray) {
        try {
            val b = Unpooled.wrappedBuffer(frame); val pid = VarInts.tryRead(b) ?: return
            when (pid) {
                pChunk -> { val x = b.readInt(); val z = b.readInt(); chunks += pack(x, z) }
                pForget -> chunks -= b.readLong()
                pObjective -> { val n = ByteArray(VarInts.tryRead(b)!!).also { b.readBytes(it) }.toString(Charsets.UTF_8); when (b.readByte().toInt()) { 0 -> objectives += n; 1 -> objectives -= n } }
                pTeam -> { val n = ByteArray(VarInts.tryRead(b)!!).also { b.readBytes(it) }.toString(Charsets.UTF_8); when (b.readByte().toInt()) { 0 -> teams += n; 1 -> teams -= n } }
                pEffect -> if (VarInts.tryRead(b) == map.clientSelf) effects += VarInts.tryRead(b)!!
                pRemoveEffect -> if (VarInts.tryRead(b) == map.clientSelf) effects -= VarInts.tryRead(b)!!
                pCenter -> { centerX = VarInts.tryRead(b)!!; centerZ = VarInts.tryRead(b)!! }
            }
        } catch (_: Throwable) { }
    }
}

/** Builds the frames of a transplant (pure: frames in, frames out; the session writes them). */
object TransplantBuilder {
    private fun frame(id: Int, f: (ByteBuf) -> Unit = {}): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, id); f(b); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
    private fun gameEvent(event: Int, param: Float) = frame(PlayPacketIds.c("GameEvent")) { it.writeByte(event); it.writeFloat(param) }
    private fun str(b: ByteBuf, s: String) { val t = s.toByteArray(Charsets.UTF_8); VarInts.write(b, t.size); b.writeBytes(t) }

    val bundleDelimiter: ByteArray get() = frame(PlayPacketIds.c("BundleDelimiter"))

    /** Frames that clear what the OLD backend left on the client (before the id maps are replaced). [tabAndBars] = SwitchCleanup.cleanupFrames(). */
    fun cleanup(map: EntityIdMap, view: ClientView, tabAndBars: List<ByteArray>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val held = map.heldIds()
        if (held.isNotEmpty()) out += frame(PlayPacketIds.c("RemoveEntities")) { VarInts.write(it, held.size); held.forEach { id -> VarInts.write(it, id) } }
        out += tabAndBars
        view.objectives.forEach { n -> out += frame(PlayPacketIds.c("SetObjective")) { str(it, n); it.writeByte(1) } }
        view.teams.forEach { n -> out += frame(PlayPacketIds.c("SetPlayerTeam")) { str(it, n); it.writeByte(1) } }
        view.effects.forEach { e -> out += frame(PlayPacketIds.c("RemoveMobEffect")) { VarInts.write(it, map.clientSelf); VarInts.write(it, e) } }
        out += frame(PlayPacketIds.c("ContainerClose")) { VarInts.write(it, 0) }
        out += gameEvent(2, 0f); out += gameEvent(7, 0f); out += gameEvent(8, 0f)         // stop raining, rain level 0, thunder level 0 (the new backend sends its own weather after)
        return out
    }

    /** What the new backend's Login said, as the incremental packets a client that keeps its level understands. */
    fun fromLogin(l: SpawnInfo, clientSelf: Int): List<ByteArray> = listOf(
        frame(PlayPacketIds.c("SetChunkCacheRadius")) { VarInts.write(it, l.chunkRadius) },
        frame(PlayPacketIds.c("SetSimulationDistance")) { VarInts.write(it, l.simulationDistance) },
        gameEvent(3, l.gameType.toFloat()),                                                  // CHANGE_GAME_MODE
        gameEvent(11, if (l.showDeathScreen) 0f else 1f),                                    // IMMEDIATE_RESPAWN: param 0 = show the death screen
        gameEvent(12, if (l.doLimitedCrafting) 1f else 0f),                                  // LIMITED_CRAFTING
        frame(PlayPacketIds.c("EntityEvent")) { it.writeInt(clientSelf); it.writeByte(if (l.reducedDebugInfo) 22 else 23) },
    )

    /** ForgetLevelChunk for every chunk the client holds that the new backend neither sent nor will send (outside its view square around [centerX],[centerZ]). */
    fun forgetOutside(held: Collection<Long>, sentByNew: Set<Long>, centerX: Int, centerZ: Int, radius: Int): List<ByteArray> =
        held.filter { p ->
            val x = p.toInt(); val z = (p ushr 32).toInt()
            p !in sentByNew && (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius) && !(x == centerX && z == centerZ)
        }.map { p -> frame(PlayPacketIds.c("ForgetLevelChunk")) { it.writeLong(p) } }
}
