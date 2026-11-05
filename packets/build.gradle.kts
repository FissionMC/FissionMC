evaluationDependsOn(":packetevents")
dependencies {
    api(project(":protocol"))
    api(project(":packetevents"))
    testImplementation(kotlin("test"))
}
val adventurePatch = project(":packetevents").tasks.named("patchJar")
tasks.withType<Test>().configureEach { useJUnitPlatform(); dependsOn(adventurePatch); classpath = files(adventurePatch) + classpath }
