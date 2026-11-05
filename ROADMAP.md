# FissionMC roadmap

Status tracker only. `[x]` done, `[~]` in progress, `[ ]` not started. Rationale lives in project memory.

## M0 Foundation
- [x] Gradle multi-module scaffold
- [x] Mixin loader (custom class loader + Mixin service)
- [x] Vanilla 26.3 boots through loader
- [x] CraftServer skeleton (throwing stubs)

## M1 Plugin bring-up
- [x] Split: master / backend products, shared protocol+net+packets, loader, craft
- [x] Mixin on MinecraftServer.loadLevel creates CraftServer, Bukkit.setServer
- [x] Name/version/logger/plugin folder, ServerBuildInfo provider
- [x] Paper's plugin system ported (scripts/port.py, provenance in upstream/ported/paper.tsv): test plugin loads, enables, disables
- [x] Parallel jar probe + stable load order
- [ ] Persistent descriptor cache (path+size+mtime), lazy class loading
- [ ] BOOTSTRAP entrypoint at the right time (before registries), bootstrap plugins
- [ ] Legacy plugin bytecode conversion (Paper commodore); LifecycleEvents.TAGS; permissions.yml; reload
- [ ] UnsafeValues, scheduler, services manager
- [ ] Commands (Bukkit + Brigadier)
- [ ] Test plugin enables, registers command + listener

## M2 Packet pipeline
- [x] License: GPL-3.0
- [x] Vendor PacketEvents api + netty-common (pinned), upstream tracker script + daily workflow
- [ ] Gradle integration of vendored PacketEvents; own platform module replaces its spigot one
- [ ] Built-in virtual plugin "packetevents" so depend: [packetevents] resolves
- [ ] Strip PacketEvents injector; feed it from core pipeline
- [ ] Mixin into connection pipeline setup (master terminates the client connection)
- [ ] Packet logger demo (real client join)
- [ ] Typed packet wrappers (sealed hierarchy)
- [ ] Listener chain: priority, cancel, modify, send/receive
- [ ] Per-player context (state, version, latency)
- [ ] Public API module for plugins
- [ ] Master-side packet merge: backends' packet streams -> one coherent client view
- [ ] Global entity ID space (no clashes between backends)

## M3 Node network
- [x] Transport interface: LocalChannel + TCP
- [ ] Message codecs on NMS StreamCodec
- [x] Node auth (Ed25519 mutual), handshake, heartbeat, reconnect (NodeClient)
- [x] Master hostable in a backend's JVM (combined mode) and as a separate process; two-process boot with Ed25519 auth + reconnect verified

## M4 Master
- [x] Node registry
- [~] World authority: serve world to booting nodes (file pull works; master-side live changes pending)
- [x] Ordered event log (sequencer): seq numbers, bounded log, per-node ack, resume/replay on reconnect (unit-tested + real boot)
- [ ] Persistence: master canonical store, backends keep local cache/WAL for owned regions

## M5 Replication
- [x] Boot pull of full world
- [~] Network updates v1: block change done and booted; entity removal (non-player deaths) implemented but not booted; spawn/inventory/chat not started
- [~] Conflict ordering (master order wins): implemented (pending-local-change rule), unit-level only for the sequencer, not exercised with a real race
- [x] Two-server demo: block set on A appears on B
- [~] Replicated vs local table: block changes + entity removals replicate; everything else local (physics noise is replicated as block changes, see docs)

## M6 Regions and presence
- [ ] Static region grid, tick ownership
- [ ] Player presence broadcast
- [ ] Edge/ghost chunks
- [ ] Player + entity handoff across borders (client never reconnects; master re-routes its packets)
- [ ] Cross-border interactions (explosions, fluids, pathing)
- [ ] Dynamic region assignment

## M7 Craft layer depth
- [ ] World, Block, Chunk
- [ ] Entity, LivingEntity, Player
- [ ] Inventory, ItemStack, meta
- [ ] Event inventory: every Bukkit/Paper event hooked (Mixin)
- [ ] Paper-only APIs (lifecycle, data components, Brigadier)
- [ ] Shared plugin state through master (economy, perms)
- [ ] Plugin remapping / CraftBukkit internals compat
- [ ] Plugin loader: Paper behaviour (plugin.yml, paper-plugin.yml, bootstrappers) but faster (parallel scan/parse, cached descriptor index, lazy class loading)

## M8 Compatibility harness
- [ ] Plugin test set (WorldEdit, LuckPerms, Vault, Essentials, ...)
- [ ] Behaviour diff against Paper
- [ ] CI runs it

## M9 Optimization
- [ ] Profiling baseline vs Paper/Leaf
- [ ] Targeted Mixin optimizations (ticking, chunks, networking)

## M10 Hardening and release
- [ ] Node failure/recovery, world backups
- [ ] Packet exploit hardening, node-link security
- [ ] Admin tooling, docs
- [ ] First tagged release

## M11 Updates
- [~] Upstream tracking: upstream/upstreams.toml + scripts/upstream.py (PacketEvents vendored; Paper, Folia, Purpur, Leaf, Spigot repos as references)
- [x] Ported-from tracking: scripts/port.py + upstream/ported/{paper.tsv,skipped.txt}
- [x] Stub generator: scripts/gen_stubs.py
- [ ] API-diff tool: new abstract methods in paper-api -> stubs
- [ ] MC 26.4 port process (re-check every Mixin target)
