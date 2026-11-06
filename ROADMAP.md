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
- [~] Gradle integration of vendored PacketEvents (`:packetevents`; generated mapping resources committed under `packetevents/resources`, see upstream/packetevents-resources.md); own platform class `FissionPacketEventsAPI` in `:packets` replaces the spigot injector
- [x] Built-in virtual plugin "packetevents" so depend: [packetevents] resolves (generated descriptor jar, boot-verified: loads and enables)
- [ ] Strip PacketEvents injector; feed it from core pipeline
- [~] Master terminates the client connection (Netty listener, status/login/encryption/compression/limbo coded, status/login/online-auth/limbo flows unit-tested; limbo configuration is a recording of the real backend (registries + tags); limbo play frames decoded by vanilla's own codec in a backend boot; first real-client run failed on missing tags (fixed, retest pending))
- [~] Seamless backend switch (no "Reconfiguring"): master completes each backend's configuration as a virtual client, hash-checks it against the recorded one, forwards the backend's Login in PLAY, cleans tab/bossbars; limbo->backend, backend->backend (`/fission send`, admin), backend death->limbo; scripted 3-client/2-backend run passes, vanilla decoders replay 12k frames clean; NOT yet run with a real client (design: )
- [~] Global/local chat: master `/chat global|local|toggle` (tab-complete via patched command tree), global = unsigned system line built by the master (`chat.global.*` keys), sender backend fires the real Paper chat events with an empty audience and may cancel/modify, other backends get a monitor-only copy, limbo silent; scripted run + unit tests pass; real client and signed (online-mode) chat unverified; found and fixed: ANY chat message used to kick the player (ChatDecorator stub)
- [~] MiniMessage chat formats: `chat.format.syntax=minimessage|legacy`, `chat.global.format` (master) and NEW `chat.local.format` (pushed to the backends, wire 82, rendered there as the default Paper chat renderer); one `ChatTemplate` for both sides, `<head:{uuid}>` object component, `&r` and legacy codes inside MiniMessage templates, player text can never inject; shared vectors pass on adventure 4.26.1 (master) and 5.2.0 (backend); real client unverified
- [x] Config moved from `fission.properties` to YAML `fission.yml` (SnakeYAML, nested sections, strict: unknown keys / wrong types / invalid region map stop the process with the key path; commented defaults `fission.default.yml` / `fission.backend.default.yml` written when the file is missing; no migration layer converts dev files once has the old-key table); `ConfigFilesTest` and `switch-test.sh` run on YAML
- [~] Master-owned player data (position/inventory follow the player across backends): world pull keeps backend-local player files; master store `run-master/playerdata`, wire ids 57-59, backend load/save mixin, flush before a switch; unit tests and the scripted two-backend run pass (same coordinates + inventory after /fission send, reconnect restores); real client pending. Admins (uuid keyed) are operator level 4 on every backend, see the same note
- [~] Chunk authority: master chunk store, generation leases, backends pull on load and push on save (wire 64-69), boot pull no longer touches region/entities/poi, `importWorld` seeds the store; scripted two-backend run: 900 chunks generated once, second backend generated nothing, 100/100 pulled chunks identical to the store; pregen, region-owner leases and entity/poi timing are design notes only, real client untested
- [~] Traveling entities and stats: tamed pets, riders on the player, mounts, shoulder entities, stats and advancements follow a player through every switch; full NBT diff of 9 subjects in the scripted run is clean (allowlist documented); real client untested. Phase 2 (static regions) not started
- [x] Network-wide join/leave lines from the master (`messages.*`), backends silent for relayed players
- [ ] Packet logger demo (real client join)
- [ ] Typed packet wrappers (sealed hierarchy)
- [~] Listener chain: `PacketBus` + `PacketTap` over PacketEvents' EventManager, wired in master limbo phases, master relay (both directions) and backend virtual connection (both directions); `PacketTapTest` covers both directions, cancel and modify; backend boot shows the tap initialises
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
- [x] Static region grid, tick ownership (`regions.*` in fission.yml, validated at master start; ghost chunks are not ticked/spawned in)
- [~] Player presence broadcast (backends report player positions to the master, 2 block granularity; not broadcast to other players/backends)
- [~] Edge/ghost chunks (ghost chunks reach the client from the non-owner, block changes replicate into them; foreign entities in view and cross-border block updates missing)
- [~] Player + entity handoff across borders (player: seamless switch with hysteresis, pets/riders travel; items/xp/mobs handed over with ack; projectiles, leashes, vehicles not)
- [~] Seamless backend switch without the loading screen (`switch.mode: seamless`, default): backend-to-backend switches and region border crossings keep the client's level and player (no Login/Respawn/StartConfiguration): per-client entity id map + byte-level rewriter driven by a mechanically generated id table and checked against vanilla's codecs over ~150k recorded frames, cut-over in one bundle, chunk bookkeeping, PlayerLoaded from the master; limbo to first backend and dimension/seed changes still use Login+Respawn; scripted region-test passes, real client unverified
- [ ] Cross-border interactions (explosions, fluids, pathing, redstone, block entities)
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
