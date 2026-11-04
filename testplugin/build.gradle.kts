dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.240-stable")
}

// Drops the jar into run/plugins for :backend:run.
tasks.register<Copy>("installTestPlugin") {
    dependsOn("jar")
    from(tasks.named("jar"))
    into(rootProject.file("run/plugins"))
}
