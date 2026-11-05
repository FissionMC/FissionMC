dependencies {
    api(project(":protocol"))
    api(project(":packetevents"))
    testImplementation(kotlin("test"))
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
