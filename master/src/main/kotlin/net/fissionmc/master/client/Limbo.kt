package net.fissionmc.master.client

import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.chat.ChatTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.world.Difficulty
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk
import com.github.retrooper.packetevents.protocol.world.chunk.Column
import com.github.retrooper.packetevents.protocol.world.chunk.LightData
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypeRef
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.util.KnownPack
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerConfigurationEnd
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerRegistryData
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerSelectKnownPacks
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
 * The packets of the limbo: an empty void world, spectator mode so nobody falls. Every packet is a PacketEvents wrapper.
 * Registry contents are NOT hand-encoded: entries are announced by name only (data comes from the client's own
 * `minecraft:core` pack, which we announce via known packs), names come from `limbo-registries.txt` generated from the vanilla jar.
 */
object Limbo {
    const val ENTITY_ID = 1
    const val WORLD = "minecraft:overworld"

    /** registry id -> entry names, in the order we send them (dimension_type starts with overworld). */
    val registries: List<Pair<String, List<String>>> by lazy {
        Limbo::class.java.getResourceAsStream("/limbo-registries.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
            .map { l -> val (r, e) = l.split('\t'); r to e.split(',') }
    }

    fun knownPacks(): PacketWrapper<*> = WrapperConfigServerSelectKnownPacks(listOf(KnownPack("minecraft", "core", MC_VERSION)))

    fun registryPackets(): List<PacketWrapper<*>> = registries.map { (reg, entries) ->
        WrapperConfigServerRegistryData(
            ResourceLocation(reg),
            entries.map { WrapperConfigServerRegistryData.RegistryElement(ResourceLocation("minecraft", it), null) },
        )
    }

    fun configurationEnd(): PacketWrapper<*> = WrapperConfigServerConfigurationEnd()

    fun joinGame(maxPlayers: Int): PacketWrapper<*> = WrapperPlayServerJoinGame(
        ENTITY_ID, false, GameMode.SPECTATOR, null, listOf(WORLD), com.github.retrooper.packetevents.protocol.nbt.NBTCompound(),
        DimensionTypeRef.IdRef(0), Difficulty.PEACEFUL, WORLD, 0L, maxPlayers, 8, 8, false, false, false, false, false,
        null, 0, 63, false, false,
    )

    fun position(): PacketWrapper<*> =
        WrapperPlayServerPlayerPositionAndLook(Vector3d(0.5, 80.0, 0.5), 0f, 0f, 0.toByte(), 1, false)

    fun startLoadingChunks(): PacketWrapper<*> = WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.START_LOADING_CHUNKS, 0f)
    fun viewPosition(): PacketWrapper<*> = WrapperPlayServerUpdateViewPosition(0, 0)

    /** One empty chunk column (24 empty sections of an overworld-height world) at 0,0. */
    fun emptyChunk(): PacketWrapper<*> {
        val v = ClientVersion.V_26_3
        val sections = Array<BaseChunk>(24) { Chunk_v1_18(v) }
        val light = LightData(true, BitSet(), BitSet(), BitSet(), BitSet(), 0, 0, arrayOf(), arrayOf())
        return WrapperPlayServerChunkData(Column(0, 0, true, sections, null), light)
    }

    fun chat(text: String): PacketWrapper<*> = WrapperPlayServerSystemChatMessage(false, Component.text(text))
    fun serverVersion(): ServerVersion = ServerVersion.V_26_3
}
