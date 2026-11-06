package net.fissionmc.backend.relay

import io.netty.buffer.Unpooled
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.server.MinecraftServer
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Dev check (`-Dfission.check-limbo=<limbo-play.bin>`): decodes the master limbo's play frames (built with PacketEvents wrappers)
 * with vanilla's OWN clientbound play codec and this server's registries, once, at the first tick. Logs one line per frame:
 * the decoded vanilla packet class and unread bytes (must be 0), or the decoding error. This is what makes PacketEvents' encoders
 * checkable against vanilla instead of against themselves.
 */
object LimboCheck {
    private val log = Logger.getLogger("FissionMC")
    @Volatile private var done = false

    /**
     * `-Dfission.check-stream=<file>`: replays the exact server->client byte stream the master wrote (login, configuration, play)
     * through vanilla's client-side decoders in order: frame splitter, compression, then the login/configuration/play clientbound codecs,
     * switching phase on LoginFinished / FinishConfiguration like a real client. One log line per frame.
     */
    private fun checkStream(server: MinecraftServer, file: String) {
        try {
            val frames = ArrayList<io.netty.buffer.ByteBuf>()
            val splitter = io.netty.channel.embedded.EmbeddedChannel(net.minecraft.network.Varint21FrameDecoder(null))
            splitter.writeInbound(Unpooled.wrappedBuffer(Files.readAllBytes(Path.of(file))))
            while (true) frames += splitter.readInbound<io.netty.buffer.ByteBuf>() ?: break
            val login = net.minecraft.network.protocol.login.LoginProtocols.CLIENTBOUND.codec()
            val config = net.minecraft.network.protocol.configuration.ConfigurationProtocols.CLIENTBOUND.codec()
            val play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec()
            var phase = "LOGIN"; var decompress: io.netty.channel.embedded.EmbeddedChannel? = null
            frames.forEachIndexed { i, raw ->
                try {
                    val buf = if (decompress != null) { decompress!!.writeInbound(raw.retain()); decompress!!.readInbound<io.netty.buffer.ByteBuf>() } else raw
                    val p = when (phase) { "LOGIN" -> login.decode(buf); "CONFIG" -> config.decode(buf); else -> play.decode(buf) }
                    val deep = ((p as? net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket)?.let { c -> runCatching { deepChunkCheck(server, c) }.getOrElse { e -> "deep check FAILED: $e" } }
                        ?: (p as? net.minecraft.network.protocol.game.ClientboundCommandsPacket)?.let { c -> runCatching { deepCommandsCheck(server, c) }.getOrElse { e -> "deep commands check FAILED: $e" } }) ?: ""
                    log.info("[fission] stream #$i ($phase, ${raw.readableBytes()} wire bytes): ${p.javaClass.simpleName}, unread ${buf.readableBytes()} $deep")
                    when (p) {
                        is net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket -> decompress = io.netty.channel.embedded.EmbeddedChannel(net.minecraft.network.CompressionDecoder(p.compressionThreshold, false))
                        is net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket -> phase = "CONFIG"
                        is net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket -> phase = "PLAY"
                        is net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket -> phase = "CONFIG"
                    }
                } catch (t: Throwable) { log.warning("[fission] stream #$i ($phase): vanilla client FAILED: $t") }
            }
            log.info("[fission] stream check done: ${frames.size} frames")
        } catch (t: Throwable) { log.warning("[fission] stream check failed: $t") }
    }

    /**
     * Deep commands check: what the client does with ClientboundCommandsPacket (build the Brigadier tree from the entries); reports the root's
     * literal children and the children of `chat` (the master adds `/chat global|local|toggle`).
     */
    private fun deepCommandsCheck(server: MinecraftServer, p: net.minecraft.network.protocol.game.ClientboundCommandsPacket): String {
        val ctx = net.minecraft.commands.CommandBuildContext.simple(server.registryAccess(), net.minecraft.world.flag.FeatureFlags.REGISTRY.allFlags())
        val builder = object : net.minecraft.network.protocol.game.ClientboundCommandsPacket.NodeBuilder<Any> {
            override fun createLiteral(id: String) = com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<Any>(id)
            override fun createArgument(id: String, type: com.mojang.brigadier.arguments.ArgumentType<*>, suggestionId: net.minecraft.resources.Identifier?) = com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<Any, Any?>(id, type as com.mojang.brigadier.arguments.ArgumentType<Any?>)
            override fun configure(input: com.mojang.brigadier.builder.ArgumentBuilder<Any, *>, executable: Boolean, restricted: Boolean) = input
        }
        val root = p.getRoot(ctx, builder)
        val chat = root.getChild("chat")
        val fission = root.getChild("fission")
        val send = fission?.getChild("send")
        return "commands tree OK: ${root.children.size} root children, chat=${chat?.children?.map { it.name }}, fission=${fission?.children?.map { it.name }}, send->${send?.children?.map { c -> c.name + "->" + c.children.map { it.name } }}"
    }

    /**
     * `-Dfission.check-pipeline=<s2c file>`: feeds the master's byte stream into a real vanilla CLIENT pipeline (`Connection` with
     * CLIENTBOUND serialization: frame splitter, FlowControlHandler, compression, PacketDecoder, the terminal-packet protocol swap that pauses
     * reading) and performs the protocol switches the way vanilla's client listeners do, one "main thread" step at a time:
     * ClientLoginPacketListenerImpl.handleLoginFinished, ClientConfigurationPacketListenerImpl.handleConfigurationFinished,
     * ClientPacketListener.handleConfigurationStart. Bytes are fed in chunks of [chunk] bytes (-Dfission.check-chunk, default whole file).
     */
    private fun checkPipeline(server: MinecraftServer, file: String) {
        val chunk = System.getProperty("fission.check-chunk")?.toInt() ?: Int.MAX_VALUE
        try {
            val bytes = Files.readAllBytes(Path.of(file))
            val conn = net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND)
            val ch = io.netty.channel.embedded.EmbeddedChannel(object : io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
                override fun initChannel(c: io.netty.channel.Channel) {
                    net.minecraft.network.Connection.configureSerialization(c.pipeline(), net.minecraft.network.protocol.PacketFlow.CLIENTBOUND, false, null)
                    conn.configurePacketHandler(c.pipeline())
                }
            })
            val actions = java.util.ArrayDeque<() -> Unit>()
            var n = 0
            fun proxy(iface: Class<*>, protocol: net.minecraft.network.ConnectionProtocol, handle: (String, Array<Any?>?) -> Unit): Any =
                java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, a ->
                    when (m.name) {
                        "flow" -> net.minecraft.network.protocol.PacketFlow.CLIENTBOUND
                        "protocol" -> protocol
                        "isAcceptingMessages", "shouldHandleMessage" -> true
                        "onDisconnect" -> { log.warning("[fission] pipeline: vanilla client DISCONNECTED: ${a?.firstOrNull()?.let { d -> (d as net.minecraft.network.DisconnectionDetails).reason().string }}"); null }
                        "equals" -> false; "hashCode" -> 0; "toString" -> "proxy"
                        else -> { if (m.name.startsWith("handle")) { log.info("[fission] pipeline #${++n} ($protocol): ${m.name} ${a?.firstOrNull()?.javaClass?.simpleName}"); handle(m.name, a) }; null }
                    }
                }
            val play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()))
            lateinit var gameL: Any; lateinit var cfgL: Any; lateinit var loginL: Any
            @Suppress("UNCHECKED_CAST") fun <T : net.minecraft.network.PacketListener> setup(info: net.minecraft.network.ProtocolInfo<T>, l: Any) = conn.setupInboundProtocol(info, l as T)
            gameL = proxy(net.minecraft.network.protocol.game.ClientGamePacketListener::class.java, net.minecraft.network.ConnectionProtocol.PLAY) { name, _ ->
                if (name == "handleConfigurationStart") actions.add { setup(net.minecraft.network.protocol.configuration.ConfigurationProtocols.CLIENTBOUND, cfgL) }
            }
            cfgL = proxy(net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener::class.java, net.minecraft.network.ConnectionProtocol.CONFIGURATION) { name, _ ->
                if (name == "handleConfigurationFinished") actions.add { setup(play, gameL) }
            }
            loginL = proxy(net.minecraft.network.protocol.login.ClientLoginPacketListener::class.java, net.minecraft.network.ConnectionProtocol.LOGIN) { name, a ->
                if (name == "handleCompression") conn.setupCompression((a!![0] as net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket).compressionThreshold, false)
                if (name == "handleLoginFinished") actions.add { setup(net.minecraft.network.protocol.configuration.ConfigurationProtocols.CLIENTBOUND, cfgL) }
            }
            setup(net.minecraft.network.protocol.login.LoginProtocols.CLIENTBOUND, loginL)
            // The server writes in bursts separated by client replies (login ack, known packs, finish ack); cut the stream where it waits
            // (after frames 1, 3, 37 = LoginFinished, SelectKnownPacks, FinishConfiguration), then feed the rest. -Dfission.check-chunk overrides.
            val cuts = java.util.TreeSet<Int>()
            run { var i = 0; var f = 0; while (i < bytes.size) { var len = 0; var sh = 0; var j = i; while (true) { val b = bytes[j++].toInt(); len = len or ((b and 0x7f) shl sh); sh += 7; if (b and 0x80 == 0) break }; i = j + len; if (f in setOf(1, 3, 37)) cuts += i; f++ } }
            var off = 0
            while (off < bytes.size) {
                val end = if (chunk != Int.MAX_VALUE) minOf(bytes.size, off + chunk) else (cuts.higher(off) ?: bytes.size)
                ch.writeInbound(Unpooled.wrappedBuffer(bytes, off, end - off).copy()); off = end
                // the "main thread": run switches, then let the paused channel read again (FlowControlHandler.read -> dequeue)
                var ran = true; var guard = 0
                while (ran && guard++ < 1000) {
                    ch.runPendingTasks()
                    val a = actions.poll(); ran = a != null
                    if (a != null) try { a(); log.info("[fission] pipeline: main-thread step done, pipeline=${ch.pipeline().names()} autoRead=${ch.config().isAutoRead}") } catch (t: Throwable) { log.warning("[fission] pipeline: main-thread step failed: $t") }
                    ch.runPendingTasks(); ch.read(); ch.runPendingTasks()
                }
            }
            log.info("[fission] pipeline check done: $n packets handled, ${bytes.size - off} bytes unfed")
        } catch (t: Throwable) { log.warning("[fission] pipeline check failed: $t") }
    }

    /**
     * Deep chunk check, what the vanilla client does with a chunk packet: ClientPacketListener.updateLevelChunk -> LevelChunk.replaceWithPacketData
     * reads every section with LevelChunkSection.read and the renderer later asks for biomes (getNoiseBiome). Decoding the PACKET alone does not
     * parse the section bytes, which is how a wrongly encoded biome container went unnoticed (client crash "Missing Palette entry for index 0").
     */
    private fun deepChunkCheck(server: MinecraftServer, p: net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket): String {
        val factory = net.minecraft.world.level.chunk.PalettedContainerFactory.create(server.registryAccess())
        val buf = p.chunkData().readBuffer
        val sections = server.overworld().sectionsCount
        var biomeLookups = 0
        for (i in 0 until sections) {
            val s = net.minecraft.world.level.chunk.LevelChunkSection(factory)
            s.read(buf)
            for (x in 0..3) for (y in 0..3) for (z in 0..3) { s.getNoiseBiome(x, y, z); biomeLookups++ }
        }
        return "deep check: $sections sections read, $biomeLookups biome lookups ok, unread ${buf.readableBytes()}"
    }

    /** `-PrecordChunk=<file>`: writes the frame (id + payload) vanilla itself builds for an empty overworld chunk at 0,0: LevelChunk(level, pos) + ClientboundLevelChunkWithLightPacket. */
    private fun recordChunk(server: MinecraftServer, file: String) {
        try {
            val level = server.overworld()
            val chunk = net.minecraft.world.level.chunk.LevelChunk(level, net.minecraft.world.level.ChunkPos(0, 0))
            val pkt = net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(chunk, level.lightEngine, null, null)
            val codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec()
            val out = Unpooled.buffer()
            @Suppress("UNCHECKED_CAST") (codec as net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<*>>).encode(out, pkt)
            Files.write(Path.of(file), ByteArray(out.readableBytes()).also { out.readBytes(it) })
            log.info("[fission] recorded vanilla empty chunk frame (${Files.size(Path.of(file))} bytes) to $file; ${deepChunkCheck(server, pkt)}")
        } catch (t: Throwable) { log.warning("[fission] chunk recording failed: $t") }
    }

    @JvmStatic fun once(server: MinecraftServer) {
        if (done) return
        System.getProperty("fission.record-chunk")?.let { recordChunk(server, it) }
        // a comma separated list of files is replayed one after the other (each file is one client connection)
        System.getProperty("fission.check-stream")?.let { spec -> spec.split(',').filter { it.isNotBlank() }.forEach { f -> log.info("[fission] stream check of $f"); checkStream(server, f) }; log.info("[fission] all stream checks finished") }
        System.getProperty("fission.check-pipeline")?.let { checkPipeline(server, it) }
        if (System.getProperty("fission.check-ids") != null) runCatching {
            fun id(c: net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, out net.minecraft.network.protocol.Packet<*>>, p: net.minecraft.network.protocol.Packet<*>): Int {
                val b = Unpooled.buffer(); @Suppress("UNCHECKED_CAST") (c as net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<*>>).encode(b, p); return b.readUnsignedByte().toInt()
            }
            val play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec()
            val cfg = net.minecraft.network.protocol.configuration.ConfigurationProtocols.CLIENTBOUND.codec()
            val names = StringBuilder()
            GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets { t, i -> if (i in 20..30 || i == 32) names.append("play[$i]=${t.id()} ") }
            net.minecraft.network.protocol.configuration.ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details().listPackets { t, i -> names.append("config[$i]=${t.id()} ") }
            log.info("[fission] vanilla clientbound ids: $names")
            log.info("[fission] vanilla ids: play-clientbound start_configuration=${id(play, net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket.INSTANCE)} keep_alive=${id(play, net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(1L))} " +
                "config-clientbound finish=${id(cfg, net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket.INSTANCE)} keep_alive=${id(cfg, net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(1L))}")
        }.onFailure { log.warning("[fission] id check failed: $it") }
        val file = System.getProperty("fission.check-limbo") ?: run { done = true; return }
        done = true
        try {   // what the recorder's fake client sends: select-known-packs [minecraft:core:26.3], decoded by vanilla's serverbound configuration codec
            val kp = byteArrayOf(0x07, 0x01, 0x09) + "minecraft".toByteArray() + byteArrayOf(0x04) + "core".toByteArray() + byteArrayOf(0x04) + "26.3".toByteArray()
            val b = Unpooled.wrappedBuffer(kp)
            val pkt = net.minecraft.network.protocol.configuration.ConfigurationProtocols.SERVERBOUND.codec().decode(b)
            log.info("[fission] limbo check: serverbound configuration frame 07... decoded as ${pkt.javaClass.simpleName}, unread ${b.readableBytes()}")
        } catch (t: Throwable) { log.warning("[fission] limbo check: serverbound configuration frame 07 FAILED: $t") }
        try {
            val d = DataInputStream(Files.newInputStream(Path.of(file)))
            val frames = List(d.readInt()) { ByteArray(d.readInt()).also { b -> d.readFully(b) } }
            val codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec()
            frames.forEachIndexed { i, f ->
                val buf = Unpooled.wrappedBuffer(f)
                try {
                    val p = codec.decode(buf)
                    val deep = (p as? net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket)?.let { c -> runCatching { deepChunkCheck(server, c) }.getOrElse { e -> "deep check FAILED: $e" } } ?: ""
                    log.info("[fission] limbo check #$i: vanilla decoded ${p.javaClass.simpleName}, unread bytes ${buf.readableBytes()} $deep")
                } catch (t: Throwable) { log.warning("[fission] limbo check #$i: vanilla FAILED to decode (${f.size} bytes): $t") }
            }
            log.info("[fission] limbo check done: ${frames.size} frames")
        } catch (t: Throwable) { log.warning("[fission] limbo check failed: $t") }
    }
}
