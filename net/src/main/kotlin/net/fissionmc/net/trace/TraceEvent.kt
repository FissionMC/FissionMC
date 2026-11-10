package net.fissionmc.net.trace

import java.util.UUID
import java.util.zip.CRC32

/**
 * One event under construction: the payload fields as JSON (each starts with a comma; [Trace.commit] adds the common prefix: wall time, monotonic nanos, node, seq, type).
 * Only ever created when tracing is on, see [Trace.emit].
 */
class TraceEvent internal constructor(internal val type: String) {
    internal val sb = StringBuilder(192)

    private fun key(k: String) { sb.append(',').append('"').append(k).append("\":") }

    fun str(k: String, v: String?): TraceEvent { key(k); if (v == null) sb.append("null") else quote(sb, v); return this }
    fun int(k: String, v: Int): TraceEvent { key(k); sb.append(v); return this }
    fun long(k: String, v: Long): TraceEvent { key(k); sb.append(v); return this }
    fun bool(k: String, v: Boolean): TraceEvent { key(k); sb.append(v); return this }
    fun double(k: String, v: Double): TraceEvent { key(k); if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v); return this }
    fun ints(k: String, v: IntArray, limit: Int = 32): TraceEvent {
        key(k); sb.append('[')
        for (i in 0 until minOf(v.size, limit)) { if (i > 0) sb.append(','); sb.append(v[i]) }
        sb.append(']'); if (v.size > limit) { key(k + "More"); sb.append(v.size - limit) }
        return this
    }
    fun strs(k: String, v: Collection<String>, limit: Int = 32): TraceEvent {
        key(k); sb.append('['); var i = 0
        for (s in v) { if (i >= limit) break; if (i++ > 0) sb.append(','); quote(sb, s) }
        sb.append(']'); if (v.size > limit) { key(k + "More"); sb.append(v.size - limit) }
        return this
    }

    /** The player this event is about (name and uuid; either may be unknown). */
    fun player(name: String?, uuid: UUID?): TraceEvent { if (name != null) str("player", name); if (uuid != null) str("uuid", uuid.toString()); return this }
    fun player(name: String?, uuidHigh: Long, uuidLow: Long): TraceEvent = player(name, UUID(uuidHigh, uuidLow))

    /** Bulk data summarised as its size and a CRC32 (hex): never the bytes themselves. */
    fun blob(k: String, bytes: ByteArray?): TraceEvent {
        if (bytes == null) return str(k, null)
        key(k); sb.append("{\"size\":").append(bytes.size).append(",\"crc\":\"").append(Integer.toHexString(crc(bytes))).append("\"}")
        return this
    }

    companion object {
        /** The packet id: the first varint of a frame (-1 when malformed). */
        fun packetId(frame: ByteArray): Int {
            var id = 0; var shift = 0; var i = 0
            while (i < frame.size && shift < 35) { val b = frame[i++].toInt(); id = id or ((b and 0x7f) shl shift); shift += 7; if (b and 0x80 == 0) return id }
            return -1
        }

        fun crc(b: ByteArray): Int = CRC32().also { it.update(b) }.value.toInt()

        fun quote(sb: StringBuilder, s: String) {
            sb.append('"')
            for (ch in s) when {
                ch == '"' -> sb.append("\\\""); ch == '\\' -> sb.append("\\\\"); ch == '\n' -> sb.append("\\n"); ch == '\r' -> sb.append("\\r"); ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
            sb.append('"')
        }
    }
}
