package net.fissionmc.protocol

/**
 * Master -> backend (87): "your player [uuid] is really HERE": the master's latest knowledge of the client's position and rotation (parsed from its movement packets). Sent at the
 * end of a backend switch, because the position in the player data the new backend placed the player at is the one saved when the switch started (the client kept moving for
 * the 0.1-2 s the switch takes). The backend teleports the player there (`ServerGamePacketListenerImpl.teleport`), which also resets its movement validation baseline.
 * Wire id 87 (ids are wire format; 83-86 are the world settings).
 */
data class PlayerMoveTo(val uuidHigh: Long, val uuidLow: Long, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) : NodeMessage

fun MessageRegistry.registerPositionMessages(): MessageRegistry = this
    .register<PlayerMoveTo>(87, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeDouble(m.x).writeDouble(m.y).writeDouble(m.z).writeFloat(m.yaw).writeFloat(m.pitch) },
        { b -> PlayerMoveTo(b.readLong(), b.readLong(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat()) })
