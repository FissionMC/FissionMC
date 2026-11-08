package net.fissionmc.loader

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Main class of the distributed `backend.jar` (`java -jar backend.jar nogui`, e.g. a Pterodactyl egg whose start line is `java ... -jar ${SERVER_JARFILE} nogui`).
 *
 * Mojang's server is not part of FissionMC (not redistributable), so on the first start this takes it from Mojang like Paper's launcher does:
 * `cache/mojang_<version>.jar` is downloaded (SHA-1 verified), its bundle is extracted to `.fission/vanilla`, the access/member declarations
 * bundled in this jar go to `.fission/surface`, the game class path and the loader properties are set, and [Bootstrap] takes over.
 * Overrides: `FISSION_VANILLA_DIR` (an already extracted bundle: META-INF/versions, META-INF/libraries) and any `-Dfission.*` already set.
 */
object Launcher {
    private fun log(s: String) = println("[fission-launcher] $s")

    private fun banner(version: String, mc: String) {
        listOf(
            """ _____ _          _             __  __  ____""",
            """|  ___(_)___ ___ (_) ___  _ __ |  \/  |/ ___|""",
            """| |_  | / __/ __|| |/ _ \| '_ \| |\/| | |""",
            """|  _| | \__ \__ \| | (_) | | | | |  | | |___""",
            """|_|   |_|___/___/|_|\___/|_| |_|_|  |_|\____|""",
        ).forEach(::println)
        println("  backend | FissionMC $version | Minecraft $mc | Java ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}) | pid ${ProcessHandle.current().pid()}")
        println()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dist = java.util.Properties().also { p ->
            Launcher::class.java.getResourceAsStream("/fission-dist.properties")?.use { p.load(it) } ?: error("not a FissionMC distribution jar: fission-dist.properties is missing (run via the Gradle run task or build with ./gradlew dist)")
        }
        val mcVersion = dist.getProperty("mcVersion") ?: error("fission-dist.properties has no mcVersion")
        banner(dist.getProperty("version") ?: "dev", mcVersion)
        val force = System.getenv("FISSION_SHUTDOWN_FORCE_SECONDS")?.toLongOrNull() ?: 120L
        Runtime.getRuntime().addShutdownHook(Thread({
            try { Thread.sleep(force * 1000) } catch (_: InterruptedException) { return@Thread }
            System.err.println("[fission-launcher] shutdown has taken more than ${force}s: thread dump, then forcing the exit (FISSION_SHUTDOWN_FORCE_SECONDS changes this)")
            Thread.getAllStackTraces().forEach { (t, st) -> System.err.println("\"${t.name}\" daemon=${t.isDaemon} state=${t.state}"); st.forEach { System.err.println("\tat $it") } }
            System.err.flush()
            Runtime.getRuntime().halt(1)
        }, "shutdown-watchdog").also { it.isDaemon = true })
        val work = File(".fission").also { it.mkdirs() }
        val vanilla = System.getenv("FISSION_VANILLA_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(work, "vanilla")
        if (!File(vanilla, "META-INF/versions").isDirectory) {
            val bundle = File("cache/mojang_$mcVersion.jar")
            if (!bundle.isFile) download(mcVersion, bundle)
            log("extracting ${bundle.path} to ${vanilla.path}")
            extractBundle(bundle, vanilla)
        }
        val serverJar = File(vanilla, "META-INF/versions").walkTopDown().firstOrNull { it.isFile && it.name.startsWith("server-") && it.name.endsWith(".jar") }
            ?: error("no server-*.jar under ${vanilla.path}/META-INF/versions (is it Mojang's official server.jar for $mcVersion?)")
        val libs = File(vanilla, "META-INF/libraries").walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }.map { it.path }.sorted().toList()

        val game = extractGame(File(work, "game.jar"), dist.getProperty("gameSha1"))
        val surface = File(work, "surface")
        extractSurface(surface)

        fun prop(k: String, v: String) { if (System.getProperty(k) == null) System.setProperty(k, v) }
        prop("fission.classpath", (listOf(serverJar.path) + libs + game.path).joinToString(File.pathSeparator))
        prop("fission.mixins", "fission.mixins.json")
        prop("fission.surface", surface.path)
        prop("fission.main", "org.bukkit.craftbukkit.Main")
        Bootstrap.main(args)
    }

    /** The nested game jar (`/game.jar`) goes to [target] unless the copy from the last start has the same SHA-1 (recorded next to it). */
    private fun extractGame(target: File, sha1: String?): File {
        val marker = File(target.path + ".sha1")
        if (target.isFile && sha1 != null && marker.isFile && marker.readText().trim() == sha1) return target
        log("extracting the game classes to ${target.path}")
        val tmp = File(target.path + ".part")
        (Launcher::class.java.getResourceAsStream("/game.jar") ?: error("this jar has no embedded game.jar (not built with ./gradlew dist)")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        target.delete(); require(tmp.renameTo(target)) { "cannot move $tmp to $target" }
        if (sha1 != null) marker.writeText(sha1)
        return target
    }

    /** Resources listed in `/surface/INDEX` (one relative path per line) are written to [dir]; stale files are removed first. */
    private fun extractSurface(dir: File) {
        val index = Launcher::class.java.getResourceAsStream("/surface/INDEX")?.use { it.readBytes().toString(Charsets.UTF_8).lines().map(String::trim).filter(String::isNotEmpty) }
            ?: return log("no bundled surface/INDEX: using -Dfission.surface as given")
        dir.deleteRecursively(); dir.mkdirs()
        for (rel in index) {
            val out = File(dir, rel).canonicalFile
            require(out.path.startsWith(dir.canonicalPath + File.separator)) { "bad surface path $rel" }
            out.parentFile.mkdirs()
            out.writeBytes(Launcher::class.java.getResourceAsStream("/surface/$rel")!!.use { it.readBytes() })
        }
    }

    private fun extractBundle(bundle: File, out: File) {
        out.deleteRecursively(); out.mkdirs()
        ZipFile(bundle).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !(e.name.startsWith("META-INF/versions/") || e.name.startsWith("META-INF/libraries/"))) continue
                val f = File(out, e.name).canonicalFile
                require(f.path.startsWith(out.canonicalPath + File.separator)) { "bad entry ${e.name}" }
                f.parentFile.mkdirs()
                z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }

    /** Mojang's launcher metadata: version manifest -> version JSON -> downloads.server {sha1, url}. Fields are read with regexes (no JSON library on this early class path). */
    private fun download(mcVersion: String, target: File) {
        log("Mojang's server.jar for $mcVersion is not in ${target.path}: downloading it from Mojang (needs internet; to avoid this put the official server.jar there)")
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        fun text(url: String): String {
            val r = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "FissionMC-launcher").build(), HttpResponse.BodyHandlers.ofString())
            require(r.statusCode() == 200) { "GET $url -> HTTP ${r.statusCode()}" }
            return r.body()
        }
        val versionUrl = Regex("""\{\s*"id"\s*:\s*"${Regex.escape(mcVersion)}"[^{}]*?"url"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(text("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))?.groupValues?.get(1) ?: error("Minecraft $mcVersion is not in Mojang's version manifest")
        val server = Regex(""""server"\s*:\s*\{([^{}]*)\}""").find(text(versionUrl))?.groupValues?.get(1) ?: error("no server download in $versionUrl")
        val sha1 = Regex(""""sha1"\s*:\s*"([0-9a-fA-F]{40})"""").find(server)?.groupValues?.get(1) ?: error("no sha1 for the server download")
        val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(server)?.groupValues?.get(1) ?: error("no url for the server download")
        target.parentFile.mkdirs()
        val tmp = File(target.path + ".part")
        val r = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "FissionMC-launcher").build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        require(r.statusCode() == 200) { "GET $url -> HTTP ${r.statusCode()}" }
        val md = MessageDigest.getInstance("SHA-1")
        tmp.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(sha1, ignoreCase = true)) { tmp.delete(); error("SHA-1 mismatch for the downloaded server.jar (got $got, Mojang says $sha1)") }
        require(tmp.renameTo(target)) { "cannot move $tmp to $target" }
        log("downloaded and verified ${target.path} (${target.length() / 1024} KiB)")
    }
}
