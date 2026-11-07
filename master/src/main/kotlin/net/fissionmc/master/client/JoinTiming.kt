package net.fissionmc.master.client

/**
 * Where the time of a player's first entry (login success, limbo, first backend) went; one line in the master log so a slow join can be read without a probe:
 * `[join] R4T_2 phases: limbo 0.05s, config 0.42s, join 0.35s, held 0.00s, chunks 0.62s (first batch 0.40s, position 0.12s), total 1.44s -> backend-a; limbo client loaded after 0.31s`.
 *  limbo  = login success until the limbo burst is sent and the backend switch started (waiting for a backend)
 *  config = the master completing the backend's configuration as the client (includes the backend preparing the spawn chunks: vanilla's PrepareSpawnTask is a configuration task)
 *  join   = the backend placing the player until its Login arrived
 *  held   = the Login waited for the limbo client to report it is loaded (0 when it already had; the configuration overlaps with the limbo loading)
 *  chunks = Login/Respawn forwarded until the client said it has its terrain (ServerboundPlayerLoaded); `first batch`/`position` = when the first chunk batch / PlayerPosition went to the client
 *  `limbo client loaded after` = when the limbo client's own PlayerLoaded arrived (`grace` = it never did, the timer released the join)
 */
class JoinTiming(private val clock: () -> Long = System::nanoTime) {
    private val t0 = clock()
    @Volatile private var limbo = 0L; private var loaded = 0L; private var byGrace = false; private var attach = 0L; private var cfg = 0L; private var login = 0L; private var forwarded = 0L
    private var batch = 0L; private var position = 0L; private var clientLoaded = 0L
    val isForwarded: Boolean get() = forwarded != 0L
    val isDone: Boolean get() = clientLoaded != 0L
    fun limboJoined() { if (limbo == 0L) limbo = clock() }
    fun limboLoaded(grace: Boolean) { if (loaded == 0L) { loaded = clock(); byGrace = grace } }
    fun attached() { if (attach == 0L) attach = clock() }
    fun configDone() { if (cfg == 0L) cfg = clock() }
    fun backendLogin() { if (login == 0L) login = clock() }
    fun loginForwarded() { if (forwarded == 0L) forwarded = clock() }
    fun chunkBatch() { if (forwarded != 0L && batch == 0L) batch = clock() }
    fun positionSent() { if (forwarded != 0L && position == 0L) position = clock() }
    fun playerLoaded() { if (forwarded != 0L && clientLoaded == 0L) clientLoaded = clock() }

    private fun s(a: Long, b: Long) = if (a == 0L || b == 0L) "?" else "%.2fs".format(java.util.Locale.ROOT, (b - a) / 1e9)
    fun line(name: String, node: String?): String {
        val end = if (clientLoaded != 0L) clientLoaded else clock()
        val chunks = if (clientLoaded != 0L) s(forwarded, clientLoaded) else "never (${s(forwarded, end)} so far)"
        return "[join] $name phases: limbo ${s(t0, attach)}, config ${s(attach, cfg)}, join ${s(cfg, login)}, held ${s(login, forwarded)}, " +
            "chunks $chunks (first batch ${s(forwarded, batch)}, position ${s(forwarded, position)}), total ${s(t0, end)}${if (node != null) " -> $node" else ""}; limbo client loaded after ${s(t0, loaded)}${if (byGrace) " (grace)" else ""}"
    }
}
