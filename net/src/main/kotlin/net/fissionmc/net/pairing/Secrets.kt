package net.fissionmc.net.pairing

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** File helpers for key material: atomic writes, created owner-only from the first byte, permission check of existing files. */
object Secrets {
    private val ownerOnly = PosixFilePermissions.fromString("rw-------")

    /** Writes [text] to [file] through a temp file in the same directory (owner-only from creation) and an atomic move: a reader never sees half a file, a crash never loses the old one. */
    fun writeAtomic(file: Path, text: String) {
        file.toAbsolutePath().parent?.let(Files::createDirectories)
        val tmp = file.toAbsolutePath().resolveSibling(file.fileName.toString() + ".tmp-" + java.lang.Long.toHexString(java.security.SecureRandom().nextLong()))
        try {
            try { Files.createFile(tmp, PosixFilePermissions.asFileAttribute(ownerOnly)) } catch (_: UnsupportedOperationException) { Files.createFile(tmp) }
            Files.writeString(tmp, text)
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { runCatching { Files.deleteIfExists(tmp) } }
    }

    /** True when group or others can read or write [file] (false where the file system has no POSIX permissions). */
    fun looseOn(file: Path): Boolean = try {
        Files.getPosixFilePermissions(file).any { it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE) }
    } catch (_: UnsupportedOperationException) { false } catch (_: java.io.IOException) { false }

    /** Tightens an existing secret file to owner-only and says so through [warn] (a key file that a backup tool or a careless chmod made world-readable is a leaked key). */
    fun enforceOwnerOnly(file: Path, warn: (String) -> Unit = {}) {
        if (!Files.exists(file) || !looseOn(file)) return
        try { Files.setPosixFilePermissions(file, ownerOnly); warn("$file was readable by other users: permissions set to owner-only (rw-------). If this machine is shared, treat the key as exposed and rotate it.") }
        catch (e: Exception) { warn("$file is readable by other users and its permissions could not be tightened: $e") }
    }

    fun isOwnerOnly(file: Path): Boolean = !looseOn(file)
}
