package net.fissionmc.protocol

/**
 * Backend -> master (96): where a relayed player is right now, so the master can show it as a "ghost" entity to players of OTHER backends. Sent every 2 ticks when the pose changed.
 * [x] [y] [z] in blocks, [yaw] [pitch] [headYaw] in degrees, [dimension] like `minecraft:overworld`.
 */
data class GhostPose(val uuidHigh: Long, val uuidLow: Long, val dimension: String, val x: Double, val y: Double, val z: Double,
                     val yaw: Float, val pitch: Float, val headYaw: Float, val onGround: Boolean) : NodeMessage

/**
 * Backend -> master (97): what a ghost looks like. [entityData] is the payload of vanilla's `ClientboundSetEntityDataPacket` after its entity id, [equipment] the payload of
 * `ClientboundSetEquipmentPacket` after its entity id (empty = nothing worn); both are encoded by the backend with vanilla's registry-aware codecs.
 */
class GhostMeta(val uuidHigh: Long, val uuidLow: Long, val entityData: ByteArray, val equipment: ByteArray) : NodeMessage

/** Backend -> master (98): the player swung an arm ([hand] 0 main, 1 off hand). */
data class GhostSwing(val uuidHigh: Long, val uuidLow: Long, val hand: Int) : NodeMessage

/** Master -> backend (99): may [playerId] run [command] (text without the slash)? Answered by [CommandCheckResult] with the same [seq]. */
data class CommandCheck(val playerId: Long, val seq: Long, val command: String) : NodeMessage

/** Backend -> master (100): the answer to [CommandCheck]: true when the command parses for the player's command source (its permission requirements hold). */
/** [present] false = the backend does not have the player (yet): [allowed] is meaningless then (used to wait for a player that was just moved there). */
data class CommandCheckResult(val playerId: Long, val seq: Long, val allowed: Boolean, val present: Boolean = true) : NodeMessage

fun MessageRegistry.registerGhostMessages(): MessageRegistry = this
    .register<GhostPose>(96,
        { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeString(m.dimension, 128).writeDouble(m.x).writeDouble(m.y).writeDouble(m.z).writeFloat(m.yaw).writeFloat(m.pitch).writeFloat(m.headYaw).writeBoolean(m.onGround) },
        { b -> GhostPose(b.readLong(), b.readLong(), b.readString(128), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readFloat(), b.readBoolean()) })
    .register<GhostMeta>(97,
        { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeByteArray(m.entityData, 65536).writeByteArray(m.equipment, 65536) },
        { b -> GhostMeta(b.readLong(), b.readLong(), b.readByteArray(65536), b.readByteArray(65536)) })
    .register<GhostSwing>(98, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeVarInt(m.hand) }, { b -> GhostSwing(b.readLong(), b.readLong(), b.readVarInt()) })
    .register<CommandCheck>(99, { m, b -> b.writeLong(m.playerId).writeLong(m.seq).writeString(m.command, 1024) }, { b -> CommandCheck(b.readLong(), b.readLong(), b.readString(1024)) })
    .register<CommandCheckResult>(100, { m, b -> b.writeLong(m.playerId).writeLong(m.seq).writeBoolean(m.allowed).writeBoolean(m.present) }, { b -> CommandCheckResult(b.readLong(), b.readLong(), b.readBoolean(), b.readBoolean()) })
