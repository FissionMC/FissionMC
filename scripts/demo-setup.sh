#!/usr/bin/env bash
# Prepares run-master/, run-a/, run-b/ for the replication demo (see docs/replication-demo.md).
# Run from the repo root after:  ./gradlew :master:nodeKeys for master, backend-a and backend-b (keys must exist).
set -euo pipefail
pub() { sed -n 2p "$1"; }
mkdir -p run-master run-a run-b
[ -d run-master/world ] || { cp -r run/world run-master/world; rm -f run-master/world/session.lock; }
cat > run-master/fission.properties <<P
node.mode=master
node.listen=127.0.0.1:25600
node.security=on
node.key=fission-node.key
node.authorized=fission-authorized.keys
node.world-root=.
P
for n in a b; do
cat > run-$n/fission.properties <<P
node.mode=backend
node.id=backend-$n
node.master=127.0.0.1:25600
node.security=on
node.key=fission-node.key
node.master-public=$(pub run-master/fission-node.key)
P
done
echo "ok: master on 25600, backends use -Pport=25565 (a) / 25566 (b)"
