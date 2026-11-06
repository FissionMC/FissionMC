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
    fun patch(frame: ByteArray, admin: Boolean = false, log: (String) -> Unit = {}): ByteArray? =
        try { patchOrThrow(frame, admin) } catch (t: Throwable) { log("commands tree not patched: $t"); null }

    /** `minecraft:ask_server` suggestions: the client sends a ServerboundCommandSuggestion, the master answers it (see [MasterCommands.suggest]). */
    private const val ASK_SERVER = "minecraft:ask_server"
    /** Registry id of `brigadier:string` in COMMAND_ARGUMENT_TYPE (26.3, read from the vanilla jar; the vanilla Brigadier replay in the scripted test checks it). */
    private const val STRING_PARSER = 5

    private fun patchOrThrow(frame: ByteArray, admin: Boolean): ByteArray {
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
        VarInts.write(out, count + (if (admin) 15 else 4))
        // root with one more child: the new `chat` literal (index = old count)
        out.writeByte(flags); VarInts.write(out, n + (if (admin) 2 else 1)); children.forEach { VarInts.write(out, it) }; VarInts.write(out, count)
        if (admin) VarInts.write(out, count + 4)       // `fission` (admins only)
        out.writeBytes(frame, node0End, frame.size - 1 - node0End)       // the old nodes 1..count-1 verbatim, without the trailing root index
        fun literal(name: String, executable: Boolean, kids: IntArray) {
            out.writeByte(1 or (if (executable) 4 else 0)); VarInts.write(out, kids.size); kids.forEach { VarInts.write(out, it) }
            val nb = name.toByteArray(); VarInts.write(out, nb.size); out.writeBytes(nb)
        }
        literal("chat", true, intArrayOf(count + 1, count + 2, count + 3))
        literal("global", true, IntArray(0)); literal("local", true, IntArray(0)); literal("toggle", true, IntArray(0))
        if (admin) {
            // fission (+4) -> send (+5) -> <player> (+7) -> <backend> (+8) ; status (+6) ; admin (+9) -> add (+10) -> <player> (+12) , remove (+11) -> <player> (+13)
            literal("fission", true, intArrayOf(count + 5, count + 6, count + 9, count + 14))
            literal("send", false, intArrayOf(count + 7)); literal("status", true, IntArray(0))
            fun argument(name: String, executable: Boolean, kids: IntArray) {
                out.writeByte(2 or 16 or (if (executable) 4 else 0)); VarInts.write(out, kids.size); kids.forEach { VarInts.write(out, it) }
                val nb = name.toByteArray(); VarInts.write(out, nb.size); out.writeBytes(nb)
                VarInts.write(out, STRING_PARSER); VarInts.write(out, 0)                 // brigadier:string, StringType.SINGLE_WORD
                val sb = ASK_SERVER.toByteArray(); VarInts.write(out, sb.size); out.writeBytes(sb)
            }
            argument("player", false, intArrayOf(count + 8)); argument("backend", true, IntArray(0))
            literal("admin", false, intArrayOf(count + 10, count + 11)); literal("add", false, intArrayOf(count + 12)); literal("remove", false, intArrayOf(count + 13))
            argument("player", true, IntArray(0)); argument("player", true, IntArray(0))
            literal("regions", true, IntArray(0))
        }
        out.writeByte(0)                                                  // root index
        return ByteArray(out.readableBytes()).also { out.readBytes(it) }
    }
}
