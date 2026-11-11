// Legacy-API conversion test plugins (Paper's Commodore / CraftLegacy). No old API jar exists on the box, so the old-API-shaped
// class (LegacyShapes) is generated with ASM by LegacyShapesGen (src/gen). Three jars share the same classes and differ in plugin.yml:
//   LegacyA: no api-version (full conversion), LegacyB: api-version 1.13 (modern conversion only), LegacyC: api-version 26.3 (must NOT be converted).
plugins { java }

val gen: SourceSet by sourceSets.creating
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.240-stable")
    "genImplementation"("org.ow2.asm:asm:9.9.1")
}

val genDir = layout.buildDirectory.dir("generated-classes")
val genShapes = tasks.register<JavaExec>("genShapes") {
    classpath = gen.runtimeClasspath
    mainClass.set("LegacyShapesGen")
    args(genDir.get().asFile.absolutePath)
    inputs.files(gen.output)
    outputs.dir(genDir)
}

val ymls = mapOf("LegacyA" to null, "LegacyB" to "1.13", "LegacyC" to "26.3")
for ((n, api) in ymls) {
    tasks.register<Jar>("jar$n") {
        dependsOn(genShapes)
        archiveFileName.set("$n.jar")
        destinationDirectory.set(layout.buildDirectory.dir("jars"))
        from(sourceSets.main.get().output)
        from(genDir)
        val yml = layout.buildDirectory.file("yml/$n/plugin.yml")
        doFirst {
            yml.get().asFile.apply {
                parentFile.mkdirs()
                writeText("name: $n\nmain: net.fissionmc.testpluginlegacy.LegacyMain\nversion: 1\nload: POSTWORLD\n" + (if (api != null) "api-version: \"$api\"\n" else ""))
            }
        }
        from(yml)
    }
}
tasks.register("legacyJars") { dependsOn(ymls.keys.map { "jar$it" }) }
