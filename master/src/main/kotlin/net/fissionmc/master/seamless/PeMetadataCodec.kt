package net.fissionmc.master.seamless

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import net.fissionmc.master.client.Limbo
import java.util.Optional

/** [MetadataCodec] on PacketEvents' SetEntityData wrapper. Fail open: any problem returns null and the packet goes out with the backend's ids inside (cosmetic: a beam or tongue target). */
class PeMetadataCodec(private val decode: (ByteArray) -> PacketSendEvent?) : MetadataCodec {
    @Suppress("UNCHECKED_CAST")
    override fun rewrite(frame: ByteArray, entityClientId: Int, entries: List<EntityIdTable.MetaEntry>, mapId: (Int) -> Int): ByteArray? = try {
        val ev = decode(frame) ?: return null
        val w = WrapperPlayServerEntityMetadata(ev)
        var changed = false
        for (e in w.entityMetadata) {
            val spec = entries.firstOrNull { it.index == e.index } ?: continue
            val v = e.value
            when (spec.kind) {
                "ID_OR_0" -> if (v is Int && v != 0) { (e as com.github.retrooper.packetevents.protocol.entity.data.EntityData<Any>).value = mapId(v); changed = true }
                "ID_PLUS_1" -> if (v is Int && v != 0) { (e as com.github.retrooper.packetevents.protocol.entity.data.EntityData<Any>).value = mapId(v - 1) + 1; changed = true }
                "OPT_ID" -> if (v is Optional<*> && v.isPresent) { (e as com.github.retrooper.packetevents.protocol.entity.data.EntityData<Any>).value = Optional.of(mapId(v.get() as Int)); changed = true }
            }
        }
        if (!changed) frame else { w.entityId = entityClientId; Limbo.encode(w) }
    } catch (t: Throwable) { null }
}
