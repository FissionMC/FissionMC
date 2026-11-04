val mcVersion: String by project
val mcJar = rootProject.file(".cache/$mcVersion/x/META-INF/versions/$mcVersion/server-$mcVersion.jar")
val mcLibs = rootProject.fileTree(".cache/$mcVersion/x/META-INF/libraries") { include("**/*.jar") }

dependencies {
    api("io.papermc.paper:paper-api:26.3.build.240-stable")
    compileOnly(files(mcJar))
    compileOnly(mcLibs)
    compileOnly("org.jspecify:jspecify:1.0.0")
}

dependencies {
    api("org.spongepowered:configurate-yaml:4.2.0")
    compileOnly("org.checkerframework:checker-qual:4.2.3")
    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
}
