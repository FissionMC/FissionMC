dependencies {
    api(project(":net"))
    api(project(":packets"))
    testImplementation(kotlin("test"))
}

dependencies { testImplementation(kotlin("test")) }
tasks.withType<Test>().configureEach { useJUnitPlatform(); systemProperty("fission.debug.packets", "1") }

tasks.register<JavaExec>("run") {
    group = "fission"
    description = "Runs the standalone master (reads run-master/fission.properties)"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
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
