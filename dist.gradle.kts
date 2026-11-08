// ./gradlew dist : build/dist/ with runnable master.jar and backend.jar plus start scripts (applied from the root build.gradle.kts)
//
// master.jar   : everything the master needs: java -jar master.jar nogui   (config fission.yml, console "stop")
// backend.jar  : loader + Paper port + our code + libraries + the surface files, WITHOUT Mojang's server (not redistributable): the first start takes
//                Mojang's official server.jar for the Minecraft version from Mojang into cache/ (net.fissionmc.loader.Launcher): java -jar backend.jar nogui
// Both start like a Minecraft server jar, so a hosting panel egg (java ... -jar ${SERVER_JARFILE} nogui) can run them.

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

evaluationDependsOn(":packetevents")
evaluationDependsOn(":loader")
evaluationDependsOn(":master")
evaluationDependsOn(":backend")

fun mainRuntime(path: String) = project(path).extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath

/** One jar from many: first entry wins, signatures dropped, META-INF/services files concatenated (ServiceLoader providers must all survive). */
fun fatJar(out: File, inputs: List<File>, mainClass: String, extra: Map<String, ByteArray> = emptyMap()) {
    out.parentFile.mkdirs()
    val seen = HashSet<String>()
    val services = LinkedHashMap<String, LinkedHashSet<String>>()
    fun skip(n: String) = n.endsWith("/") || n == "META-INF/MANIFEST.MF" || n == "META-INF/INDEX.LIST" || n == "module-info.class" ||
        (n.startsWith("META-INF/") && n.count { it == '/' } == 1 && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")))
    // Mixin detects the ASM version from Package.getImplementationVersion() of org.objectweb.asm: a merged jar has no such manifest data, Mixin then
    // assumes ASM 9.0 and refuses compatibility level JAVA_25. Keep the version of the ASM jar that wins as a per-package manifest section.
    val asmVersion = inputs.filter { it.isFile && it.name.endsWith(".jar") }.firstNotNullOfOrNull { f ->
        ZipFile(f).use { z ->
            if (z.getEntry("org/objectweb/asm/Opcodes.class") == null) null
            else z.getEntry("META-INF/MANIFEST.MF")?.let { m -> java.util.jar.Manifest(z.getInputStream(m)).mainAttributes.getValue("Implementation-Version") }
        }
    }
    ZipOutputStream(out.outputStream().buffered()).use { zos ->
        zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
        zos.write(("Manifest-Version: 1.0\nMain-Class: $mainClass\nImplementation-Title: FissionMC\n\n" +
            (asmVersion?.let { "Name: org/objectweb/asm/\nImplementation-Version: $it\n\n" } ?: "")).toByteArray()); zos.closeEntry()
        fun add(name: String, bytes: ByteArray) {
            if (name.startsWith("META-INF/services/") && name.length > "META-INF/services/".length) {
                services.getOrPut(name) { LinkedHashSet() } += String(bytes, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                return
            }
            if (skip(name) || !seen.add(name)) return
            zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
        }
        for (f in inputs) {
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.forEach { add(it.relativeTo(f).path.replace(File.separatorChar, '/'), it.readBytes()) }
            else if (f.isFile && f.name.endsWith(".jar")) ZipFile(f).use { z -> z.entries().asSequence().filter { !it.isDirectory }.forEach { add(it.name, z.getInputStream(it).readBytes()) } }
        }
        for ((name, bytes) in extra) add(name, bytes)
        for ((name, lines) in services) { zos.putNextEntry(ZipEntry(name)); zos.write((lines.joinToString("\n") + "\n").toByteArray()); zos.closeEntry() }
    }
}

val distRoot = layout.buildDirectory.dir("dist")

tasks.register("dist") {
    group = "fission"
    description = "Builds build/dist: master.jar, backend.jar, surface/ and the start scripts"
    val patch = project(":packetevents").tasks.named("patchJar")
    val masterCp = files(patch) + mainRuntime(":master")
    val loaderCp = mainRuntime(":loader")
    val gameCp = mainRuntime(":backend")
    val backendCp = loaderCp + gameCp
    dependsOn(masterCp, backendCp)
    inputs.files(masterCp, backendCp)
    inputs.dir(file("surface"))
    outputs.dir(distRoot)
    val mcVersion = project.property("mcVersion") as String
    val distVersion = project(":master").version.toString()
    val surfaceDir = file("surface")
    doLast {
        val root = distRoot.get().asFile
        root.deleteRecursively(); root.mkdirs()
        fatJar(File(root, "master.jar"), masterCp.files.toList(), "net.fissionmc.master.MasterMainKt", mapOf("fission-dist.properties" to "version=$distVersion\nmcVersion=$mcVersion\n".toByteArray()))
        val surfaceFiles = surfaceDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(surfaceDir).path.replace(File.separatorChar, '/') }.sorted().toList()
        // Two layers like the dev setup: the outer backend.jar holds ONLY the loader (Mixin, ASM, Launcher) so the app class loader never sees game classes;
        // the game classes (Paper port, our code, libraries) are a nested jar the Launcher extracts to .fission/game.jar and gives to FissionClassLoader.
        // One merged jar made both loaders define the same libraries and failed with "loader constraint violation".
        val gameJar = File(root, ".game.jar")
        fatJar(gameJar, gameCp.files.toList(), "net.fissionmc.loader.Bootstrap")
        val gameBytes = gameJar.readBytes(); gameJar.delete()
        val gameSha1 = java.security.MessageDigest.getInstance("SHA-1").digest(gameBytes).joinToString("") { "%02x".format(it) }
        val extra = LinkedHashMap<String, ByteArray>()
        extra["game.jar"] = gameBytes
        extra["fission-dist.properties"] = "version=$distVersion\nmcVersion=$mcVersion\ngameSha1=$gameSha1\n".toByteArray()
        extra["surface/INDEX"] = (surfaceFiles.joinToString("\n") + "\n").toByteArray()
        surfaceFiles.forEach { extra["surface/$it"] = File(surfaceDir, it).readBytes() }
        fatJar(File(root, "backend.jar"), loaderCp.files.toList(), "net.fissionmc.loader.Launcher", extra)

        fun script(name: String, text: String) = File(root, name).also { it.writeText(text.trimIndent() + "\n"); it.setExecutable(true) }
        script("start-master.sh", """
            #!/usr/bin/env bash
            # Starts the FissionMC master. Config: ./fission.yml (written with comments on the first start if missing). Console: type stop.
            cd "${'$'}(dirname "${'$'}0")" || exit 1
            exec java ${'$'}{JAVA_OPTS:--Xmx1G} -jar master.jar nogui
        """)
        script("start-backend.sh", """
            #!/usr/bin/env bash
            # Starts a FissionMC backend (a Paper-compatible server, Java 25). The first start downloads Mojang's official server for $mcVersion into cache/
            # (needs internet; or put Mojang's server.jar there as cache/mojang_$mcVersion.jar, or set FISSION_VANILLA_DIR to an extracted copy).
            # Config: ./fission.yml (written with comments on the first start; node: mode standalone|backend|combined). Accept the Minecraft EULA in eula.txt.
            cd "${'$'}(dirname "${'$'}0")" || exit 1
            exec java ${'$'}{JAVA_OPTS:--Xmx4G} -jar backend.jar nogui "${'$'}@"
        """)
        File(root, "README.txt").writeText(
            """
            FissionMC test distribution (Minecraft $mcVersion, Java 25)

            master.jar   the master (players connect here).   java -jar master.jar nogui     config: fission.yml (commented default written on first start), console: stop, list, help
            backend.jar  a backend server.                    java -jar backend.jar nogui    config: fission.yml (node: mode standalone | backend | combined), accept the EULA in eula.txt
                         The first start downloads Mojang's official server for $mcVersion from Mojang into cache/mojang_$mcVersion.jar (SHA-1 verified), extracts it to .fission/vanilla
                         and the bundled surface declarations to .fission/surface. Mojang's server is not part of FissionMC.

            Hosting panels (Pterodactyl egg with the start line  java <flags> -jar ${'$'}{SERVER_JARFILE} nogui ): set the egg variable Server Jar File to master.jar or backend.jar.
            The master prints a line containing  )! For help, type "  when it is up (the egg's startup detection), and stops on the console command stop.

            Multi server: see the node:, client:, regions:, plugins: keys in the generated fission.yml files. Node keys: java -cp master.jar net.fissionmc.net.NodeKeysMainKt <keyfile> <node id> [authorized-keys file]
            """.trimIndent() + "\n")
        logger.lifecycle("dist: " + root.listFiles()!!.sortedBy { it.name }.joinToString { it.name + (if (it.isFile) " " + it.length() / 1024 + " KiB" else "/") })
    }
}
