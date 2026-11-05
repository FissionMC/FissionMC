package net.fissionmc.net

import net.fissionmc.protocol.NodeRole
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AuthAndClientTest {
    private val masterKeys = Keys.generate()
    private val beKeys = Keys.generate()
    private fun addr() = NodeAddress.Local("a-" + System.nanoTime())
    private fun masterT(trusted: Map<String, java.security.PublicKey>) =
        NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"), security = NodeSecurity(masterKeys.private) { trusted[it] })
    private fun beT(id: String, key: java.security.KeyPair, masterPub: java.security.PublicKey) =
        NettyTransport(LocalNode(id, NodeRole.BACKEND, "26.3"), security = NodeSecurity(key.private) { if (it == "master") masterPub else null })

    @Test fun mutualAuthSucceeds() {
        val a = addr(); val m = masterT(mapOf("be-1" to beKeys.public)); val b = beT("be-1", beKeys, masterKeys.public)
        val bound = m.bind(a) {}
        try {
            val peer = b.connect(a).get(5, TimeUnit.SECONDS).ready.get(5, TimeUnit.SECONDS)
            assertEquals("master", peer.nodeId)
        } finally { bound.close(); b.close(); m.close() }
    }

    @Test fun unknownNodeRejected() {
        val a = addr(); val m = masterT(emptyMap()); val b = beT("be-1", beKeys, masterKeys.public)
        val bound = m.bind(a) {}
        try {
            val ex = assertFailsWith<ExecutionException> { b.connect(a).get(5, TimeUnit.SECONDS).ready.get(5, TimeUnit.SECONDS) }
            assertTrue(ex.cause!!.message!!.contains("unknown node"), ex.cause!!.message)
        } finally { bound.close(); b.close(); m.close() }
    }

    @Test fun impostorBackendRejected() {
        val a = addr(); val m = masterT(mapOf("be-1" to beKeys.public))
        val b = beT("be-1", Keys.generate(), masterKeys.public) // wrong private key
        val bound = m.bind(a) {}
        try {
            val link = b.connect(a).get(5, TimeUnit.SECONDS)
            val ex = assertFailsWith<ExecutionException> { link.ready.get(5, TimeUnit.SECONDS) }
            assertTrue(ex.cause!!.message!!.contains("failed authentication") || ex.cause!!.message!!.contains("lost"), ex.cause!!.message)
        } finally { bound.close(); b.close(); m.close() }
    }

    @Test fun impostorMasterRejected() {
        val a = addr(); val m = masterT(mapOf("be-1" to beKeys.public))
        val b = beT("be-1", beKeys, Keys.generate().public) // backend trusts a different master key
        val bound = m.bind(a) {}
        try {
            val link = b.connect(a).get(5, TimeUnit.SECONDS)
            val ex = assertFailsWith<ExecutionException> { link.ready.get(5, TimeUnit.SECONDS) }
            assertTrue(ex.cause!!.message!!.contains("failed authentication"), ex.cause!!.message)
        } finally { bound.close(); b.close(); m.close() }
    }

    @Test fun clientReconnectsAfterDrop() {
        val a = addr()
        val m = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val b = NettyTransport(LocalNode("be", NodeRole.BACKEND, "26.3"))
        val serverLinks = java.util.concurrent.LinkedBlockingQueue<NodeLink>()
        val bound = m.bind(a) { serverLinks.add(it) }
        val readies = java.util.concurrent.LinkedBlockingQueue<NodeLink>()
        val client = NodeClient(b, a, { l, _ -> readies.add(l) }, initialDelayMs = 50).start()
        try {
            val first = readies.poll(5, TimeUnit.SECONDS)!!
            serverLinks.poll(5, TimeUnit.SECONDS)!!.close("kick")
            val second = readies.poll(5, TimeUnit.SECONDS)
            assertNotNull(second, "client did not reconnect")
            assertNotSame(first, second)
        } finally { client.close(); bound.close(); b.close(); m.close() }
    }

    @Test fun clientWaitsForLateMaster() {
        val a = addr()
        val m = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3"))
        val b = NettyTransport(LocalNode("be", NodeRole.BACKEND, "26.3"))
        val ready = CompletableFuture<NodeLink>()
        val client = NodeClient(b, a, { l, _ -> ready.complete(l) }, initialDelayMs = 50, maxDelayMs = 100).start()
        Thread.sleep(300)
        val bound = m.bind(a) {}
        try { assertNotNull(ready.get(5, TimeUnit.SECONDS)) } finally { client.close(); bound.close(); b.close(); m.close() }
    }
}
