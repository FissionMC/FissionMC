package net.fissionmc.master.seamless

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts

/**
 * What the client told its backend about HOW it moves, as opposed to where: the last PlayerInput (forward/back/left/right/jump/sneak/sprint keys), the sprint and
 * elytra state (PlayerCommand), the flying flag (PlayerAbilities) and the selected hotbar slot. A new backend starts from the saved player data and knows none of it, so the
 * client would keep sprinting locally while the server (and everybody else) sees a walking player, and its own metadata update (sprinting flag cleared) would stop the sprint.
 * [replay] gives the frames that bring a new backend to the same state; they are only accepted after PlayerLoaded (the 26.3 server ignores them before).
 */
class ClientInputState {
    private val pInput = PlayPacketIds.s("PlayerInput"); private val pCommand = PlayPacketIds.s("PlayerCommand")
    private val pAbilities = PlayPacketIds.s("PlayerAbilities"); private val pCarried = PlayPacketIds.s("SetCarriedItem")
    private val pPos = PlayPacketIds.s("MovePlayerPos"); private val pPosRot = PlayPacketIds.s("MovePlayerPosRot"); private val pRot = PlayPacketIds.s("MovePlayerRot"); private val pStatus = PlayPacketIds.s("MovePlayerStatusOnly")

    @Volatile var input: Int = -1; private set          // the Input bitfield byte, -1 = never sent
    @Volatile var sprinting = false; private set
    @Volatile var fallFlying = false; private set
    @Volatile var abilities: Int = -1; private set      // serverbound abilities byte (bit 1 = flying), -1 = never sent
    @Volatile var slot: Int = -1; private set

    /** Feed every serverbound play frame (id + payload, client entity ids). */
    fun observe(frame: ByteArray) {
        val b = Unpooled.wrappedBuffer(frame); val pid = VarInts.tryRead(b) ?: return
        try {
            when (pid) {
                pInput -> input = b.readByte().toInt() and 0xFF
                pCommand -> { VarInts.tryRead(b); when (VarInts.tryRead(b)) { START_SPRINTING -> sprinting = true; STOP_SPRINTING -> sprinting = false; START_FALL_FLYING -> fallFlying = true } }
                pAbilities -> abilities = b.readByte().toInt() and 0xFF
                pCarried -> slot = b.readShort().toInt()
                // the client sends no "stop gliding": a glide ends when it is on the ground again (flags byte bit 0 of every move packet), so a stale glide is never replayed
                pPos -> { b.skipBytes(24); onGround(b.readByte().toInt()) }
                pPosRot -> { b.skipBytes(32); onGround(b.readByte().toInt()) }
                pRot -> { b.skipBytes(8); onGround(b.readByte().toInt()) }
                pStatus -> onGround(b.readByte().toInt())
            }
        } catch (_: Throwable) { }
    }

    private fun onGround(flags: Int) { if (flags and 1 != 0) fallFlying = false }

    /** Serverbound frames, with the new backend's own entity id [backendSelf], that give a freshly placed player the state the client is in. */
    fun replay(backendSelf: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (slot in 0..8) out += frame(pCarried) { it.writeShort(slot) }
        if (abilities >= 0) out += frame(pAbilities) { it.writeByte(abilities) }
        if (input >= 0) out += frame(pInput) { it.writeByte(input) }
        if (sprinting) out += frame(pCommand) { VarInts.write(it, backendSelf); VarInts.write(it, START_SPRINTING); VarInts.write(it, 0) }
        if (fallFlying) out += frame(pCommand) { VarInts.write(it, backendSelf); VarInts.write(it, START_FALL_FLYING); VarInts.write(it, 0) }
        return out
    }

    private fun frame(id: Int, f: (io.netty.buffer.ByteBuf) -> Unit): ByteArray {
        val b = Unpooled.buffer(); VarInts.write(b, id); f(b); return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }

    companion object {
        /** `ServerboundPlayerCommandPacket.Action` ordinals (decompiled 26.3): STOP_SLEEPING, START_SPRINTING, STOP_SPRINTING, START_RIDING_JUMP, STOP_RIDING_JUMP, OPEN_INVENTORY, START_FALL_FLYING. */
        const val START_SPRINTING = 1; const val STOP_SPRINTING = 2; const val START_FALL_FLYING = 6
    }
}
