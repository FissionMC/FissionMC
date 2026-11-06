package net.fissionmc.master.client

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientConfigurationEndAck
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearTitles
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter
import io.netty.buffer.Unpooled
import net.kyori.adventure.text.Component
import java.security.MessageDigest
import java.util.UUID

/** Packet ids of 26.3 that the switch/chat logic looks at, resolved from PacketEvents' tables (a unit test pins them to vanilla's). */
object PlayIds {
    private val V = ClientVersion.V_26_3
    val login by lazy { PacketType.Play.Server.JOIN_GAME.getId(V) }
    val keepAliveS2C by lazy { PacketType.Play.Server.KEEP_ALIVE.getId(V) }
    val keepAliveC2S by lazy { PacketType.Play.Client.KEEP_ALIVE.getId(V) }
    val bossEvent by lazy { PacketType.Play.Server.BOSS_BAR.getId(V) }
    val playerInfoUpdate by lazy { PacketType.Play.Server.PLAYER_INFO_UPDATE.getId(V) }
    val playerInfoRemove by lazy { PacketType.Play.Server.PLAYER_INFO_REMOVE.getId(V) }
    val commands by lazy { PacketType.Play.Server.DECLARE_COMMANDS.getId(V) }
    val systemChat by lazy { PacketType.Play.Server.SYSTEM_CHAT_MESSAGE.getId(V) }
    val chatCommand by lazy { PacketType.Play.Client.CHAT_COMMAND_UNSIGNED.getId(V) }
    val chatCommandSigned by lazy { PacketType.Play.Client.CHAT_COMMAND.getId(V) }
    val chat by lazy { PacketType.Play.Client.CHAT_MESSAGE.getId(V) }
    val commandSuggestion by lazy { PacketType.Play.Client.TAB_COMPLETE.getId(V) }
    val commandSuggestions by lazy { PacketType.Play.Server.TAB_COMPLETE.getId(V) }
    val respawn by lazy { PacketType.Play.Server.RESPAWN.getId(V) }
    val playerLoaded by lazy { PacketType.Play.Client.PLAYER_LOADED.getId(V) }

    val cfgEnd by lazy { PacketType.Configuration.Server.CONFIGURATION_END.getId(V) }
    val cfgKeepAlive by lazy { PacketType.Configuration.Server.KEEP_ALIVE.getId(V) }
    val cfgPing by lazy { PacketType.Configuration.Server.PING.getId(V) }
    val cfgDisconnect by lazy { PacketType.Configuration.Server.DISCONNECT.getId(V) }
    val cfgFeatures by lazy { PacketType.Configuration.Server.UPDATE_ENABLED_FEATURES.getId(V) }
    val cfgKnownPacks by lazy { PacketType.Configuration.Server.SELECT_KNOWN_PACKS.getId(V) }
    val cfgRegistry by lazy { PacketType.Configuration.Server.REGISTRY_DATA.getId(V) }
    val cfgTags by lazy { PacketType.Configuration.Server.UPDATE_TAGS.getId(V) }
    val cfgKeepAliveC2S by lazy { PacketType.Configuration.Client.KEEP_ALIVE.getId(V) }
    val cfgPong by lazy { PacketType.Configuration.Client.PONG.getId(V) }

    /** A frame with a packet id and a raw payload. */
    fun frame(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val b = Unpooled.buffer(); VarInts.write(b, id); b.writeBytes(payload)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }
    fun payload(frame: ByteArray): ByteArray = frame.copyOfRange(VarInts.size(Limbo.idOf(frame)), frame.size)
}

/**
 * The master acting as the client of a backend's CONFIGURATION phase, so the real client (already in PLAY) never sees it:
 * answers known packs / keepalive / ping, acks the end, and compares the frames that define the client's world data (features, known
 * packs, registries, tags) with the recording the client was configured with. Pure logic: frames in, frames out.
 */
class ConfigVirtualClient(private val reference: List<String> = Limbo.configHashes) {
    class Result(val replies: List<ByteArray> = emptyList(), val finished: Boolean = false, val abort: String? = null, val mismatch: Boolean = false)

    val hashes = ArrayList<String>()
    private val kinds = ArrayList<Int>()
    val ignored = ArrayList<Int>()

    /** First difference to the reference, or null when the backend's configuration is identical to the client's. */
    fun mismatch(): String? {
        for (i in 0 until maxOf(hashes.size, reference.size)) {
            val a = hashes.getOrNull(i); val b = reference.getOrNull(i)
            if (a != b) return "data frame #$i (packet id ${kinds.getOrNull(i) ?: "none"}) differs (backend ${a?.take(12) ?: "none"}, client ${b?.take(12) ?: "none"})"
        }
        return null
    }

    fun onFrame(frame: ByteArray): Result {
        val id = Limbo.idOf(frame)
        return when (id) {
            PlayIds.cfgFeatures, PlayIds.cfgRegistry, PlayIds.cfgTags -> { hashes += hash(frame); kinds += id; Result() }
            PlayIds.cfgKnownPacks -> { hashes += hash(frame); kinds += id; Result(listOf(Limbo.encode(Limbo.knownPacksAnswer()))) }
            PlayIds.cfgKeepAlive -> Result(listOf(PlayIds.frame(PlayIds.cfgKeepAliveC2S, PlayIds.payload(frame))))
            PlayIds.cfgPing -> Result(listOf(PlayIds.frame(PlayIds.cfgPong, PlayIds.payload(frame))))   // ping and pong both carry an int
            PlayIds.cfgDisconnect -> Result(abort = "backend disconnected during configuration")
            PlayIds.cfgEnd -> {
                val m = mismatch()
                if (m != null) Result(abort = "configuration differs from the client's: $m", mismatch = true)
                else Result(listOf(Limbo.encode(WrapperConfigClientConfigurationEndAck())), finished = true)
            }
            else -> { ignored += id; Result() }   // brand, server links, ...: harmless for a client already configured
        }
    }

    companion object {
        fun hash(frame: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(canonical(frame)).joinToString("") { "%02x".format(it) }

        /**
         * Frames whose bytes depend on hash map iteration order (two boots of the same server write the tags registries and tags in a
         * different order; the enabled-features set too) are compared in a canonical order: sorted by name, id lists sorted. Anything that
         * does not parse exactly is compared as raw bytes.
         */
        fun canonical(frame: ByteArray): ByteArray = try {
            when (Limbo.idOf(frame)) {
                PlayIds.cfgTags -> canonicalTags(frame)
                PlayIds.cfgFeatures -> canonicalFeatures(frame)
                else -> frame
            }
        } catch (_: Throwable) { frame }

        private fun str(b: io.netty.buffer.ByteBuf): String { val n = VarInts.tryRead(b)!!; require(n in 0..b.readableBytes()); return ByteArray(n).also { b.readBytes(it) }.toString(Charsets.UTF_8) }
        private fun wstr(b: io.netty.buffer.ByteBuf, s: String) { val t = s.toByteArray(Charsets.UTF_8); VarInts.write(b, t.size); b.writeBytes(t) }

        private fun canonicalFeatures(frame: ByteArray): ByteArray {
            val b = Unpooled.wrappedBuffer(frame); val id = VarInts.tryRead(b)!!
            val names = List(VarInts.tryRead(b)!!) { str(b) }.sorted(); require(b.readableBytes() == 0)
            val o = Unpooled.buffer(); VarInts.write(o, id); VarInts.write(o, names.size); names.forEach { wstr(o, it) }
            return ByteArray(o.readableBytes()).also { o.readBytes(it) }
        }

        private fun canonicalTags(frame: ByteArray): ByteArray {
            val b = Unpooled.wrappedBuffer(frame); val id = VarInts.tryRead(b)!!
            val regs = List(VarInts.tryRead(b)!!) {
                val reg = str(b)
                val tags = List(VarInts.tryRead(b)!!) { val tag = str(b); tag to List(VarInts.tryRead(b)!!) { VarInts.tryRead(b)!! }.sorted() }.sortedBy { it.first }
                reg to tags
            }.sortedBy { it.first }
            require(b.readableBytes() == 0)
            val o = Unpooled.buffer(); VarInts.write(o, id); VarInts.write(o, regs.size)
            regs.forEach { (reg, tags) -> wstr(o, reg); VarInts.write(o, tags.size); tags.forEach { (tag, ids) -> wstr(o, tag); VarInts.write(o, ids.size); ids.forEach { VarInts.write(o, it) } } }
            return ByteArray(o.readableBytes()).also { o.readBytes(it) }
        }
        /** Hashes of the frames of [frames] that define the client's data, in order. */
        fun dataHashes(frames: List<ByteArray>): List<String> =
            frames.filter { Limbo.idOf(it) in setOf(PlayIds.cfgFeatures, PlayIds.cfgRegistry, PlayIds.cfgTags, PlayIds.cfgKnownPacks) }.map { hash(it) }
    }
}

/**
 * State a fresh level does not reset on the client (see `ClientPacketListener.handleLogin`): tab list entries, boss bars, tab header/footer, titles.
 * Fed with the backend's play frames; [cleanupFrames] says what to send before the next Login. [decode] turns a frame into a PacketEvents
 * send event (the session's user knows the protocol state).
 */
class SwitchCleanup(private val decode: (ByteArray) -> PacketSendEvent?, private val log: (String) -> Unit = {}) {
    private val tab = LinkedHashSet<UUID>()
    private val bars = LinkedHashSet<UUID>()

    fun observe(frame: ByteArray) {
        val id = Limbo.idOf(frame)
        try {
            when (id) {
                PlayIds.bossEvent -> {   // UUID, varint action (0 add, 1 remove)
                    val b = Unpooled.wrappedBuffer(PlayIds.payload(frame))
                    val u = UUID(b.readLong(), b.readLong()); val action = VarInts.tryRead(b)
                    if (action == 0) bars += u else if (action == 1) bars -= u
                }
                PlayIds.playerInfoUpdate -> {
                    val e = decode(frame) ?: return
                    val w = WrapperPlayServerPlayerInfoUpdate(e)
                    if (w.actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) w.entries.forEach { tab += it.profileId }
                }
                PlayIds.playerInfoRemove -> {
                    val e = decode(frame) ?: return
                    WrapperPlayServerPlayerInfoRemove(e).profileIds.forEach { tab -= it }
                }
            }
        } catch (t: Throwable) { log("cleanup tracking failed for packet $id: $t") }
    }

    val tracked: Int get() = tab.size + bars.size

    /** Frames to send before a new Login, and forget the state. */
    fun cleanupFrames(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (tab.isNotEmpty()) out += Limbo.encode(WrapperPlayServerPlayerInfoRemove(tab.toList()))
        bars.forEach { u -> val b = Unpooled.buffer(); b.writeLong(u.mostSignificantBits); b.writeLong(u.leastSignificantBits); VarInts.write(b, 1)
            out += PlayIds.frame(PlayIds.bossEvent, ByteArray(b.readableBytes()).also { b.readBytes(it) }) }
        out += Limbo.encode(WrapperPlayServerPlayerListHeaderAndFooter(Component.empty(), Component.empty()))
        out += Limbo.encode(WrapperPlayServerClearTitles(true))
        tab.clear(); bars.clear()
        return out
    }
}


/**
 * The Respawn that must follow every Login the client gets while it is already playing.
 *
 * `ClientPacketListener.handleLogin` creates the LocalPlayer only when `minecraft.player == null`; a second Login keeps the OLD LocalPlayer, whose
 * `Entity.level` still points at the previous ClientLevel (nothing in the client ever calls `Entity.setLevel`). That player then moves and collides in
 * the old level: no chunks around it, so it cannot move horizontally ("glued to the block") and bobs up and down. `handleRespawn` is what creates a new
 * LocalPlayer on the current level (BungeeCord/Velocity send it for the same reason; it is not only the "drop old chunks" trick). Same dimension as
 * the old player's level: the level made by the Login is kept (no dummy dimension needed); different: the Respawn builds the matching level.
 *
 * Layout (26.3, ClientboundLoginPacket / ClientboundRespawnPacket codecs): Login = int entity id, bool hardcore, varint n + n identifiers,
 * varint maxPlayers, varint chunkRadius, varint simulationDistance, 3 bools, CommonPlayerSpawnInfo, bool onlineMode, bool enforcesSecureChat.
 * Respawn = CommonPlayerSpawnInfo + byte dataToKeep (0: keep nothing).
 */
object RespawnFrames {
    fun fromLogin(login: ByteArray): ByteArray {
        val b = Unpooled.wrappedBuffer(login)
        VarInts.tryRead(b)!!; b.skipBytes(4 + 1)
        repeat(VarInts.tryRead(b)!!) { b.skipBytes(VarInts.tryRead(b)!!) }
        repeat(3) { VarInts.tryRead(b)!! }; b.skipBytes(3)
        val start = b.readerIndex(); val end = login.size - 2
        require(end > start) { "Login frame too short" }
        return PlayIds.frame(PlayIds.respawn, login.copyOfRange(start, end) + byteArrayOf(0))
    }
}
