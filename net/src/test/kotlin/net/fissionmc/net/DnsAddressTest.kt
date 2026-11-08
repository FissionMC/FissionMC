package net.fissionmc.net

import net.fissionmc.protocol.NodeRole
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** A node address may be a DNS name: the backend dials `localhost` (resolved at connect time) while the master listens on the IP. */
class DnsAddressTest {
    @Test fun backendDialsAHostnameAndHandshakes() {
        val port = ServerSocket(0).use { it.localPort }
        val master = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val backend = NettyTransport(LocalNode("be-dns", NodeRole.BACKEND, "26.3"))
        val bound = master.bind(NodeAddress.Tcp("127.0.0.1", port)) { }
        try {
            val link = backend.connect(NodeAddress.Tcp("localhost", port)).get(10, TimeUnit.SECONDS)
            assertEquals("master", link.ready.get(10, TimeUnit.SECONDS).nodeId)
        } finally { bound.close(); backend.close(); master.close() }
    }

    @Test fun hostPortWithAnUnresolvableNameFailsToConnectInsteadOfHanging() {
        val backend = NettyTransport(LocalNode("be-dns2", NodeRole.BACKEND, "26.3"))
        try {
            val r = runCatching { backend.connect(NodeAddress.Tcp("no-such-host.invalid", 25600)).get(20, TimeUnit.SECONDS) }
            assert(r.isFailure) { "connect to an unresolvable name must fail" }
        } finally { backend.close() }
    }
}
