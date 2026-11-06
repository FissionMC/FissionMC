package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.world.Difficulty
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk
import com.github.retrooper.packetevents.protocol.world.chunk.Column
import com.github.retrooper.packetevents.protocol.world.chunk.LightData
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypeRef
import com.github.retrooper.packetevents.util.KnownPack
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSelectKnownPacks
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.netty.buffer.Unpooled
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewPosition
import net.fissionmc.protocol.MC_VERSION
import net.kyori.adventure.text.Component
import java.util.BitSet

/**
 * The packets of the limbo: an empty void world, spectator mode so nobody falls.
 *
 * The CONFIGURATION phase is not hand-built: registries, tags and feature flags are replayed from `limbo-config.bin`, a recording of what
 * the real vanilla/Paper backend sends during configuration (vanilla's own codecs are the single source of truth, e.g. the numeric ids
 * inside the tags packet). Record it again with the relay probe after every Minecraft version change: see [RelayProbe].
 * Play-phase packets are PacketEvents wrappers; the probe also dumps them (`limbo-play.bin`) so a backend can check them against
 * vanilla's own decoders (`-Dfission.check-limbo=<file>`).
 */
object Limbo {
    const val ENTITY_ID = 1
    const val WORLD = "minecraft:overworld"
    private val V = ClientVersion.V_26_3

    /** The recorded configuration phase, split at the known-packs request. */
    class Config(val beforeKnownPacks: List<ByteArray>, val afterKnownPacks: List<ByteArray>, val overworldDimensionId: Int)

    /** Frame container: int count, then per frame int length + bytes. */
    fun readFrames(bytes: ByteArray): List<ByteArray> {
        val d = java.io.DataInputStream(bytes.inputStream()); val n = d.readInt()
        return List(n) { ByteArray(d.readInt()).also { d.readFully(it) } }
    }
    fun writeFrames(frames: List<ByteArray>): ByteArray {
        val o = java.io.ByteArrayOutputStream(); val d = java.io.DataOutputStream(o)
        d.writeInt(frames.size); frames.forEach { d.writeInt(it.size); d.write(it) }; return o.toByteArray()
    }

    /** Packet id of a frame (first varint). */
    fun idOf(frame: ByteArray): Int = VarInts.tryRead(Unpooled.wrappedBuffer(frame)) ?: -1

    /** Splits recorded frames at the select-known-packs request and finds the overworld's index in the dimension_type registry. */
    fun parse(frames: List<ByteArray>): Config {
        val known = PacketType.Configuration.Server.SELECT_KNOWN_PACKS.getId(V)
        val at = frames.indexOfFirst { idOf(it) == known }
        require(at >= 0) { "recording has no known-packs request" }
        return Config(frames.subList(0, at + 1), frames.subList(at + 1, frames.size), overworldIndex(frames))
    }

    /** Reads the dimension_type registry frame: id, registry key, count, entries (identifier + has-data flag [+ data, unsupported]). */
    fun overworldIndex(frames: List<ByteArray>): Int {
        val reg = PacketType.Configuration.Server.REGISTRY_DATA.getId(V)
        for (f in frames) {
            if (idOf(f) != reg) continue
            val b = Unpooled.wrappedBuffer(f); VarInts.tryRead(b)
            fun str(): String { val n = VarInts.tryRead(b)!!; val a = ByteArray(n); b.readBytes(a); return String(a, Charsets.UTF_8) }
            if (str() != "minecraft:dimension_type") continue
            val count = VarInts.tryRead(b)!!
            for (i in 0 until count) {
                val id = str()
                require(!b.readBoolean()) { "dimension_type entry $id carries data; limbo needs name-only registries (client known pack)" }
                if (id == "minecraft:overworld") return i
            }
        }
        error("recording has no minecraft:overworld in dimension_type")
    }

    val config: Config by lazy {
        val bytes = Limbo::class.java.getResourceAsStream("/limbo-config.bin")?.readAllBytes()
            ?: error("limbo-config.bin is missing: record it with debug.record-limbo (see RelayProbe)")
        parse(readFrames(bytes))
    }

    /** Hashes of the recorded data frames (features, known packs, registries, tags): what a backend's configuration must equal for a seamless switch. */
    val configHashes: List<String> by lazy { ConfigVirtualClient.dataHashes(config.beforeKnownPacks + config.afterKnownPacks) }

    /** Raw frames (packet id + payload), exactly as the real server produced them. */
    fun sendRaw(ch: io.netty.channel.Channel, frames: List<ByteArray>) {
        frames.forEach { ch.write(Unpooled.wrappedBuffer(it)) }; ch.flush()
    }

    fun encode(w: PacketWrapper<*>): ByteArray {
        val b = Unpooled.buffer(); w.buffer = b
        w.writeVarInt(w.nativePacketId); w.write()
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }

    fun knownPacksAnswer(): PacketWrapper<*> = WrapperConfigClientSelectKnownPacks(listOf(KnownPack("minecraft", "core", MC_VERSION)))

    fun joinGame(maxPlayers: Int, overworldId: Int): PacketWrapper<*> = WrapperPlayServerJoinGame(
        ENTITY_ID, false, GameMode.SPECTATOR, null, listOf(WORLD), com.github.retrooper.packetevents.protocol.nbt.NBTCompound(),
        DimensionTypeRef.IdRef(overworldId), Difficulty.PEACEFUL, WORLD, 0L, maxPlayers, 8, 8, false, false, false, false, false,
        null, 0, 63, false, false,
    )

    fun position(): PacketWrapper<*> =
        WrapperPlayServerPlayerPositionAndLook(Vector3d(0.5, 80.0, 0.5), 0f, 0f, 0.toByte(), 1, false)

    fun startLoadingChunks(): PacketWrapper<*> = WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.START_LOADING_CHUNKS, 0f)
    fun viewPosition(): PacketWrapper<*> = WrapperPlayServerUpdateViewPosition(0, 0)

    /**
     * The empty chunk at 0,0 is a RECORDING of what vanilla builds (`limbo-chunk.bin`, `gradlew :backend:run -PrecordChunk=<file>`:
     * LevelChunk + ClientboundLevelChunkWithLightPacket). The hand-built PacketEvents chunk was wrong where decoding the packet cannot
     * notice: PalettedContainer.write is `bits byte, palette, fixed-size long array` (no length prefix since 1.21.5), and the client crashed
     * on the first biome lookup ("Missing Palette entry for index 0").
     */
    val chunkFrame: ByteArray by lazy {
        Limbo::class.java.getResourceAsStream("/limbo-chunk.bin")?.readAllBytes() ?: error("limbo-chunk.bin is missing: record it with -PrecordChunk")
    }

    /** An unsigned system chat line (the limbo itself sends none: nothing is sent or received in limbo). */
    fun chat(text: String): PacketWrapper<*> = WrapperPlayServerSystemChatMessage(false, Component.text(text))

    /** The whole play-phase entry sequence as raw frames (wrappers encoded, the chunk replayed from the vanilla recording). */
    fun playFrames(maxPlayers: Int, overworldId: Int): List<ByteArray> = listOf(
        encode(joinGame(maxPlayers, overworldId)), encode(position()), encode(startLoadingChunks()), encode(viewPosition()), chunkFrame,
    )
}
