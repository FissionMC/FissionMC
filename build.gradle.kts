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
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }
}
