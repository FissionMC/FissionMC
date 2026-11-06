package net.fissionmc.master.chunk

import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException

/** Reads just the top-level `Status` string of a chunk NBT (uncompressed `NbtIo.write` format) without building a tag tree: the master has no NBT library. */
object NbtScan {
    fun statusOf(raw: ByteArray): String? = try {
        val i = DataInputStream(raw.inputStream())
        if (i.readByte().toInt() != 10) null else {
            i.readUTF()
            var found: String? = null
            while (found == null) {
                val type = i.readByte().toInt(); if (type == 0) break
                val name = i.readUTF()
                if (type == 8 && name == "Status") found = i.readUTF() else skip(i, type)
            }
            found
        }
    } catch (_: EOFException) { null } catch (_: IllegalStateException) { null }

    private fun skip(i: DataInput, type: Int) {
        when (type) {
            1 -> i.skipBytes(1); 2 -> i.skipBytes(2); 3 -> i.skipBytes(4); 4 -> i.skipBytes(8); 5 -> i.skipBytes(4); 6 -> i.skipBytes(8)
            7 -> i.skipBytes(i.readInt()); 8 -> i.skipBytes(i.readUnsignedShort())
            9 -> { val t = i.readByte().toInt(); repeat(i.readInt()) { skip(i, t) } }
            10 -> { while (true) { val t = i.readByte().toInt(); if (t == 0) break; i.skipBytes(i.readUnsignedShort()); skip(i, t) } }
            11 -> i.skipBytes(i.readInt() * 4); 12 -> i.skipBytes(i.readInt() * 8)
            else -> throw IllegalStateException("bad tag type $type")
        }
    }
}
