dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.240-stable")
}

// Drops the jar into run/plugins for :backend:run.
tasks.register<Copy>("installTestPlugin") {
    dependsOn("jar")
    from(tasks.named("jar"))
    into(rootProject.file("run/plugins"))
}

// Two tiny plugins (ReplX, ReplY) sharing one main class, for .dev/scripts/plugin-replication-test.sh (build/repl-jars/*.jar).
for (n in listOf("ReplX", "ReplY")) {
    tasks.register<Jar>("replJar$n") {
        archiveFileName.set("$n.jar")
        destinationDirectory.set(layout.buildDirectory.dir("repl-jars"))
        from(sourceSets.main.get().output) { exclude("plugin.yml") }
        val yml = layout.buildDirectory.file("repl-yml/$n/plugin.yml")
        doFirst { yml.get().asFile.apply { parentFile.mkdirs(); writeText("name: $n\nmain: net.fissionmc.testplugin.ReplProbe\nversion: 1\napi-version: \"26.3\"\nload: STARTUP\n") } }
        from(yml)
    }
}
tasks.register("replJars") { dependsOn("replJarReplX", "replJarReplY") }
