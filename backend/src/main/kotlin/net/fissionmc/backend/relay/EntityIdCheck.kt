package net.fissionmc.backend.relay

import net.fissionmc.master.seamless.EntityIdMap
import net.fissionmc.master.seamless.EntityIdTable
import net.fissionmc.master.seamless.EntityRewriter
import net.fissionmc.master.seamless.PlayPacketIds
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.logging.Logger

/**
 * The vanilla oracle of the entity id rewriter (`-PcheckEntityIds=1` together with `-PcheckStream=...`). For every clientbound play frame
 * of a recorded stream: (1) the IDENTITY rewrite must return the same bytes; (2) with a NON-identity map the rewritten frame must decode with vanilla's own codec to the
 * same packet except for exactly the entity-id fields of `entity-id-fields.txt`, which must hold the mapped id. NMS classes are used here only (backend check), never by the master.
 */
class EntityIdCheck(private val log: Logger) {
    private val idMap = EntityIdMap().also { it.start(1) }
    private val identity = EntityRewriter(idMap)
    private val mapped = EntityIdMap().also { it.start(4242) }
    private val rewriter = EntityRewriter(mapped)
    private val kinds: Map<Pair<String, String>, String> = EntityIdCheck::class.java.getResourceAsStream("/entity-id-fields.txt")!!.bufferedReader().readLines()
        .filter { it.startsWith("C ") }.associate { val p = it.split(' '); (p[1] to p[2]) to p[3] }
    private var checked = 0; private var rewrittenCount = 0; private var identityOk = 0
    private val byType = java.util.TreeMap<String, Int>()
    private val failures = ArrayList<String>()
    private var armed = false

    private fun fields(c: Class<*>): List<Field> { val out = ArrayList<Field>(); var k: Class<*>? = c; while (k != null && k != Any::class.java) { out += k.declaredFields.filter { !Modifier.isStatic(it.modifiers) }; k = k.superclass }; return out }
    private fun ints(v: Any?): List<Int>? = when (v) { is Int -> listOf(v); is IntArray -> v.toList(); is it.unimi.dsi.fastutil.ints.IntList -> v.toIntArray().toList(); is java.util.OptionalInt -> if (v.isPresent) listOf(v.asInt) else emptyList(); else -> null }

    /** [raw] = the frame (packet id + payload) as the backend wrote it, [a] = vanilla's decoded packet, [decode] = vanilla's decoder for a copy of bytes. */
    fun frame(raw: ByteArray, a: net.minecraft.network.protocol.Packet<*>, decode: (ByteArray) -> net.minecraft.network.protocol.Packet<*>) {
        val name = a.javaClass.name.substringBefore('$').substringAfterLast('.')
        if (a is net.minecraft.network.protocol.game.ClientboundLoginPacket) { idMap.start(a.playerId()); mapped.start(4242); mapped.transplantTo(a.playerId()); armed = true; return }
        if (!armed || !name.startsWith("Clientbound")) return
        checked++
        val id = net.fissionmc.master.client.VarInts.tryRead(io.netty.buffer.Unpooled.wrappedBuffer(raw))!!
        val same = identity.toClient(raw)
        if (same != null && same.contentEquals(raw)) identityOk++ else failures += "$name: the identity rewrite changed the bytes"
        if (id !in rewriter.handledClientbound) return
        val key = a.javaClass.name.substringBefore('$').substringAfterLast('.')
        val atEntityFlag = (a as? net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket)?.let { runCatching { it.javaClass.getDeclaredField("atEntity").also { f -> f.isAccessible = true }.getBoolean(it) }.getOrDefault(false) } ?: false
        // expected values FIRST: the rewriter releases the map entries of removed entities and allocates the ones of new entities, so the oracle asks the same map before it
        val expected = HashMap<String, List<Int>>()
        for (f in fields(a.javaClass)) {
            f.isAccessible = true
            val kind = kinds[key to f.name] ?: continue
            if (kind == "NOT") continue
            val ia = ints(f.get(a)) ?: continue
            expected[f.name] = ia.map { x ->
                when (kind) {
                    "ID", "IDS", "OPT_ID" -> mapped.toClient(x)
                    "SELF" -> mapped.clientSelf
                    "ID_OR_MINUS1" -> if (x < 0) x else mapped.toClient(x)
                    "ID_OR_0" -> if (x == 0) 0 else mapped.toClient(x)
                    "ID_IF_FLAG" -> if (atEntityFlag) mapped.toClient(x) else x
                    "ADD_ENTITY_OWNER" -> { val t = (a as net.minecraft.network.protocol.game.ClientboundAddEntityPacket).type; val tid = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getId(t); if (tid in EntityIdTable.projectileTypes && x != 0) mapped.toClient(x) else x }
                    else -> x
                }
            }
        }
        val out = rewriter.toClient(raw)
        if (out == null) { failures += "$name: dropped"; return }
        val b = try { decode(out) } catch (t: Throwable) { failures += "$name: vanilla cannot decode the rewritten frame: $t"; return }
        rewrittenCount++; byType.merge(name, 1, Int::plus)
        for (f in fields(a.javaClass)) {
            f.isAccessible = true
            val va = f.get(a); val vb = f.get(b)
            val exp = expected[f.name]
            if (exp != null) {
                val ib = ints(vb)
                if (exp != ib) failures += "$name.${f.name}: expected $exp after the rewrite, vanilla decoded $ib (was ${ints(va)})"
            } else if (kinds[key to f.name].let { it != null && it != "NOT" }) {
                // an id field that is not int-like in this packet (nothing to compare)
            } else if (!(java.util.Objects.deepEquals(va, vb) || va.toString() == vb.toString())) {
                // metadata of the few entity types with an id inside is rewritten by a PacketEvents codec that is not part of this oracle (EntityIdTable.metadata)
                if (!(name == "ClientboundSetEntityDataPacket" && f.name == "packedItems")) failures += "$name.${f.name}: changed although it is not an entity id field ($va -> $vb)"
            }
        }
    }

    /**
     * Packets the recorded streams may not contain: built with vanilla's constructors, encoded by vanilla's codec, then run through [frame] like recorded ones.
     * [encode] = vanilla's encoder for a play packet (frame bytes with the packet id).
     */
    fun synthetic(encode: (net.minecraft.network.protocol.Packet<*>) -> ByteArray, decode: (ByteArray) -> net.minecraft.network.protocol.Packet<*>) {
        if (!armed) return
        val pos = net.minecraft.core.BlockPos(1, 2, 3)
        val packets = listOf<net.minecraft.network.protocol.Packet<*>>(
            net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(60, 1.5f), net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(61, pos, 4),
            net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket(62, 0.5), net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket(3, 5, 63),
            net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket(idMap.backendSelf, net.minecraft.network.chat.Component.literal("dead")),
        )
        for (p in packets) try { frame(encode(p), p, decode) } catch (t: Throwable) { failures += "synthetic ${p.javaClass.simpleName}: $t" }
    }

    fun report() {
        log.info("[fission] entity id check: $checked clientbound play frames, identity rewrite exact for $identityOk, ${rewrittenCount} frames rewritten with a non-identity map and decoded again by vanilla: ${byType}; failures ${failures.size}")
        failures.take(40).forEach { log.warning("[fission] entity id check FAILED: $it") }
    }
}
