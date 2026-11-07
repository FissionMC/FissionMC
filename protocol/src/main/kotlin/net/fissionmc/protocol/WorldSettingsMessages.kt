package net.fissionmc.protocol

/**
 * World settings sync (master-owned clocks, weather, game rules, difficulty, border, spawn), wire ids 83-86.
 * State is a flat map of `key -> text value`: `clock:<clock id>` = `totalTicks,partialTick,rate,paused`; `gametime` = ticks; `weather` = `clearTime,rainTime,thunderTime,raining,thundering`;
 * `rule:<game rule id>` = the rule's serialized value; `difficulty` = `<name>,<locked>`; `gametype` = default game mode id; `spawn` = `<dimension>|x|y|z|yaw|pitch`;
 * `border:<dimension>` = `centerX,centerZ,target,lerpRemainingTicks,damage,safeZone,warningBlocks,warningTime`.
 */

/** Backend -> master (83): the backend's whole current state, sent when it attaches/reconnects. The master adopts the keys it does not know yet (first start seeds from the first backend) and answers [WorldSettingsState]. */
data class WorldSettingsHello(val state: Map<String, String>) : NodeMessage

/** Backend -> master (84): one setting changed on this backend (a command, a plugin, sleeping). The master applies it in arrival order and sends [WorldSettingApply] to the others. */
data class WorldSettingChange(val key: String, val value: String) : NodeMessage

/** Master -> backend (85): the canonical state, sent as the answer to a hello and every sync interval. [categories] = the enabled categories (`time,weather,gamerules,difficulty,border,spawn,gamemode`). */
data class WorldSettingsState(val masterTick: Long, val categories: String, val state: Map<String, String>) : NodeMessage

/** Master -> every backend except [origin] (86): one change, applied exactly (no drift tolerance). */
data class WorldSettingApply(val key: String, val value: String, val origin: String) : NodeMessage

fun MessageRegistry.registerWorldSettingsMessages(): MessageRegistry = this
    .register<WorldSettingsHello>(83, { m, b -> b.writeVarInt(m.state.size); m.state.forEach { (k, v) -> b.writeString(k, 256).writeString(v, 1024) } },
        { b -> val n = b.readVarInt(); require(n in 0..4096) { "too many settings" }; WorldSettingsHello(LinkedHashMap<String, String>().also { m -> repeat(n) { m[b.readString(256)] = b.readString(1024) } }) })
    .register<WorldSettingChange>(84, { m, b -> b.writeString(m.key, 256).writeString(m.value, 1024) }, { b -> WorldSettingChange(b.readString(256), b.readString(1024)) })
    .register<WorldSettingsState>(85, { m, b -> b.writeLong(m.masterTick).writeString(m.categories, 256).writeVarInt(m.state.size); m.state.forEach { (k, v) -> b.writeString(k, 256).writeString(v, 1024) } },
        { b -> val t = b.readLong(); val c = b.readString(256); val n = b.readVarInt(); require(n in 0..4096) { "too many settings" }; WorldSettingsState(t, c, LinkedHashMap<String, String>().also { m -> repeat(n) { m[b.readString(256)] = b.readString(1024) } }) })
    .register<WorldSettingApply>(86, { m, b -> b.writeString(m.key, 256).writeString(m.value, 1024).writeString(m.origin, 64) }, { b -> WorldSettingApply(b.readString(256), b.readString(1024), b.readString(64)) })
