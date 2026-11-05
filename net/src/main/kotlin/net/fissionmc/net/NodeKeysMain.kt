package net.fissionmc.net

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Key helper: `NodeKeysMain <keyFile> <nodeId> [authorizedFile]`.
 * Creates the Ed25519 key file if missing, prints the public key (for `node.master-public` on backends),
 * and, if [authorizedFile] is given, adds/replaces the `nodeId publicKey` line there (for the master's `node.authorized`).
 */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "usage: <keyFile> <nodeId> [authorizedFile]" }
    val pair = Keys.loadOrCreate(Path.of(args[0]).toAbsolutePath())
    val pub = Keys.encode(pair.public)
    println("node ${args[1]} public-key: $pub")
    if (args.size == 3) {
        val f = Path.of(args[2]).toAbsolutePath()
        f.parent?.let(Files::createDirectories)
        val kept = if (Files.exists(f)) Files.readAllLines(f).filter { it.isNotBlank() && it.trim().split(Regex("\\s+")).first() != args[1] } else emptyList()
        Files.write(f, kept + "${args[1]} $pub", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        println("authorized: ${args[1]} written to $f")
    }
}
