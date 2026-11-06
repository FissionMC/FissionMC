package net.fissionmc.master.client

import io.netty.buffer.Unpooled

/**
 * Adds the master's `/chat global|local|toggle` to the ClientboundCommands tree a backend sent (so the client tab-completes it).
 * Raw patch, no argument parser needed: the root is node 0 (Brigadier numbers the root first; `ClientboundCommandsPacket.createEntries`),
 * the new nodes are appended after the last node, and the trailing root index byte (0) is kept.
 * Node layout (ClientboundCommandsPacket.Entry.write): flags byte (type 1 = literal, +4 executable), varint child array, [redirect], name.
 */
object ChatCommandTree {
    /** The patched frame, or null when the frame is not in the expected shape (it is then relayed unchanged). */
    fun patch(frame: ByteArray, log: (String) -> Unit = {}): ByteArray? = try { patchOrThrow(frame) } catch (t: Throwable) { log("commands tree not patched: $t"); null }

    private fun patchOrThrow(frame: ByteArray): ByteArray {
        require(frame.isNotEmpty() && frame.last() == 0.toByte()) { "root index is not 0" }
        val b = Unpooled.wrappedBuffer(frame)
        VarInts.tryRead(b)!!                                  // packet id
        val count = VarInts.tryRead(b)!!
        val node0Start = b.readerIndex()
        val flags = b.readByte().toInt()
        require(flags and 3 == 0) { "node 0 is not the root" }
        require(flags and 8 == 0) { "root redirects" }
        val n = VarInts.tryRead(b)!!
        val children = IntArray(n) { VarInts.tryRead(b)!! }
        val node0End = b.readerIndex()
        require(count in 1..100_000)
        val out = Unpooled.buffer(frame.size + 64)
        out.writeBytes(frame, 0, node0Start - VarInts.size(count))      // packet id
        VarInts.write(out, count + 4)
        // root with one more child: the new `chat` literal (index = old count)
        out.writeByte(flags); VarInts.write(out, n + 1); children.forEach { VarInts.write(out, it) }; VarInts.write(out, count)
        out.writeBytes(frame, node0End, frame.size - 1 - node0End)       // the old nodes 1..count-1 verbatim, without the trailing root index
        fun literal(name: String, executable: Boolean, kids: IntArray) {
            out.writeByte(1 or (if (executable) 4 else 0)); VarInts.write(out, kids.size); kids.forEach { VarInts.write(out, it) }
            val nb = name.toByteArray(); VarInts.write(out, nb.size); out.writeBytes(nb)
        }
        literal("chat", true, intArrayOf(count + 1, count + 2, count + 3))
        literal("global", true, IntArray(0)); literal("local", true, IntArray(0)); literal("toggle", true, IntArray(0))
        out.writeByte(0)                                                  // root index
        return ByteArray(out.readableBytes()).also { out.readBytes(it) }
    }
}
