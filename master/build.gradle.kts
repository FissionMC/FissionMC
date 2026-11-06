evaluationDependsOn(":packetevents")
dependencies {
    api(project(":net"))
    api(project(":packets"))
    // MiniMessage for the chat templates: the SAME line as the adventure the master already uses (PacketEvents' patched 4.26.1). The backend (via project(":master")) resolves
    // this module to paper-api's 5.x like it does adventure-api, so there is still exactly one copy on the backend class path (never add it to :backend/:craft).
    implementation("net.kyori:adventure-text-minimessage:4.26.1")
    testImplementation(kotlin("test"))
}

dependencies { testImplementation(kotlin("test")) }
// PacketEvents' patched adventure serializers (see packetevents/build.gradle.kts): master-only, never on the backend class path
val adventurePatch = project(":packetevents").tasks.named("patchJar")
tasks.withType<Test>().configureEach { useJUnitPlatform(); systemProperty("fission.debug.packets", "1"); dependsOn(adventurePatch); classpath = files(adventurePatch) + classpath }

tasks.register<JavaExec>("run") {
    group = "fission"
    description = "Runs the standalone master (reads run-master/fission.properties)"
    dependsOn("classes")
    dependsOn(adventurePatch)
    classpath = files(adventurePatch) + sourceSets["main"].runtimeClasspath
    mainClass = "net.fissionmc.master.MasterMainKt"
    workingDir = rootProject.file("run-master").also { it.mkdirs() }
    args("fission.properties")
}

// ./gradlew :master:nodeKeys -Pkeyfile=run-a/fission-node.key -Pnode=backend-a [-Pauthorized=run-master/fission-authorized.keys]
tasks.register<JavaExec>("nodeKeys") {
    group = "fission"
    description = "Creates a node Ed25519 key file and prints its public key (optionally adds it to an authorized-keys file)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "net.fissionmc.net.NodeKeysMainKt"
    doFirst {
        val a = mutableListOf(rootProject.file(project.property("keyfile") as String).path, project.property("node") as String)
        (project.findProperty("authorized") as String?)?.let { a += rootProject.file(it).path }
        args(a)
    }
}

// ./gradlew :master:clientProbe -Pport=25577 -Pout=/abs/stream.bin  (scripted offline client, see ClientProbeMain)
tasks.register<JavaExec>("clientProbe") {
    group = "fission"
    dependsOn("testClasses")
    dependsOn(adventurePatch)
    classpath = files(adventurePatch) + sourceSets["test"].runtimeClasspath
    mainClass = "net.fissionmc.master.client.ClientProbeMainKt"
    doFirst { args(project.property("port") as String, project.property("out") as String) }
}

// Writes the class path of the scripted probes to build/probe.classpath, so scripts can run them with plain java (no Gradle) while servers run.
tasks.register("probeClasspath") {
    group = "fission"
    dependsOn("testClasses"); dependsOn(adventurePatch)
    val cp = files(adventurePatch) + sourceSets["test"].runtimeClasspath
    val out = layout.buildDirectory.file("probe.classpath")
    doLast { out.get().asFile.writeText(cp.asPath) }
}

// ./gradlew :master:importWorld -Pworld=<world dir>[,<world dir>...] [-Pstore=run-master/chunkstore] : seeds the master's chunk store from world directories
tasks.register<JavaExec>("importWorld") {
    group = "fission"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "net.fissionmc.master.chunk.ChunkImportKt"
    doFirst {
        val store = rootProject.file((project.findProperty("store") as String?) ?: "run-master/chunkstore").path
        args(listOf(store) + (project.property("world") as String).split(',').map { rootProject.file(it).path })
    }
}
