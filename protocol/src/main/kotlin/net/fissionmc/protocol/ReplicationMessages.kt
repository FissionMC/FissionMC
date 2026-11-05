package net.fissionmc.protocol

import io.netty.buffer.ByteBuf

/**
 * World-state events replicated between backends through the master (decision D3, conservative set).
 * Events are plain data; the master only orders them, backends interpret them.
 */
sealed interface ReplicatedEvent

/** A block in [dimension] (e.g. `minecraft:overworld`) became the block state with global id [stateId] (`Block.getId`). */
data class BlockChange(val dimension: String, val x: Int, val y: Int, val z: Int, val stateId: Int, val cause: String) : ReplicatedEvent

/** The entity with this uuid was removed (killed/despawned for good) in [dimension]. */
data class EntityRemoved(val dimension: String, val uuidHigh: Long, val uuidLow: Long, val cause: String) : ReplicatedEvent

/** Backend -> master: a change this backend made locally. The master assigns the sequence number. */
class EventSubmit(val event: ReplicatedEvent) : NodeMessage

/** Master -> backend: [event] is number [seq] in the single global order; [origin] is the node that submitted it. Also echoed to the origin. */
data class SequencedEvent(val seq: Long, val origin: String, val event: ReplicatedEvent) : NodeMessage

/** Backend -> master: events up to and including [seq] were applied. Informational (the master tracks it per node). */
data class EventAck(val seq: Long) : NodeMessage

/**
 * Backend -> master, once per (re)connect after the world pull: "I run master epoch [epoch] (0 = none yet) and have applied up to [lastSeq]".
 * The master replies with everything after [lastSeq] and then streams live events.
 */
data class ResumeFrom(val epoch: Long, val lastSeq: Long) : NodeMessage

/** Master -> backend: the master's event log restarted (new [epoch]); the backend's state may have diverged. Sent before any replay. */
data class LogReset(val epoch: Long) : NodeMessage

/** Master -> backend: the requested history was already dropped from the bounded log; the oldest retained seq is [oldestSeq]. */
data class LogTruncated(val oldestSeq: Long) : NodeMessage

private fun ByteBuf.writeEvent(e: ReplicatedEvent) {
    when (e) {
        is BlockChange -> { writeByte(0); writeString(e.dimension, 128).writeInt(e.x).writeInt(e.y).writeInt(e.z).writeVarInt(e.stateId).writeString(e.cause, 64) }
        is EntityRemoved -> { writeByte(1); writeString(e.dimension, 128).writeLong(e.uuidHigh).writeLong(e.uuidLow).writeString(e.cause, 64) }
    }
}

private fun ByteBuf.readEvent(): ReplicatedEvent = when (val t = readByte().toInt()) {
    0 -> BlockChange(readString(128), readInt(), readInt(), readInt(), readVarInt(), readString(64))
    1 -> EntityRemoved(readString(128), readLong(), readLong(), readString(64))
    else -> error("Unknown replicated event type $t")
}

/** Registers replication messages (ids 32-47). Ids are wire format: never reuse one. */
fun MessageRegistry.registerReplicationMessages(): MessageRegistry = this
    .register<EventSubmit>(32, { m, b -> b.writeEvent(m.event) }, { b -> EventSubmit(b.readEvent()) })
    .register<SequencedEvent>(33,
        { m, b -> b.writeLong(m.seq).writeString(m.origin, 64); b.writeEvent(m.event) },
        { b -> SequencedEvent(b.readLong(), b.readString(64), b.readEvent()) })
    .register<EventAck>(34, { m, b -> b.writeLong(m.seq) }, { b -> EventAck(b.readLong()) })
    .register<ResumeFrom>(35, { m, b -> b.writeLong(m.epoch).writeLong(m.lastSeq) }, { b -> ResumeFrom(b.readLong(), b.readLong()) })
    .register<LogReset>(36, { m, b -> b.writeLong(m.epoch) }, { b -> LogReset(b.readLong()) })
    .register<LogTruncated>(37, { m, b -> b.writeLong(m.oldestSeq) }, { b -> LogTruncated(b.readLong()) })
