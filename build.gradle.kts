plugins {
    kotlin("jvm") version "2.4.21" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    group = "net.fissionmc"
    version = "0.1.0"

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }
}

// ---- FissionMC surface: compile against vanilla + Paper's AT + Paper-added member stubs ----
val mcVersion: String by project
val mcJar = file(".cache/$mcVersion/x/META-INF/versions/$mcVersion/server-$mcVersion.jar")
val mcLibs = fileTree(".cache/$mcVersion/x/META-INF/libraries") { include("**/*.jar") }
val surfaceJar = layout.buildDirectory.file("surface/surface-$mcVersion.jar")

val generateSurface by tasks.registering(JavaExec::class) {
    group = "fission"
    description = "Builds the compile-only surface jar from surface/"
    dependsOn(":loader:classes")
    classpath = project(":loader").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("net.fissionmc.loader.surface.SurfaceGen")
    inputs.dir(file("surface"))
    inputs.file(mcJar)
    inputs.files(mcLibs)
    outputs.file(surfaceJar)
    doFirst {
        args(listOf(file("surface").path, surfaceJar.get().asFile.path, mcJar.path) + mcLibs.files.map { it.path })
    }
}

configure(listOf(project(":craft"), project(":backend"))) {
    afterEvaluate {
        dependencies {
            "compileOnly"(files(mcJar))
            "compileOnly"(mcLibs)
        }
        tasks.withType<JavaCompile>().configureEach {
            dependsOn(generateSurface)
            classpath = files(surfaceJar) + classpath
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            dependsOn(generateSurface)
            libraries.setFrom(files(surfaceJar), libraries.files)
        }
    }
}
