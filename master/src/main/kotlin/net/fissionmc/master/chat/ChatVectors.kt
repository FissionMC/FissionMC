package net.fissionmc.master.chat

import net.kyori.adventure.text.Component

/**
 * The shared test vectors of [ChatTemplate] (`chat-vectors.txt`, tab separated: `name syntax allow-colors display template message expected`). Fixed variables: player name
 * `Alex`, uuid `35deb110-752a-3928-95c4-cc89250ce08e`, server id `backend-a`. Run by `ChatTemplateTest` (master, adventure 4.26.1) and by `gradlew :backend:chatVectors`
 * (the backend's class path: paper-api's adventure 5.x), so both lines of adventure must produce the same description. `main` prints the failures and exits 1; with
 * `--print` it prints the vectors with their current output (to write expected values).
 */
object ChatVectors {
    val uuid: java.util.UUID = java.util.UUID.fromString("35deb110-752a-3928-95c4-cc89250ce08e")

    data class Row(val name: String, val syntax: String, val allow: Boolean, val display: String, val template: String, val message: String, val expected: String)

    fun rows(): List<Row> = ChatVectors::class.java.getResourceAsStream("/chat-vectors.txt")!!.bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { l -> val c = l.split('\t'); Row(c[0], c[1], c[2] == "true", c[3], c[4], c[5], c.getOrElse(6) { "" }) }

    fun actual(r: Row): String = ChatTemplate.describe(ChatTemplate.render(r.template, r.syntax, "Alex", Component.text(r.display), uuid, "backend-a", ChatTemplate.messageComponent(r.message, r.allow)))

    fun failures(): List<String> = rows().mapNotNull { r -> actual(r).let { a -> if (a == r.expected) null else "${r.name}: expected ${r.expected} but got $a" } }

    @JvmStatic fun main(args: Array<String>) {
        if (args.contains("--print")) { rows().forEach { println(listOf(it.name, it.syntax, it.allow, it.display, it.template, it.message, actual(it)).joinToString("\t")) }; return }
        val f = failures(); val n = rows().size
        f.forEach { println("[chat-vectors] FAIL $it") }
        println("[chat-vectors] ${n - f.size}/$n vectors pass on ${Component::class.java.protectionDomain?.codeSource?.location}")
        if (f.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
