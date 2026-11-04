import org.gradle.api.tasks.SourceSetContainer

val mcVersion: String by project
val mcJar = rootProject.file(".cache/$mcVersion/x/META-INF/versions/$mcVersion/server-$mcVersion.jar")
val mcLibs = rootProject.fileTree(".cache/$mcVersion/x/META-INF/libraries") { include("**/*.jar") }

dependencies {
    api(project(":net"))
    api(project(":packets"))
    implementation(project(":loader"))
    compileOnly("net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7")
    compileOnly(files(mcJar))
    compileOnly(mcLibs)
    compileOnly("org.jspecify:jspecify:1.0.0")
}

tasks.register<JavaExec>("run") {
    group = "fission"
    description = "Boots vanilla $mcVersion through the FissionMC loader"
    val loaderCp = project(":loader").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    val gameCp = files(mcJar, mcLibs, sourceSets["main"].runtimeClasspath)
    dependsOn(":loader:classes", "classes")
    classpath = loaderCp
    mainClass = "net.fissionmc.loader.Bootstrap"
    workingDir = rootProject.file("run").also { it.mkdirs() }
    jvmArgs("-Dfission.mixins=fission.mixins.json")
    doFirst { systemProperty("fission.classpath", gameCp.asPath) }
    args("nogui")
    standardInput = System.`in`
}
