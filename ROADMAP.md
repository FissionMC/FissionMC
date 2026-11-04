# FissionMC roadmap

Status tracker only. `[x]` done, `[~]` in progress, `[ ]` not started. Rationale lives in project memory.

## M0 Foundation
- [x] Gradle multi-module scaffold
- [x] Mixin loader (custom class loader + Mixin service)
- [x] Vanilla 26.3 boots through loader
- [x] CraftServer skeleton (throwing stubs)

## M1 Plugin bring-up
- [ ] Mixin on server init creates CraftServer, Bukkit.setServer
- [ ] Name/version/logger/plugin folder
- [ ] PluginManager + plugin loading from plugins/
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
- [ ] Transport interface: LocalChannel + TCP
- [ ] Message codecs on NMS StreamCodec
- [ ] Node auth (keypairs), handshake, heartbeat, reconnect
- [ ] Master hostable in a backend's JVM

## M4 Master
- [ ] Node registry
- [ ] World authority: serve world to booting nodes
- [ ] Ordered event log (sequencer)
- [ ] Persistence: master canonical store, backends keep local cache/WAL for owned regions

## M5 Replication
- [ ] Boot pull of full world
- [ ] Network updates v1: block change, entity kill/spawn, inventory
- [ ] Conflict ordering (master order wins)
- [ ] Two-server demo: break block on A, appears on B
- [ ] Replicated vs local table (redstone, fluids, lighting, AI)

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
- [ ] API-diff tool: new abstract methods in paper-api -> throwing stubs in Craft layer
- [ ] Per-file ported-from markers for code ported from Paper
- [ ] MC 26.4 port process (re-check every Mixin target)
