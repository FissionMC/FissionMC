val mcVersion: String by project
val mcJar = rootProject.file(".cache/$mcVersion/x/META-INF/versions/$mcVersion/server-$mcVersion.jar")
val mcLibs = rootProject.fileTree(".cache/$mcVersion/x/META-INF/libraries") { include("**/*.jar") }

dependencies {
    api(project(":net"))
    api(project(":packets"))
    implementation(project(":loader"))
    compileOnly(files(mcJar))
    compileOnly(mcLibs)
}
