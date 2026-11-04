import org.gradle.api.tasks.SourceSetContainer

val mcVersion: String by project
val mcJar = rootProject.file(".cache/$mcVersion/x/META-INF/versions/$mcVersion/server-$mcVersion.jar")
val mcLibs = rootProject.fileTree(".cache/$mcVersion/x/META-INF/libraries") { include("**/*.jar") }


dependencies {
    api(project(":net"))
    api(project(":packets"))
    implementation(project(":loader"))
    implementation(project(":craft"))
    compileOnly("net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.5")
    compileOnly("org.jspecify:jspecify:1.0.0")
}

// fission.mixins.json is generated from every *Mixin.java under the mixin package, so parallel work
// never conflicts on a shared list.
val generateMixinConfig by tasks.registering {
    val root = file("src/main/java/net/fissionmc/backend/mixin")
    val out = layout.buildDirectory.dir("generated/mixin-config")
    inputs.dir(root)
    outputs.dir(out)
    doLast {
        val names = root.walkTopDown().filter { it.isFile && it.name.endsWith("Mixin.java") }
            .map { it.relativeTo(root).path.removeSuffix(".java").replace(File.separatorChar, '.') }
            .sorted().toList()
        val json = buildString {
            append("{\n  \"required\": true,\n  \"minVersion\": \"0.8\",\n  \"package\": \"net.fissionmc.backend.mixin\",\n")
            append("  \"compatibilityLevel\": \"JAVA_25\",\n  \"injectors\": { \"defaultRequire\": 1 },\n  \"mixins\": [\n")
            append(names.joinToString(",\n") { "    \"$it\"" })
            append("\n  ]\n}\n")
        }
        out.get().file("fission.mixins.json").asFile.apply { parentFile.mkdirs(); writeText(json) }
    }
}
sourceSets["main"].resources.srcDir(generateMixinConfig)

tasks.register<JavaExec>("run") {
    group = "fission"
    description = "Boots the server through the FissionMC loader and Paper's entry point (org.bukkit.craftbukkit.Main)"
    val loaderCp = project(":loader").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    val gameCp = files(mcJar, mcLibs, sourceSets["main"].runtimeClasspath)
    dependsOn(":loader:classes", "classes", ":testplugin:installTestPlugin")
    classpath = loaderCp
    mainClass = "net.fissionmc.loader.Bootstrap"
    workingDir = rootProject.file("run").also { it.mkdirs() }
    jvmArgs(
        "-Dfission.mixins=fission.mixins.json",
        "-Dfission.surface=" + rootProject.file("surface").path,
        "-Dfission.main=org.bukkit.craftbukkit.Main",
    )
    doFirst { systemProperty("fission.classpath", gameCp.asPath) }
    args("--nogui")
    standardInput = System.`in`
}
