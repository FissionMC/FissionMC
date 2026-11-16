package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Things that travel with a player from one backend to another: pets, riders, and the stats/advancements files.
 *
 * Old backend, on [PlayerDataFlush]: [collectAndSend] serializes the pets within the radius and the riders of the player (vanilla `Entity.saveAsPassenger`), the
 * player is saved, `PlayerFlushed` ends it. New backend: [onMessage] keeps the [EntityTransfer] until the player is placed ([tick]), spawns the entities
 * (`addWithUUID`, the load-from-disk path: no CreatureSpawnEvent) next to it, re-mounts riders and confirms with [EntityTransferAck]; the old backend then
 * discards its copies ([EntityRemove]). Mounts and shoulder entities are part of the player NBT and are restored by vanilla.
 */
object TravelBackend {
    private val log = Logger.getLogger("FissionMC")
    private class Incoming(val blobs: List<EntityBlob>, val at: Long = System.currentTimeMillis())
    private val incoming = ConcurrentHashMap<UUID, Incoming>()
    private const val PENDING_TTL_MS = 60_000L

    /** Node link thread. */
    fun onMessage(m: NodeMessage, server: MinecraftServer?) {
        when (m) {
            is EntityTransfer -> incoming[UUID(m.playerHigh, m.playerLow)] = Incoming(m.blobs)
            is EntityRemove -> { RegionsBackend.handoverDone(m.uuids); server?.execute { remove(server, m.uuids) } }
            is PlayerFile -> if (m.present && server != null) writeFile(server, UUID(m.uuidHigh, m.uuidLow), m.kind, m.bytes)
        }
    }

    // ---------------------------------------------------------------- stats and advancements files
    private fun writeFile(server: MinecraftServer, uuid: UUID, kind: Int, bytes: ByteArray) {
        try {
            val dir = server.getWorldPath(if (kind == PlayerFileKind.STATS) LevelResource.PLAYER_STATS_DIR else LevelResource.PLAYER_ADVANCEMENTS_DIR)
            Files.createDirectories(dir); Files.write(dir.resolve("$uuid.json"), bytes)
        } catch (t: Throwable) { log.warning("[fission] cannot write the master's ${if (kind == PlayerFileKind.STATS) "stats" else "advancements"} of $uuid: $t") }
    }

    /** `ServerStatsCounter.save` / `PlayerAdvancements.save` mixins: report the file that was just written. */
    @JvmStatic fun onFileSaved(file: Path, kind: Int) {
        val uuid = try { UUID.fromString(file.fileName.toString().removeSuffix(".json")) } catch (_: IllegalArgumentException) { return }
        if (!PlayerDataBackend.isRelayed(uuid)) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        try {
            val bytes = Files.readAllBytes(file)
            if (bytes.size <= MAX_PLAYER_DATA) link.send(PlayerFileSave(uuid.mostSignificantBits, uuid.leastSignificantBits, kind, bytes))
        } catch (t: Throwable) { log.warning("[fission] cannot report $file: $t") }
    }

    // ---------------------------------------------------------------- old backend: collect
    private fun encode(tag: CompoundTag): ByteArray { val o = ByteArrayOutputStream(); DataOutputStream(DeflaterOutputStream(o)).use { NbtIo.write(tag, it) }; return o.toByteArray() }
    private fun decode(b: ByteArray): CompoundTag = DataInputStream(InflaterInputStream(b.inputStream()).buffered()).use { NbtIo.read(it, NbtAccounter.unlimitedHeap()) }

    /** Serialized form of [e] for a handover or transfer (null when it cannot be saved or is too big). */
    fun blobFor(kind: Int, e: Entity): EntityBlob? = blob(kind, e)

    private fun blob(kind: Int, e: Entity): EntityBlob? {
        val out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.level().registryAccess())
        if (!e.saveAsPassenger(out)) return null
        val bytes = encode(out.buildResult())
        return if (bytes.size > MAX_ENTITY_BLOB) { log.warning("[fission] ${e.type} ${e.uuid} is too big to travel"); null } else EntityBlob(kind, e.uuid.mostSignificantBits, e.uuid.leastSignificantBits, bytes)
    }

    /** The shared vehicle [root] of [player] as a MOUNT blob: the seat (the entity [player] sits on) in front of the vehicle tree. */
    private fun mountBlob(player: ServerPlayer, root: Entity): EntityBlob? {
        val seat = player.vehicle ?: return null
        val tree = blob(EntityBlobKind.MOUNT, root) ?: return null
        val b = ByteArray(16 + tree.bytes.size)
        java.nio.ByteBuffer.wrap(b).putLong(seat.uuid.mostSignificantBits).putLong(seat.uuid.leastSignificantBits).put(tree.bytes)
        return EntityBlob(EntityBlobKind.MOUNT, root.uuid.mostSignificantBits, root.uuid.leastSignificantBits, b)
    }

    /** Which entities travel with [player] (server thread). Pure selection: used by [collectAndSend] and the test hook. */
    fun travelers(player: ServerPlayer, petRadius: Int): List<Pair<Int, Entity>> {
        val out = ArrayList<Pair<Int, Entity>>()
        if (player.isPassenger && !player.rootVehicle.hasExactlyOnePlayerPassenger()) out += EntityBlobKind.MOUNT to player.rootVehicle   // vanilla saves no RootVehicle with several players aboard
        for (p in player.passengers) if (p !is ServerPlayer && !p.isRemoved) out += EntityBlobKind.RIDER to p
        if (petRadius > 0) {
            val level = player.level(); val root = player.rootVehicle
            val vehicleTree = HashSet<Entity>().also { s -> s += root; root.indirectPassengers.forEach { s += it } }   // travels as RootVehicle
            for (e in level.getEntitiesOfClass(Entity::class.java, player.boundingBox.inflate(petRadius.toDouble()))) {
                if (e === player || e is ServerPlayer || e.isRemoved || e !is OwnableEntity) continue
                if (e.ownerReference?.uuid != player.uuid) continue
                if (e is TamableAnimal && e.isInSittingPose) continue                         // sitting pets stay home
                if (e.isPassenger || e in vehicleTree) continue
                if (e is Leashable && e.isLeashed && e.leashHolder !is net.minecraft.world.entity.player.Player) continue   // tied to a fence
                out += EntityBlobKind.PET to e
            }
        }
        return out
    }

    /** Server thread, before the player is saved for a move: tell the master which entities go along. */
    fun collectAndSend(server: MinecraftServer, uuid: UUID, petRadius: Int) {
        val player = server.playerList.getPlayer(uuid) ?: return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        val blobs = try { travelers(player, petRadius).mapNotNull { (k, e) -> if (k == EntityBlobKind.MOUNT) mountBlob(player, e) else blob(k, e) } } catch (t: Throwable) { log.warning("[fission] collecting the entities of ${player.gameProfile.name()} failed: $t"); emptyList() }
        net.fissionmc.net.trace.Trace.emit("travel.collect") { player(player.gameProfile.name(), uuid); int("entities", blobs.size); ints("kinds", blobs.map { it.kind }.toIntArray()); strs("uuids", blobs.map { UUID(it.uuidHigh, it.uuidLow).toString() }) }
        link.send(EntityTransfer(uuid.mostSignificantBits, uuid.leastSignificantBits, blobs))   // empty list clears an older pending transfer
        if (blobs.isNotEmpty()) log.info("[fission] ${player.gameProfile.name()} takes ${blobs.size} entities along: ${blobs.size} (${travelersSummary(player, petRadius)})")
    }

    private fun travelersSummary(player: ServerPlayer, petRadius: Int) = travelers(player, petRadius).joinToString { (k, e) -> "${when (k) { EntityBlobKind.PET -> "pet"; EntityBlobKind.MOUNT -> "shared vehicle"; else -> "rider" }} ${e.type.toShortString()} ${e.uuid.toString().take(8)}" }

    // ---------------------------------------------------------------- new backend: spawn
    /** Server thread, every tick. */
    fun tick(server: MinecraftServer) {
        if (mountHints.isNotEmpty()) tickHints(server)
        if (incoming.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((u, inc) in incoming.entries.toList()) {
            val player = server.playerList.getPlayer(u)
            if (player == null) { if (now - inc.at > PENDING_TTL_MS) { incoming.remove(u); log.warning("[fission] entities for $u were never needed (the player did not arrive), dropped") }; continue }
            incoming.remove(u)
            try { spawn(server, player, inc.blobs) } catch (t: Throwable) { log.warning("[fission] spawning the entities of ${player.gameProfile.name()} failed: $t") }
        }
    }

    private fun spawn(server: MinecraftServer, player: ServerPlayer, blobs: List<EntityBlob>) {
        val level = player.level()
        val spawned = ArrayList<Long>()
        var pets = 0
        for (b in blobs) {
            if (b.kind == EntityBlobKind.MOUNT) { try { mount(level, player, b) } catch (t: Throwable) { log.warning("[fission] re-mounting ${player.gameProfile.name()} failed: $t") }; continue }
            val old = level.getEntity(UUID(b.uuidHigh, b.uuidLow))
            if (old != null && old !is ServerPlayer) old.discard()          // a stale copy with the same uuid: the transferred one is newer
            val tag = decode(b.bytes)
            val input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag)
            val angle = (pets++) * 1.0472 + 0.5   // pets stand in a ring about 2 blocks around the owner
            val e = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.LOAD, EntityProcessor { ent ->
                if (b.kind == EntityBlobKind.PET) placeNear(level, ent, player, angle) else ent.setPos(player.x, player.y, player.z)
                if (level.addWithUUID(ent)) ent else null
            })
            if (e == null) { log.warning("[fission] could not spawn a traveling entity ${b.uuidHigh.toString(16)} for ${player.gameProfile.name()}"); continue }
            if (b.kind == EntityBlobKind.RIDER) e.startRiding(player, true, false)
            for (x in listOf(e) + e.indirectPassengers) { spawned += x.uuid.mostSignificantBits; spawned += x.uuid.leastSignificantBits }
            net.fissionmc.net.trace.Trace.emit("entity.spawn.transferred") { str("entity", e.uuid.toString()); str("type", e.type.descriptionId); int("id", e.id); str("dim", level.dimension().identifier().toString()); double("x", e.x); double("y", e.y); double("z", e.z); int("passengers", e.indirectPassengers.count()) }
        }
        log.info("[fission] ${player.gameProfile.name()}: ${spawned.size / 2} traveling entities spawned")
        NodeBootstrap.link?.takeIf { it.isOpen }?.send(EntityTransferAck(player.uuid.mostSignificantBits, player.uuid.leastSignificantBits, spawned))
    }

    // ---------------------------------------------------------------- shared vehicles (several players aboard)
    private class Hint(val vehicle: UUID, val until: Long)
    private val mountHints = ConcurrentHashMap<UUID, Hint>()
    private const val HINT_TTL_MS = 15_000L

    /**
     * The player sat in a vehicle with other players: the first of them to arrive spawns it (the vehicle tree travelled in the blob, the other players left out), the others find it by uuid. The vehicle is NOT
     * reported as spawned, so the old backend keeps its copy until its last player is removed (vanilla removes the vehicle with its last player). If the vehicle is not there (yet), [tickHints] retries.
     */
    private fun mount(level: net.minecraft.server.level.ServerLevel, player: ServerPlayer, b: EntityBlob) {
        val seatId = java.nio.ByteBuffer.wrap(b.bytes).let { UUID(it.getLong(), it.getLong()) }
        val rootId = UUID(b.uuidHigh, b.uuidLow)
        val live = level.getEntity(rootId)
        if (live == null || live.isRemoved || live.tickCount == 0) {   // none, or only a ghost copy (never ticked): the travelled one replaces it
            live?.let { if (it !is ServerPlayer) it.discard() }
            val tag = decode(b.bytes.copyOfRange(16, b.bytes.size))
            val input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag)
            EntityType.loadEntityRecursive(input, level, EntitySpawnReason.LOAD, EntityProcessor { ent -> if (level.addWithUUID(ent)) ent else null })?.let { restoreMotion(it, tag) }
        }
        mountHints[player.uuid] = Hint(seatId, System.currentTimeMillis() + HINT_TTL_MS)
        tryMount(level, player, seatId, fresh = true)
    }

    private fun tryMount(level: net.minecraft.server.level.ServerLevel, player: ServerPlayer, seatId: UUID, fresh: Boolean = false): Boolean {
        if (player.vehicle?.uuid == seatId) { mountHints.remove(player.uuid); return true }
        val seat = level.getEntity(seatId) ?: return false
        if (seat.isRemoved || (!fresh && seat.tickCount == 0)) return false   // a copy that never ticked is a ghost
        if (player.startRiding(seat, false, false)) { mountHints.remove(player.uuid); log.info("[fission] ${player.gameProfile.name()} sits in the shared ${seat.type.toShortString()} again"); return true }
        return false
    }

    private fun tickHints(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        for ((u, h) in mountHints.entries.toList()) {
            val p = server.playerList.getPlayer(u)
            if (p == null || now > h.until) { mountHints.remove(u); continue }
            if (p.isPassenger) { if (p.vehicle?.uuid == h.vehicle) mountHints.remove(u); continue }
            try { tryMount(p.level(), p, h.vehicle) } catch (t: Throwable) { mountHints.remove(u) }
        }
    }

    /**
     * Hand-off position (the master tells the new backend where the client really is, see PlayerMoveTo): a player who steers a vehicle is moved WITH it (the master's position is the vehicle's, from
     * ServerboundMoveVehicle; the vanilla check "moved too quickly" compares against the vehicle's position), a plain passenger only gets the teleport handshake. The teleport answer is what the master waits for.
     */
    fun handoff(player: ServerPlayer, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        net.fissionmc.net.trace.Trace.emit("handoff.position") { player(player.gameProfile.name(), player.uuid); double("x", x); double("y", y); double("z", z); double("yaw", yaw.toDouble()); double("pitch", pitch.toDouble()); bool("vehicle", player.rootVehicle !== player) }
        val root = player.rootVehicle
        if (root !== player && root.controllingPassenger === player && root.distanceToSqr(x, y, z) < 64.0 * 64.0) {
            root.absSnapTo(x, y, z, root.yRot, root.xRot)
            for (p in root.passengers) root.positionRider(p)
            player.level().chunkSource.move(player)
            keepMotionTeleport(player, yaw, pitch)
        } else if (root !== player) keepMotionTeleport(player, yaw, pitch)
        else player.connection.teleport(x, y, z, yaw, pitch)
    }

    /** The teleport handshake at the player's own place without zeroing its momentum (a plain teleport(x,y,z,..) carries delta 0). */
    private fun keepMotionTeleport(player: ServerPlayer, yaw: Float, pitch: Float) =
        player.connection.teleport(net.minecraft.world.entity.PositionMoveRotation(player.position(), player.deltaMovement, yaw, pitch), emptySet())

    /** Re-apply the saved "Motion" to [e] (and the "Rotation" it was saved with): nothing between load and the first tick may have reset it, and a hand-over must not stop a rolling cart. */
    private fun restoreMotion(e: Entity, tag: CompoundTag) {
        val m = tag.getList("Motion").orElse(null)
        if (m != null && m.size == 3) e.deltaMovement = net.minecraft.world.phys.Vec3(m.getDoubleOr(0, 0.0), m.getDoubleOr(1, 0.0), m.getDoubleOr(2, 0.0))
    }

    /** `ServerPlayer.loadAndSpawnParentVehicle` HEAD: a ghost copy of the vehicle (and its passengers) with the same uuids would make vanilla refuse the restored one ("duplicated UUID") and the player would arrive on foot. */
    @JvmStatic fun dropStaleVehicle(level: net.minecraft.server.level.ServerLevel, playerInput: net.minecraft.world.level.storage.ValueInput) {
        val root = playerInput.child("RootVehicle").orElse(null) ?: return
        fun walk(v: net.minecraft.world.level.storage.ValueInput) {
            v.read("UUID", net.minecraft.core.UUIDUtil.CODEC).ifPresent { id -> level.getEntity(id)?.let { if (it !is ServerPlayer) it.discard() } }
            for (c in v.childrenListOrEmpty("Passengers")) walk(c)
        }
        walk(root.childOrEmpty("Entity"))
    }

    /** A free spot around the owner (like a pet teleporting to its owner): the ring at 2, 1 and 0 blocks, up to 3 blocks higher; the owner's own spot as the last resort. */
    private fun placeNear(level: net.minecraft.server.level.ServerLevel, ent: Entity, player: ServerPlayer, angle: Double) {
        for (radius in doubleArrayOf(2.0, 1.0, 0.0)) for (up in 0..3) {
            ent.setPos(player.x + Math.cos(angle) * radius, player.y + up, player.z + Math.sin(angle) * radius)
            if (level.noCollision(ent)) return
        }
        ent.setPos(player.x, player.y, player.z)
    }

    /** Region handover: spawn the entities exactly where their NBT says (they crossed a border), discard a stale copy with the same uuid. Returns the spawned uuids (high/low pairs). */
    fun spawnAt(level: net.minecraft.server.level.ServerLevel, blobs: List<EntityBlob>): List<Long> {
        val spawned = ArrayList<Long>()
        for (b in blobs) try {
            level.getEntity(UUID(b.uuidHigh, b.uuidLow))?.let { if (it !is ServerPlayer) it.discard() }
            val tag = decode(b.bytes)
            val input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag)
            val e = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.LOAD, EntityProcessor { ent -> if (level.addWithUUID(ent)) ent else null }) ?: continue
            restoreMotion(e, tag)
            for (x in listOf(e) + e.indirectPassengers) { spawned += x.uuid.mostSignificantBits; spawned += x.uuid.leastSignificantBits }
        } catch (t: Throwable) { log.warning("[fission] handover spawn failed: $t") }
        return spawned
    }

    // ---------------------------------------------------------------- old backend: remove after the ack
    private fun remove(server: MinecraftServer, pairs: List<Long>) {
        var n = 0
        for (i in 0 until pairs.size / 2) {
            val u = UUID(pairs[i * 2], pairs[i * 2 + 1])
            for (level in server.allLevels) { val e = level.getEntity(u); if (e != null && e !is ServerPlayer) { e.discard(); n++; break } }
        }
        net.fissionmc.net.trace.Trace.emit("entity.remove.moved") { int("requested", pairs.size / 2); int("removed", n) }
        log.info("[fission] removed $n entities that moved to another backend")
    }
}
