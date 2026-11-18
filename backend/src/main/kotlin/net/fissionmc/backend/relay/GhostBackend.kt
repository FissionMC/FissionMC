package net.fissionmc.backend.relay

import io.netty.buffer.Unpooled
import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Backend side of the ghost players (`.dev/docs/tablist-ghosts-commands.md` section 2) and of the cross-backend command permission check (section 3).
 *
 * Every 2 ticks, for every relayed player: [GhostPose] when position/rotation changed; every 10 ticks [GhostMeta] when the encoded entity data or equipment changed
 * (vanilla's own `ClientboundSetEntityDataPacket` / `ClientboundSetEquipmentPacket` codecs with a registry-aware buffer, entity id stripped); [GhostSwing] when an arm swing starts.
 * Nothing is sent for players that are not relayed (no master to show them to).
 */
object GhostBackend {
    private val log = Logger.getLogger("FissionMC")
    private class Last(var dim: String = "", var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0, var yaw: Float = 0f, var pitch: Float = 0f, var head: Float = 0f, var ground: Boolean = false) {
        var data: ByteArray? = null; var equip: ByteArray? = null; var swinging = false; var swingProgress = 0f
    }
    private val last = ConcurrentHashMap<UUID, Last>()
    private val reportPace = net.fissionmc.net.Pacer(100)   // 2 ticks at 20 tps, every tick on a slower server (never later than 1 report per tick)
    private val metaPace = net.fissionmc.net.Pacer(500)     // 10 ticks at 20 tps
    private val slots = listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)

    @JvmStatic fun tick(server: MinecraftServer) {
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        if (!reportPace.due()) return
        val metaTick = metaPace.due()
        val seen = HashSet<UUID>()
        for (p in server.playerList.players) {
            if (!PlayerDataBackend.isRelayed(p.uuid)) continue
            seen += p.uuid
            try { report(server, link, p, metaTick) } catch (t: Throwable) { if (net.fissionmc.net.FissionDebug.on) log.warning("[fission] ghost report for ${p.plainTextName} failed: $t") }
        }
        last.keys.removeIf { it !in seen }
    }

    private fun report(server: MinecraftServer, link: net.fissionmc.net.NodeLink, p: ServerPlayer, metaTick: Boolean) {
        val l = last.computeIfAbsent(p.uuid) { Last().also { it.dim = "?" } }
        val dim = p.level().dimension().identifier().toString()
        val dx = p.x - l.x; val dy = p.y - l.y; val dz = p.z - l.z
        val moved = dim != l.dim || dx * dx + dy * dy + dz * dz >= 1.0 / 256 || Math.abs(p.yRot - l.yaw) >= 0.5f || Math.abs(p.xRot - l.pitch) >= 0.5f || Math.abs(p.yHeadRot - l.head) >= 0.5f || p.onGround() != l.ground
        if (moved) {
            l.dim = dim; l.x = p.x; l.y = p.y; l.z = p.z; l.yaw = p.yRot; l.pitch = p.xRot; l.head = p.yHeadRot; l.ground = p.onGround()
            link.send(GhostPose(p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, dim, p.x, p.y, p.z, p.yRot, p.xRot, p.yHeadRot, p.onGround()))
        }
        val swinging = p.isSwinging; val progress = p.getSwingAnimation(0f)
        if (swinging && (!l.swinging || progress < l.swingProgress - 0.3f)) link.send(GhostSwing(p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, 0))
        l.swinging = swinging; l.swingProgress = progress
        if (metaTick || l.data == null) {
            val data = encodeData(server, p); val equip = encodeEquipment(server, p)
            if (l.data == null || !data.contentEquals(l.data) || !equip.contentEquals(l.equip)) {
                l.data = data; l.equip = equip
                link.send(GhostMeta(p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, data, equip))
            }
        }
    }

    /** The payload of a packet after its entity id varint. */
    private fun stripId(buf: RegistryFriendlyByteBuf): ByteArray {
        buf.readVarInt()
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    @JvmStatic fun encodeData(server: MinecraftServer, p: ServerPlayer): ByteArray = GhostPackets.entityData(server, p)

    @JvmStatic fun encodeEquipment(server: MinecraftServer, p: ServerPlayer): ByteArray {
        val worn = slots.map { com.mojang.datafixers.util.Pair(it, p.getItemBySlot(it).copy()) }.filter { !it.second.isEmpty }
        if (worn.isEmpty()) return ByteArray(0)
        val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess())
        ClientboundSetEquipmentPacket.STREAM_CODEC.encode(buf, ClientboundSetEquipmentPacket(p.id, worn))
        return stripId(buf)
    }

    /** The master asks whether a relayed player may run a command (cross-backend commands). Runs on the server thread. */
    @JvmStatic fun checkCommand(server: MinecraftServer, m: CommandCheck, playerName: String?) {
        server.execute {
            var present = true; var why = ""; var who = ""
            val allowed = try {
                val p = playerName?.let { server.playerList.getPlayerByName(it) }
                if (p == null) { present = false; why = "player ${playerName ?: "#" + m.playerId} is not on this backend (yet)"; false } else {
                    val b = p.bukkitEntity
                    who = "op=${b.isOp} perm(minecraft.command.tp)=${b.hasPermission("minecraft.command.tp")} fissionLevel=${PermissionBackend.level(p.uuid)}"
                    val pr = server.commands.dispatcher.parse(m.command, p.createCommandSourceStack())
                    net.minecraft.commands.Commands.validateParseResults(pr)
                    val ok = pr.context.command != null
                    if (!ok) why = "parsed but no executable command"
                    ok
                }
            } catch (e: com.mojang.brigadier.exceptions.CommandSyntaxException) { why = "parse: ${e.message}"; false }
            catch (t: Throwable) { why = "failed: $t"; false }
            log.info("[fission] command check for $playerName: '/${m.command}' -> ${if (!present) "player not here yet" else if (allowed) "allowed" else "DENIED"}${if (why.isNotEmpty()) " ($why)" else ""} [$who]")
            NodeBootstrap.link?.takeIf { it.isOpen }?.send(CommandCheckResult(m.playerId, m.seq, allowed, present))
        }
    }
}
