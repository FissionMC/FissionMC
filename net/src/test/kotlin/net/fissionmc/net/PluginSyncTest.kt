package net.fissionmc.net

import io.netty.buffer.Unpooled
import net.fissionmc.net.plugins.*
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class PluginSyncTest {
    private fun jar(dir: Path, file: String, plugin: String, extra: Int = 0): Path {
        val p = dir.resolve(file)
        ZipOutputStream(Files.newOutputStream(p)).use { z ->
            z.putNextEntry(ZipEntry("plugin.yml")); z.write("name: $plugin\nmain: x.Y\nversion: 1\n".toByteArray()); z.closeEntry()
            if (extra > 0) { z.putNextEntry(ZipEntry("blob")); z.write(ByteArray(extra) { (it * 31).toByte() }); z.closeEntry() }
        }
        return p
    }
    private fun entry(file: String, name: String, size: Long = 10, seed: Int = 1) = PluginEntry(file, size, ByteArray(32) { (it + seed).toByte() }, name)
    private fun fj(file: String, name: String, seed: Int = 1) = FolderJar(file, PluginFolder.hex(ByteArray(32) { (it + seed).toByte() }), name)

    @Test fun codecRoundTrip() {
        val reg = MessageRegistry.core().registerPluginMessages()
        val b = Unpooled.buffer()
        reg.encode(PluginManifest(true, listOf(entry("A.jar", "A", 5), entry("B.jar", ""))), b)
        val m = reg.decode(b) as PluginManifest
        assertTrue(m.enabled); assertEquals(listOf("A.jar", "B.jar"), m.files.map { it.file }); assertEquals("A", m.files[0].pluginName); assertEquals(5, m.files[0].size)
        reg.encode(PluginFileChunk("A.jar", 7, byteArrayOf(1, 2), true), b); val c = reg.decode(b) as PluginFileChunk
        assertEquals(7, c.offset); assertTrue(c.last); assertContentEquals(byteArrayOf(1, 2), c.data)
        reg.encode(PluginManifestRequest(), b); assertTrue(reg.decode(b) is PluginManifestRequest)
        reg.encode(PluginFileRequest("A.jar"), b); assertEquals("A.jar", (reg.decode(b) as PluginFileRequest).file)
        reg.encode(PluginSyncError("x"), b); assertEquals("x", (reg.decode(b) as PluginSyncError).message)
    }

    @Test fun planDownloadsNewAndKeepsUnchanged() {
        val managed = ManagedState(listOf(ManagedJar("Vault.jar", PluginFolder.hex(ByteArray(32) { (it + 1).toByte() }), "Vault")))
        val p = PluginSync.plan(listOf(entry("Vault.jar", "Vault"), entry("WorldEdit.jar", "WorldEdit")), listOf(fj("Vault.jar", "Vault"), fj("Own.jar", "Own")), managed, ConflictPolicy.LOCAL)
        assertEquals(listOf("WorldEdit.jar"), p.download.map { it.file }); assertEquals(listOf("Vault.jar"), p.unchanged); assertTrue(p.delete.isEmpty())
        assertEquals(setOf("Vault.jar", "WorldEdit.jar"), p.state.jars.map { it.file }.toSet())
    }

    @Test fun planRedownloadsChangedAndDeletesStaleButNeverLocal() {
        val managed = ManagedState(listOf(ManagedJar("Old.jar", "aa", "Old"), ManagedJar("Vault.jar", "bb", "Vault")))
        val p = PluginSync.plan(listOf(entry("Vault.jar", "Vault", seed = 9)), listOf(fj("Old.jar", "Old"), fj("Vault.jar", "Vault", seed = 1), fj("Own.jar", "Own")), managed, ConflictPolicy.LOCAL)
        assertEquals(listOf("Vault.jar"), p.download.map { it.file }); assertEquals(listOf("Old.jar"), p.delete)
        assertFalse("Own.jar" in p.delete); assertFalse(p.state.jars.any { it.file == "Own.jar" })
    }

    @Test fun conflictPolicyLocalSkipsMasterJar() {
        val p = PluginSync.plan(listOf(entry("WorldEdit-master.jar", "WorldEdit")), listOf(fj("we.jar", "worldedit")), ManagedState(), ConflictPolicy.LOCAL)
        assertTrue(p.download.isEmpty()); assertEquals(1, p.skipped.size); assertTrue(p.shadow.isEmpty()); assertTrue(p.state.jars.isEmpty())
        // same file name, different plugin name: still a conflict (never overwrite an unmanaged file)
        val q = PluginSync.plan(listOf(entry("a.jar", "Other")), listOf(fj("a.jar", "Mine")), ManagedState(), ConflictPolicy.LOCAL)
        assertTrue(q.download.isEmpty()); assertEquals(1, q.skipped.size)
    }

    @Test fun conflictPolicyMasterShadowsLocalAndRestoresLater() {
        val p = PluginSync.plan(listOf(entry("we-m.jar", "WorldEdit")), listOf(fj("we.jar", "WorldEdit")), ManagedState(), ConflictPolicy.MASTER)
        assertEquals(listOf("we-m.jar"), p.download.map { it.file }); assertEquals(listOf("we.jar"), p.shadow.map { it.file }); assertTrue(p.delete.isEmpty())
        assertEquals(listOf("we.jar"), p.state.shadows.map { it.file })
        // the master drops the plugin: the master jar goes, the shadowed local jar comes back
        val after = PluginSync.plan(emptyList(), listOf(fj("we-m.jar", "WorldEdit", 1)), p.state, ConflictPolicy.MASTER)
        assertEquals(listOf("we-m.jar"), after.delete); assertEquals(listOf("we.jar"), after.unshadow); assertTrue(after.state.shadows.isEmpty())
        // still provided: stays shadowed, nothing restored
        val still = PluginSync.plan(listOf(entry("we-m.jar", "WorldEdit")), listOf(fj("we-m.jar", "WorldEdit")), p.state, ConflictPolicy.MASTER)
        assertTrue(still.unshadow.isEmpty()); assertEquals(listOf("we.jar"), still.state.shadows.map { it.file })
    }

    @Test fun localJarAddedLaterEvictsManagedUnderLocalPolicy() {
        val managed = ManagedState(listOf(ManagedJar("we-m.jar", "aa", "WorldEdit")))
        val p = PluginSync.plan(listOf(entry("we-m.jar", "WorldEdit")), listOf(fj("we-m.jar", "WorldEdit"), fj("mine.jar", "WorldEdit")), managed, ConflictPolicy.LOCAL)
        assertEquals(listOf("we-m.jar"), p.delete); assertTrue(p.download.isEmpty())
    }

    @Test fun unsafeNamesAndDuplicatePluginsAreSkipped() {
        for (bad in listOf("../x.jar", "a/b.jar", ".hidden.jar", "x.txt", "", "a\\b.jar", "..jar")) assertFalse(PluginFolder.safeJarName(bad), bad)
        assertTrue(PluginFolder.safeJarName("WorldEdit-7.3.jar"))
        val p = PluginSync.plan(listOf(entry("../evil.jar", "E"), entry("a.jar", "Same"), entry("b.jar", "same")), emptyList(), ManagedState(), ConflictPolicy.LOCAL)
        assertEquals(listOf("a.jar"), p.download.map { it.file }); assertEquals(2, p.skipped.size)
    }

    @Test fun managedStateRoundTrips() {
        val s = ManagedState(listOf(ManagedJar("a.jar", "ab", "A")), listOf(ShadowedJar("b.jar", "B")))
        assertEquals(s, ManagedState.parse(s.serialize()))
        assertTrue(ManagedState.parse("jar\t../x.jar\tab\tX\n").jars.isEmpty())
    }

    @Test fun configDefaultsAndValidation() {
        val d = PluginsConfig.from(FissionConfig.parse("node: {}")); assertTrue(d.replicate); assertEquals("plugins", d.dir); assertEquals(ConflictPolicy.LOCAL, d.conflict)
        val c = PluginsConfig.from(FissionConfig.parse("plugins: { replicate: false, dir: shared, conflict: master }")); assertFalse(c.replicate); assertEquals("shared", c.dir); assertEquals(ConflictPolicy.MASTER, c.conflict)
        assertFailsWith<ConfigException> { PluginsConfig.from(FissionConfig.parse("plugins: { conflict: both }")) }
        assertFailsWith<ConfigException> { FissionConfig.parse("plugins: { replicat: true }").let { PluginsConfig.from(it); it.finish() } }
    }

    private fun session(masterDir: Path, enabled: Boolean = true, block: (NodeLink) -> Unit) {
        val a = NodeAddress.Local("p-" + System.nanoTime())
        val m = NettyTransport(LocalNode("master", NodeRole.MASTER, "26.3")); val b = NettyTransport(LocalNode("be", NodeRole.BACKEND, "26.3"))
        val server = PluginServer(masterDir, enabled)
        val bound = m.bind(a) { l -> l.handler = { server.handle(l, it) } }
        try { val link = b.connect(a).get(5, TimeUnit.SECONDS); link.ready.get(5, TimeUnit.SECONDS); block(link) } finally { bound.close(); server.close(); b.close(); m.close() }
    }
    private fun pull(link: NodeLink, dest: Path, policy: ConflictPolicy = ConflictPolicy.LOCAL): PluginSyncResult {
        val puller = PluginPuller(link, dest, policy); link.handler = { puller.handle(it) }
        return puller.pull().get(20, TimeUnit.SECONDS)
    }

    @Test fun endToEndInstallUpdateRemoveAndLocalJarUntouched() {
        val master = Files.createTempDirectory("pm"); val dest = Files.createTempDirectory("pd")
        jar(master, "Big.jar", "Big", FILE_CHUNK_SIZE * 2 + 5); jar(master, "Vault.jar", "Vault"); Files.writeString(master.resolve("notes.txt"), "not a jar")
        val own = jar(dest, "Own.jar", "Own"); val ownBytes = Files.readAllBytes(own)
        session(master) { link ->
            val r = pull(link, dest)
            assertEquals(listOf("Big.jar", "Vault.jar"), r.installed.sorted()); assertTrue(r.deleted.isEmpty())
            for (f in listOf("Big.jar", "Vault.jar")) assertContentEquals(Files.readAllBytes(master.resolve(f)), Files.readAllBytes(dest.resolve(f)))
            assertFalse(Files.exists(dest.resolve("notes.txt"))); assertTrue(Files.readString(dest.resolve(".fission-managed.txt")).contains("jar\tBig.jar"))
            // second boot: nothing to do
            val r2 = pull(link, dest); assertTrue(r2.installed.isEmpty()); assertEquals(2, r2.unchanged.size)
            // the master replaces Vault and drops Big: Vault re-downloaded, Big removed, Own untouched
            Files.delete(master.resolve("Big.jar")); jar(master, "Vault.jar", "Vault", 100)
            val r3 = pull(link, dest)
            assertEquals(listOf("Vault.jar"), r3.installed); assertEquals(listOf("Big.jar"), r3.deleted)
            assertFalse(Files.exists(dest.resolve("Big.jar"))); assertContentEquals(Files.readAllBytes(master.resolve("Vault.jar")), Files.readAllBytes(dest.resolve("Vault.jar")))
            assertContentEquals(ownBytes, Files.readAllBytes(own)); assertFalse(Files.readString(dest.resolve(".fission-managed.txt")).contains("Own.jar"))
            // a hand-edited (corrupted) managed jar is restored
            Files.write(dest.resolve("Vault.jar"), byteArrayOf(1)); assertEquals(listOf("Vault.jar"), pull(link, dest).installed)
            // part files never remain
            assertTrue(Files.list(dest).use { s -> s.noneMatch { it.fileName.toString().endsWith(".part") } })
        }
    }

    @Test fun endToEndConflictPolicies() {
        val master = Files.createTempDirectory("pm"); jar(master, "WorldEdit-master.jar", "WorldEdit"); jar(master, "Other.jar", "Other")
        val destL = Files.createTempDirectory("pd"); val local = jar(destL, "we.jar", "WorldEdit"); val localBytes = Files.readAllBytes(local)
        val destM = Files.createTempDirectory("pd"); jar(destM, "we.jar", "WorldEdit")
        session(master) { link ->
            val r = pull(link, destL, ConflictPolicy.LOCAL)
            assertEquals(listOf("Other.jar"), r.installed); assertEquals(1, r.skipped.size); assertFalse(Files.exists(destL.resolve("WorldEdit-master.jar"))); assertContentEquals(localBytes, Files.readAllBytes(local))
            val r2 = pull(link, destM, ConflictPolicy.MASTER)
            assertEquals(listOf("we.jar"), r2.shadowed); assertTrue(Files.exists(destM.resolve("we.jar.fission-shadowed"))); assertFalse(Files.exists(destM.resolve("we.jar"))); assertTrue(Files.exists(destM.resolve("WorldEdit-master.jar")))
            // the master drops WorldEdit: master jar removed, local jar restored
            Files.delete(master.resolve("WorldEdit-master.jar"))
            val r3 = pull(link, destM, ConflictPolicy.MASTER)
            assertEquals(listOf("we.jar"), r3.restored); assertTrue(Files.exists(destM.resolve("we.jar"))); assertFalse(Files.exists(destM.resolve("WorldEdit-master.jar")))
        }
    }

    @Test fun disabledMasterLeavesManagedJarsAlone() {
        val master = Files.createTempDirectory("pm"); jar(master, "A.jar", "A"); val dest = Files.createTempDirectory("pd")
        session(master) { link -> pull(link, dest) }
        session(master, enabled = false) { link ->
            val r = pull(link, dest)
            assertFalse(r.enabled); assertTrue(Files.exists(dest.resolve("A.jar")))
        }
    }

    @Test fun pluginNameReading() {
        val d = Files.createTempDirectory("pn")
        assertEquals("Vault", PluginFolder.pluginName(jar(d, "v.jar", "Vault")))
        Files.writeString(d.resolve("bad.jar"), "not a zip"); assertEquals("", PluginFolder.pluginName(d.resolve("bad.jar")))
    }
}
