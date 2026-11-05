# Replication demo: master + two backends on one machine

What it shows: a block set on backend A appears on backend B, ordered by the master. A late-joining backend replays history.

Run all commands from the repo root; Gradle through the distrobox as usual (`... gradlew <task> --no-daemon -q`).
Ports: master 25600 (nodes), backend A 25565, backend B 25566. Run dirs `run-master/`, `run-a/`, `run-b/` are gitignored.

## 1. Keys and config (once)
```
gradlew :master:nodeKeys -Pkeyfile=run-master/fission-node.key -Pnode=master
gradlew :master:nodeKeys -Pkeyfile=run-a/fission-node.key -Pnode=backend-a -Pauthorized=run-master/fission-authorized.keys
gradlew :master:nodeKeys -Pkeyfile=run-b/fission-node.key -Pnode=backend-b -Pauthorized=run-master/fission-authorized.keys
bash scripts/demo-setup.sh        # copies run/world to run-master/world, writes the three fission.properties
```

## 2. Start (each in its own terminal, in this order)
```
gradlew :master:run
gradlew :backend:run -PrunDir=run-a -Pport=25565
gradlew :backend:run -PrunDir=run-b -Pport=25566
```
Each backend logs `Connected to master`, pulls the world into its own `world/`, `[fission] replication enabled`, then `Done (`.
The master prints `backend-a joined` / `backend-b joined`.

## 3. Make a change
The target chunk must be loaded on both sides; on both consoles first: `forceload add 0 0`. Then on A's console:
```
setblock 0 100 0 minecraft:gold_block
```
B's console shows:
```
[fission] applied seq=7 BlockChange from backend-a minecraft:overworld 0,100,0 -> Block{minecraft:gold_block} (readback ok)
```
`readback ok` means B read the block back from its own world after applying. The same works from B to A.

## 4. Late join / reconnect
Stop B, change blocks on A, start B again: B pulls the (static) master world and then replays the master's whole event log in order (`applied seq=1 ...`). After a plain network drop B resumes from its last applied seq.
If the master process restarts, its log restarts (new epoch); backends log a warning that they may have diverged and should be restarted.

## Notes
- Only block changes and (untested in a boot) non-player entity deaths replicate. Everything the world does by itself (fluids, bubble columns, ...) is replicated as the resulting block changes, which is noisy but converges.
- Applied remote changes use flags UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS: no neighbour updates, no drops, and a thread-local guard keeps them from being reported again.
- Wire ids 32-37 are replication messages (`protocol/.../ReplicationMessages.kt`).
