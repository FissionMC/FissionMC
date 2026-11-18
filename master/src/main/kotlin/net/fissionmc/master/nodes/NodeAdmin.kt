package net.fissionmc.master.nodes

import net.fissionmc.master.NodeRegistry
import net.fissionmc.master.RegisteredNode
import net.fissionmc.net.NodeSecurity
import net.fissionmc.net.pairing.*
import net.fissionmc.protocol.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The master's node administration: pairing, listing, removing, renaming, key rotation. One instance per master, used by the console (`node ...`) and by admins in game (`/fission node ...`).
 * It also owns the messages of the key rotation (a backend may only rotate ITS OWN key, and only when the admin asked for it).
 */
class NodeAdmin(
    private val keyFile: Path,
    private val security: NodeSecurity,
    val store: PairingStore,
    private val registry: NodeRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val keys get() = store.authorized
    private val rotating = ConcurrentHashMap<String, Pair<Long, Long>>()      // node id -> (request id, asked at)
    private val stillOnOldKey = ConcurrentHashMap.newKeySet<String>()         // after `rotate-master`: nodes that have not been seen with the new key
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "node-admin").also { it.isDaemon = true } }
    private val rng = java.security.SecureRandom()

    init {
        // a node whose key was removed from the file by hand loses its live link within a few seconds (node remove does it at once)
        timer.scheduleWithFixedDelay({ runCatching { dropUnauthorized() } }, 5, 5, TimeUnit.SECONDS)
    }

    private fun dropUnauthorized() { registry.all.forEach { n -> if (keys[n.info.nodeId] == null && n.link.isOpen) { Audit.event("node ${n.info.nodeId} is no longer in the authorized keys: link closed"); n.link.close("no longer authorized") } } }

    val masterFingerprint: String get() = Fingerprint.of(security.publicKey!!)

    /** Registry listener: after a rotation, backends that connected with the old key get the new one at once. */
    val listener = object : NodeRegistry.Listener {
        override fun joined(node: RegisteredNode) {
            val id = node.info.nodeId
            if (node.info.usedPreviousKey) {
                stillOnOldKey += id
                val old = security.previous ?: return
                val new = security.publicKey ?: return
                // signed by the old key (the one the backend pins) and by the new key (proof of possession); the backend checks both
                val cur = keyPairOfCurrent() ?: return
                node.link.send(Rotation.masterRotation(id, clock(), old.private, cur))
                Audit.event("node $id still pinned the previous master key: sent the signed rotation to ${Fingerprint.of(new)}")
            } else if (stillOnOldKey.remove(id)) Audit.event("node $id now uses the new master key")
        }
    }
    @Volatile private var currentPair: java.security.KeyPair? = null
    private fun keyPairOfCurrent(): java.security.KeyPair? = currentPair ?: runCatching { net.fissionmc.net.Keys.load(keyFile) }.getOrNull()?.also { currentPair = it }

    /** KeyRotation from a backend (it answers a RotateKeyRequest). True when the message was ours. */
    fun handle(node: RegisteredNode, msg: NodeMessage): Boolean {
        if (msg !is KeyRotation) return false
        val id = node.info.nodeId
        val ask = rotating.remove(id)
        fun answer(ok: Boolean, text: String) { node.link.send(KeyRotationAck(msg.reqId, ok, text)); if (!ok) Audit.event("rotation of node $id refused: $text") }
        if (ask == null || ask.first != msg.reqId || clock() - ask.second > 60_000) return true.also { answer(false, "no rotation was requested") }
        val old = keys[id] ?: return true.also { answer(false, "node is not authorized") }
        val new = Rotation.verifyNodeRotation(id, ask.first, old, msg) ?: return true.also { answer(false, "the rotation is not signed by the authorized key and the new key") }
        keys.put(id, new)
        Audit.event("node $id rotated its key ${Fingerprint.of(old)} -> ${Fingerprint.of(new)}")
        answer(true, "stored")
        return true
    }

    /** `node ...` (the words after `node`). [who] is for the audit lines. */
    fun command(args: List<String>, who: String, say: (String) -> Unit) {
        fun s(t: String) = say(Audit.clean(t, 400))
        when (args.getOrNull(0)?.lowercase()) {
            "add" -> {
                val replace = args.last().equals("replace", true) && args.size > 2
                val code = args.drop(1).let { if (replace) it.dropLast(1) else it }.joinToString("")
                if (code.isEmpty()) return s("usage: node add <pairing code> [replace]   (the code is the line the backend prints at its start)")
                when (val r = store.add(code, replace)) {
                    is PairingStore.AddResult.Refused -> { s("not added: ${r.reason}"); Audit.event("$who: node add refused: ${Audit.clean(r.reason, 160)}") }
                    is PairingStore.AddResult.Added -> {
                        val p = r.pending; val fp = Fingerprint.of(p.publicKey)
                        s("node ${p.nodeId} authorized, key fingerprint $fp${if (r.replacedKey) " (REPLACED the previous key of this id)" else ""}. Check that the backend console shows the same fingerprint.")
                        val online = registry[p.nodeId]?.link?.isOpen == true
                        s(if (online) "the node is connected with its old identity: it pairs when it reconnects" else "waiting for ${p.nodeId} to connect (the code is valid for ${(p.expiresAt - clock()) / 60_000} more min, once). This master's key fingerprint: $masterFingerprint (the backend prints it when it pins the key; compare)")
                        Audit.event("$who: node add ${p.nodeId} key $fp (pairing pending until first contact or expiry)")
                    }
                }
            }
            "list" -> {
                val all = runCatching { keys.load() }.getOrDefault(emptyMap())
                if (all.isEmpty()) s("no authorized nodes: start a backend with node.security on, copy its pairing code and `node add <code>`")
                all.toSortedMap().forEach { (id, k) -> s("$id  ${Fingerprint.of(k)}  ${if (registry[id]?.link?.isOpen == true) "online" else "offline"}${if (store.pendingFor(id) != null) "  pairing pending" else ""}") }
            }
            "pending" -> {
                val l = store.pendingList()
                if (l.isEmpty()) s("no pairing is pending${if (!store.enabled) " (pairing is switched off)" else ""}")
                l.forEach { p -> s("${p.nodeId}  ${Fingerprint.of(p.publicKey)}  expires in ${((p.expiresAt - clock()) / 60_000).coerceAtLeast(0)} min") }
            }
            "remove" -> {
                val id = args.getOrNull(1) ?: return s("usage: node remove <id>")
                val had = store.remove(id)
                val link = registry[id]?.link
                link?.close("removed by an administrator")
                stillOnOldKey.remove(id)
                if (!had && link == null) s("no such node: $id") else s("node $id removed${if (link != null) ", its link was closed" else ""}; it can connect again only after a new `node add`")
                if (had || link != null) Audit.event("$who: node remove $id")
            }
            "rename" -> {
                val from = args.getOrNull(1); val to = args.getOrNull(2)
                if (from == null || to == null || args.size != 3) return s("usage: node rename <old id> <new id>")
                if (!PairingCode.NODE_ID.matches(to) || to == "master") return s("not a usable node id: $to")
                if (!keys.rename(from, to)) return s("cannot rename: $from is not authorized or $to is already taken")
                registry[from]?.link?.close("renamed")
                s("$from is now $to in the authorized keys. Set node.id: $to in that backend's fission.yml (it is `$from` there) and restart it.")
                Audit.event("$who: node rename $from -> $to")
            }
            "fingerprint" -> {
                val id = args.getOrNull(1)
                if (id == null) s("this master: $masterFingerprint") else keys[id]?.let { s("$id: ${Fingerprint.of(it)}") } ?: s("no such node: $id")
            }
            "rotate" -> {
                val id = args.getOrNull(1) ?: return s("usage: node rotate <id>   (the backend makes a new key; it must be online)")
                val n = registry[id]?.takeIf { it.link.isOpen } ?: return s("$id is not connected")
                val req = rng.nextLong(); rotating[id] = req to clock()
                n.link.send(RotateKeyRequest(req))
                s("asked $id to rotate its key; it signs the new key with the old one, the result is in the audit log")
                Audit.event("$who: node rotate $id requested")
            }
            "rotate-master" -> {
                if (args.getOrNull(1)?.lowercase() == "finish") {
                    if (security.previous == null) return s("no master key rotation is in progress")
                    if (stillOnOldKey.isNotEmpty() && args.getOrNull(2) != "force") return s("not finished: ${stillOnOldKey.sorted().joinToString()} have not connected since the rotation and still pin the old master key. Wait for them, or `node rotate-master finish force` (they then need a new pairing: delete .fission-master-key on them).")
                    MasterKeyring.finish(keyFile, security); currentPair = null
                    s("the previous master key was deleted"); Audit.event("$who: master key rotation finished")
                    return
                }
                if (security.previous != null) return s("a rotation is still in progress (previous key kept): `node rotate-master finish` first")
                val fresh = MasterKeyring.rotate(keyFile, security); currentPair = fresh
                val prev = security.previous!!
                stillOnOldKey.clear(); stillOnOldKey += keys.load().keys
                var sent = 0
                registry.all.filter { it.link.isOpen }.forEach { n -> n.link.send(Rotation.masterRotation(n.info.nodeId, clock(), prev.private, fresh)); sent++ }
                s("new master key $masterFingerprint; $sent connected backends were told (signed with the old key) and reconnect. The old key stays in ${MasterKeyring.previousFile(keyFile).fileName} for backends that are offline now; `node rotate-master finish` when `node list` shows them all back.")
                Audit.event("$who: master key rotated to ${Fingerprint.of(fresh.public)}; $sent nodes informed")
            }
            "pairing" -> {
                when (args.getOrNull(1)?.lowercase()) { "on" -> store.enabled = true; "off" -> store.enabled = false; null -> {}; else -> return s("usage: node pairing [on|off]") }
                s("pairing is ${if (store.enabled) "on" else "off"} (up to ${store.maxPending} pending; node.pairing in fission.yml is the value at start)")
                if (args.size > 1) Audit.event("$who: pairing ${if (store.enabled) "on" else "off"}")
            }
            else -> s("usage: node add <code> [replace] | list | pending | remove <id> | rename <old> <new> | fingerprint [id] | rotate <id> | rotate-master [finish] | pairing [on|off]")
        }
    }

    override fun close() { timer.shutdownNow() }

    companion object {
        /** The instance of this JVM's master, for the in-game command (set by the master main). */
        @Volatile var current: NodeAdmin? = null
    }
}
