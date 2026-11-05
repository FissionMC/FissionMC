dependencies {
    api(project(":net"))
    api(project(":packets"))
}

dependencies { testImplementation(kotlin("test")) }
tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.register<JavaExec>("run") {
    group = "fission"
    description = "Runs the standalone master (reads run-master/fission.properties)"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "net.fissionmc.master.MasterMainKt"
    workingDir = rootProject.file("run-master").also { it.mkdirs() }
    args("fission.properties")
}
