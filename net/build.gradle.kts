dependencies {
    api(project(":protocol"))
    api(platform("io.netty:netty-bom:4.2.20.Final"))
    api("io.netty:netty-transport")
    api("io.netty:netty-codec-base")
}
