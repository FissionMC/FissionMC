package net.fissionmc.net

/** Where a node listens. [Local] stays inside the JVM (master hosted in a backend); [Tcp] crosses processes. */
sealed interface NodeAddress {
    data class Local(val name: String) : NodeAddress
    data class Tcp(val host: String, val port: Int) : NodeAddress
}
