package net.fissionmc.master

import kotlin.test.*

/**
 * The table of entity-id fields (master/src/main/resources/entity-id-fields.txt) against the raw scans of the vanilla 26.3 jar (master/src/test/resources/vanilla-scan,
 * produced by tools/EntityIdScan.java, EntityDataScan.java and AddEntityDataScan.java): every int-like field of every play packet must be classified, every classified
 * field must exist, the metadata indices must be the vanilla accessor ids. The byte-level oracle (rewrite with a non-identity map, decode again with vanilla's codecs) is the
 * backend check -PcheckEntityIds (Phase B); this test is the cheap guard that runs everywhere and fails when a vanilla update adds a field nobody classified.
 */
class EntityIdTableTest {
    private fun res(name: String) = EntityIdTableTest::class.java.getResourceAsStream(name)!!.bufferedReader().readLines()
    private val table = res("/entity-id-fields.txt").filter { it.isNotBlank() && !it.startsWith("#") }

    private data class F(val packet: String, val field: String)
    private val classified: Map<F, String> = table.mapNotNull { l -> val p = l.split(' '); when (p[0]) { "C", "S" -> F(p[1], p[2]) to p[3]; "NOT" -> F(p[1], p[2]) to "NOT"; else -> null } }.toMap()

    private val scanned: List<F> = res("/vanilla-scan/packets.txt").filter { it.startsWith("C ") || it.startsWith("S ") }.flatMap { l ->
        val cls = l.split(' ')[1]; val body = l.substringAfter('(').substringBeforeLast(')')
        body.split(Regex(", (?=\\w+:)")).filter { it.endsWith(" *") }.map { F(cls, it.substringBefore(':')) }
    }

    @Test fun `every int-like packet field of the vanilla scan is classified`() {
        assertTrue(scanned.size > 80, "scan has ${scanned.size} fields")
        val missing = scanned.filter { it !in classified }
        assertTrue(missing.isEmpty(), "unclassified: $missing")
        val extra = classified.keys.filter { it !in scanned.toSet() }
        assertTrue(extra.isEmpty(), "classified but not in the scan: $extra")
    }

    @Test fun `the table lists the entity-carrying packets the brief names`() {
        val ids = classified.filter { it.value != "NOT" }.keys.map { it.packet }.toSet()
        for (p in listOf("ClientboundAddEntityPacket", "ClientboundRemoveEntitiesPacket", "ClientboundSetEntityDataPacket", "ClientboundSetPassengersPacket", "ClientboundTakeItemEntityPacket",
            "ClientboundEntityEventPacket", "ClientboundAnimatePacket", "ClientboundDamageEventPacket", "ClientboundHurtAnimationPacket", "ClientboundUpdateAttributesPacket",
            "ClientboundSetEquipmentPacket", "ClientboundUpdateMobEffectPacket", "ClientboundRemoveMobEffectPacket", "ClientboundSetEntityMotionPacket", "ClientboundMoveEntityPacket",
            "ClientboundTeleportEntityPacket", "ClientboundRotateHeadPacket", "ClientboundPlayerLookAtPacket", "ClientboundSetCameraPacket", "ClientboundBlockDestructionPacket",
            "ClientboundProjectilePowerPacket", "ClientboundEntityPositionSyncPacket", "ClientboundMoveMinecartPacket", "ClientboundSetEntityLinkPacket", "ClientboundSoundEntityPacket",
            "ClientboundMountScreenOpenPacket", "ClientboundLoginPacket", "ClientboundPlayerCombatKillPacket", "ClientboundSwingAnimationPacket",
            "ServerboundInteractPacket", "ServerboundAttackPacket", "ServerboundPlayerCommandPacket", "ServerboundPickItemFromEntityPacket", "ServerboundEntityTagQueryPacket",
            "ServerboundSetCommandMinecartPacket", "ServerboundSpectatorActionPacket")) assertTrue(p in ids, "$p must be in the table")
        // the direction letter agrees with the class name
        table.filter { it.startsWith("C ") || it.startsWith("S ") }.forEach { l -> val p = l.split(' '); assertEquals(if (p[0] == "C") "Clientbound" else "Serverbound", p[1].take(11), l) }
    }

    @Test fun `metadata entries match the vanilla accessor ids and entity types`() {
        val ents = res("/vanilla-scan/entities.txt")
        val data = ents.filter { it.startsWith("DATA ") }.associate { val p = it.split(' '); p[1] to (p[2] to p[3].toInt()) }
        val types = ents.filter { it.startsWith("ENTITY_TYPE ") }.associate { val p = it.split(' '); p[1].toInt() to p[3] }
        val meta = table.filter { it.startsWith("META ") }
        assertEquals(7, meta.size)
        for (l in meta) {
            val p = l.split(' '); val key = p[1]; val (ser, idx) = data[key] ?: fail("$key is not an INT/OPTIONAL_UNSIGNED_INT accessor in the scan")
            assertEquals(ser, p[3], l); assertEquals(idx, p[2].toInt(), l)
            p[5].split(',').forEach { t -> val cls = types[t.toInt()] ?: fail("unknown entity type $t"); assertTrue(cls == key.substringBeforeLast('.') || cls.endsWith("Guardian"), "$l: type $t is $cls") }
        }
    }

    @Test fun `projectile types are real entity types and AddEntity data is only an id for them`() {
        val types = res("/vanilla-scan/entities.txt").filter { it.startsWith("ENTITY_TYPE ") }.associate { val p = it.split(' '); p[1].toInt() to p[2] }
        val listed = table.single { it.startsWith("PROJECTILE_TYPES ") }.substringAfter(' ').split(',').map { it.toInt() }
        assertEquals(19, listed.size); listed.forEach { assertTrue(it in types, "entity type $it") }
        assertTrue(listOf("arrow", "trident", "fishing_bobber", "snowball", "wither_skull").all { n -> types.entries.first { it.value == n }.key in listed })
        // the other AddEntity users with an explicit data value are direction/state ids, not entity ids
        val users = res("/vanilla-scan/addentity.txt").map { it.substringBefore('.') }.toSet()
        assertTrue(users.containsAll(listOf("decoration/ItemFrame", "decoration/painting/Painting", "item/FallingBlockEntity", "projectile/Projectile", "projectile/FishingHook")), users.toString())
    }
}
