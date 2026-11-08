package net.fissionmc.protocol

/**
 * Plugin replication (master -> backend jars at boot), wire ids 88-92. Ids are wire format: never reuse one.
 * Same transfer style as the world pull: manifest with SHA-256, chunked file stream, verification on the receiver.
 */

/** One replicable jar: [file] is a plain file name (no path separators), [pluginName] the `name:` of its plugin.yml / paper-plugin.yml ("" if unreadable). */
class PluginEntry(val file: String, val size: Long, val sha256: ByteArray, val pluginName: String)

/** Backend -> master (88). */
class PluginManifestRequest : NodeMessage

/** Master -> backend (89). [enabled] = false when the master has `plugins.replicate: false` (the backend then leaves its managed jars alone). */
class PluginManifest(val enabled: Boolean, val files: List<PluginEntry>) : NodeMessage

/** Backend -> master (90). */
class PluginFileRequest(val file: String) : NodeMessage

/** Master -> backend (91). */
class PluginFileChunk(val file: String, val offset: Long, val data: ByteArray, val last: Boolean) : NodeMessage

/** Master -> backend (92): the request could not be served. */
class PluginSyncError(val message: String) : NodeMessage

/** Limits both sides enforce. */
const val PLUGIN_MAX_JAR_BYTES = 256L * 1024 * 1024
const val PLUGIN_MAX_JARS = 512

fun MessageRegistry.registerPluginMessages(): MessageRegistry = this
    .register<PluginManifestRequest>(88, { _, _ -> }, { PluginManifestRequest() })
    .register<PluginManifest>(89,
        { m, b ->
            b.writeBoolean(m.enabled).writeVarInt(m.files.size)
            for (f in m.files) b.writeString(f.file, 255).writeLong(f.size).writeByteArray(f.sha256, 32).writeString(f.pluginName, 128)
        },
        { b ->
            val enabled = b.readBoolean()
            val n = b.readVarInt()
            require(n in 0..PLUGIN_MAX_JARS) { "Bad plugin count $n" }
            PluginManifest(enabled, List(n) { PluginEntry(b.readString(255), b.readLong(), b.readByteArray(32), b.readString(128)) })
        })
    .register<PluginFileRequest>(90, { m, b -> b.writeString(m.file, 255) }, { b -> PluginFileRequest(b.readString(255)) })
    .register<PluginFileChunk>(91,
        { m, b -> b.writeString(m.file, 255).writeLong(m.offset).writeByteArray(m.data, FILE_CHUNK_SIZE).writeBoolean(m.last) },
        { b -> PluginFileChunk(b.readString(255), b.readLong(), b.readByteArray(FILE_CHUNK_SIZE), b.readBoolean()) })
    .register<PluginSyncError>(92, { m, b -> b.writeString(m.message, 512) }, { b -> PluginSyncError(b.readString(512)) })
