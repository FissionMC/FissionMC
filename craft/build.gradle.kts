
repositories {
    maven("https://repo.lucko.me/")
}

// Mirrors paper-server's dependency block (kept in the same order) so ported code resolves.
dependencies {
    api("io.papermc.paper:paper-api:26.3.build.240-stable")
    api(platform("ca.spottedleaf.leafpile:bom:1.3.2"))
    api("ca.spottedleaf.leafpile:common")
    api("ca.spottedleaf.leafpile:concurrentutil")
    api("ca.spottedleaf.leafpile:converter")
    api("ca.spottedleaf.leafpile:ioutil")
    api("org.jline:jline-terminal-ffm:4.4.6")
    api("net.minecrell:terminalconsoleappender:1.3.0")
    api("net.kyori:adventure-text-serializer-ansi")
    api("org.apache.logging.log4j:log4j-core:2.26.0")
    api("com.velocitypowered:velocity-native:4.1.0") { isTransitive = false }
    api("io.netty:netty-codec-haproxy:4.2.16.Final")
    api("org.apache.logging.log4j:log4j-iostreams:2.26.0")
    api("org.ow2.asm:asm-commons:9.9.1")
    api("org.spongepowered:configurate-yaml:4.2.0")
    runtimeOnly("commons-lang:commons-lang:2.6")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("com.mysql:mysql-connector-j:9.2.0")
    runtimeOnly("com.lmax:disruptor:3.4.4")
    api("com.googlecode.json-simple:json-simple:1.1.1") { isTransitive = false }
    api("me.lucko:spark-api:0.1-20240720.200737-2")
    api("me.lucko:spark-paper:1.10.191")

    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.checkerframework:checker-qual:4.2.3")
    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "100000", "-nowarn"))
    options.encoding = "UTF-8"
}
