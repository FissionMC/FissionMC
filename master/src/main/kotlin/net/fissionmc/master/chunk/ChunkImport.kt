package net.fissionmc.master.chunk

import net.fissionmc.protocol.ChunkKind
import net.fissionmc.protocol.ChunkStatusRank
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Seeds the chunk store from a world directory (`gradlew :master:importWorld -Pworld=<dir>`): every slot of every `.mca` under a `region`,
 * `entities` or `poi` folder. Higher status wins, equal status keeps the first import. Layouts: 26.x `dimensions/<namespace>/<path>/{region,entities,poi}`,
 * legacy `region` (overworld), `DIM-1/...` (nether), `DIM1/...` (end). Slots stored with LZ4 or in external `.mcc` files are skipped with a warning.
 */
object ChunkImport {
    class Result(var imported: Long = 0, var skipped: Long = 0, var unsupported: Long = 0)

    fun dimensionOf(world: Path, regionFolder: Path): String? {
        val rel = world.relativize(regionFolder.parent).joinToString("/")   // e.g. dimensions/minecraft/overworld, DIM-1, ""
        return when {
            rel.isEmpty() -> "minecraft:overworld"
            rel == "DIM-1" -> "minecraft:the_nether"
            rel == "DIM1" -> "minecraft:the_end"
            rel.startsWith("dimensions/") -> rel.removePrefix("dimensions/").split('/', limit = 2).takeIf { it.size == 2 }?.let { "${it[0]}:${it[1]}" }
            else -> null
        }
    }

    fun import(world: Path, store: ChunkStore, log: (String) -> Unit = {}): Result {
        val r = Result()
        Files.walk(world).use { s ->
            s.filter { Files.isDirectory(it) && it.fileName.toString() in setOf("region", "entities", "poi") }.forEach { folder ->
                val dim = dimensionOf(world, folder) ?: return@forEach
                val kind = when (folder.fileName.toString()) { "region" -> ChunkKind.CHUNK; "entities" -> ChunkKind.ENTITIES; else -> ChunkKind.POI }
                Files.list(folder).use { fs -> fs.filter { it.fileName.toString().endsWith(".mca") }.sorted().forEach { importRegion(it, dim, kind, store, r, log) } }
            }
        }
        return r
    }

    private fun importRegion(file: Path, dim: String, kind: Int, store: ChunkStore, r: Result, log: (String) -> Unit) {
        val parts = file.fileName.toString().split('.')
        if (parts.size != 4) return
        val rx = parts[1].toIntOrNull() ?: return; val rz = parts[2].toIntOrNull() ?: return
        RandomAccessFile(file.toFile(), "r").use { raf ->
            if (raf.length() < 8192) return
            val header = ByteArray(4096).also { raf.readFully(it) }
            for (i in 0 until 1024) {
                val off = ((header[i * 4].toInt() and 0xff) shl 16) or ((header[i * 4 + 1].toInt() and 0xff) shl 8) or (header[i * 4 + 2].toInt() and 0xff)
                if (off == 0) continue
                raf.seek(off * 4096L)
                val len = raf.readInt(); val type = raf.readUnsignedByte()
                if (len <= 1 || len > 8 * 1024 * 1024) { r.skipped++; continue }
                val data = ByteArray(len - 1); raf.readFully(data)
                val raw: ByteArray = when (type) {
                    1 -> GZIPInputStream(data.inputStream()).readAllBytes()
                    2 -> InflaterInputStream(data.inputStream(), Inflater()).readAllBytes()
                    3 -> data
                    else -> { r.unsupported++; if (r.unsupported <= 3) log("[import] ${file.fileName}: slot $i uses compression $type (lz4 / external), skipped"); continue }
                }
                val cx = rx * 32 + (i % 32); val cz = rz * 32 + (i / 32)
                val rank = if (kind == ChunkKind.CHUNK) ChunkStatusRank.of(NbtScan.statusOf(raw)) else 0
                val res = store.putIfBetter(ChunkKey(dim, kind, cx, cz), rank, deflate(raw))
                if (res.accepted) r.imported++ else r.skipped++
            }
        }
    }

    fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION); d.setInput(raw); d.finish()
        val o = ByteArrayOutputStream(); val buf = ByteArray(65536)
        while (!d.finished()) o.write(buf, 0, d.deflate(buf))
        d.end(); return o.toByteArray()
    }
}

/** `importWorld`: args = <chunkstore dir> <world dir>... */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <chunkstore dir> <world dir>..." }
    val store = FileChunkStore(Path.of(args[0])) { println(it) }
    for (w in args.drop(1)) {
        val r = ChunkImport.import(Path.of(w), store) { println(it) }
        println("[import] $w: ${r.imported} imported, ${r.skipped} not better, ${r.unsupported} unsupported")
    }
}
