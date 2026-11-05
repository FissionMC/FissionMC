package net.fissionmc.packets

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.netty.NettyManager
import com.github.retrooper.packetevents.protocol.PacketSide
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.adventure.AdventureConversionInjector
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.github.retrooper.packetevents.impl.netty.manager.protocol.ProtocolManagerAbstract
import com.github.retrooper.packetevents.protocol.ProtocolVersion

/** Where a [FissionPacketEventsAPI] runs. */
enum class Side { MASTER, BACKEND }

/**
 * FissionMC's own PacketEvents platform: replaces upstream's spigot injector.
 * No Bukkit, no NMS: packets are fed to it by our pipeline (master Netty pipeline, backend virtual connection).
 * `load()` deliberately skips upstream's block-state / registry preloading, which needs compressed mapping
 * resources that are not vendored.
 */
class FissionPacketEventsAPI(val side: Side) : PacketEventsAPI<Side>() {
    private val netty = NettyManagerImpl()
    private val server = object : ServerManager { override fun getVersion(): ServerVersion = ServerVersion.V_26_3 }
    private val protocol = object : ProtocolManagerAbstract() {
        override fun getPlatformVersion(): ProtocolVersion = ProtocolVersion.UNKNOWN
    }
    private val players = object : PlayerManager {
        override fun getPing(player: Any) = 0
        override fun getClientVersion(player: Any): ClientVersion = getUser(player)?.clientVersion ?: ClientVersion.UNKNOWN
        override fun getChannel(player: Any): Any? = (player as? FissionPlayerHandle)?.channel ?: player
        override fun getUser(player: Any): User? = protocol.getUser(getChannel(player))
    }
    private val injector = object : ChannelInjector {
        override fun inject() {}
        override fun uninject() {}
        override fun updateUser(channel: Any, user: User) { protocol.setUser(channel, user) }
        override fun setPlayer(channel: Any, player: Any) {}
        override fun isPlayerSet(channel: Any) = false
        override fun isProxy() = false
        override fun getPacketSide() = PacketSide.SERVER
    }
    @Volatile private var loaded = false
    @Volatile private var terminated = false

    override fun load() {
        if (loaded) return
        PacketEvents.IDENTIFIER = "pe-fission"; PacketEvents.ENCODER_NAME = "pe-encoder-fission"
        PacketEvents.DECODER_NAME = "pe-decoder-fission"
        PacketType.prepare()
        AdventureConversionInjector.inject()
        registerInternalListener()
        loaded = true
    }
    override fun isLoaded() = loaded
    override fun init() { load() }
    override fun isInitialized() = loaded
    override fun terminate() { super.terminate(); terminated = true }
    override fun isTerminated() = terminated
    override fun getPlugin() = side
    override fun getServerManager() = server
    override fun getProtocolManager(): ProtocolManager = protocol
    override fun getPlayerManager() = players
    override fun getNettyManager(): NettyManager = netty
    override fun getInjector() = injector

    companion object {
        /** Creates, registers and loads the API once per JVM. */
        @Synchronized fun install(side: Side): FissionPacketEventsAPI {
            (PacketEvents.getAPI() as? FissionPacketEventsAPI)?.let { return it }
            val api = FissionPacketEventsAPI(side)
            PacketEvents.setAPI(api)
            api.load()
            return api
        }
    }
}

/** A "player" object for PacketEvents calls on a platform that has no Bukkit player: just the channel. */
class FissionPlayerHandle(val channel: Any)
