dependencies {
    api(project(":protocol"))
    api(platform("io.netty:netty-bom:4.2.20.Final"))
    api("io.netty:netty-transport")
    api("io.netty:netty-codec-base")
    // the YAML reader (FissionConfig). compileOnly: :master adds snakeyaml itself, the backend gets it from paper-api (configurate-yaml): one copy per class path
    compileOnly("org.yaml:snakeyaml:2.2")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
