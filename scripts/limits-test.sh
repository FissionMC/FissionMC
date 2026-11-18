#!/usr/bin/env bash
# Scripted run for the node data limits (protocol/NODE-LIMITS.md): master + backend-a + backend-b through .dev/scripts/dev-run-2.sh on OWN test ports
# (master nodes 25790, clients 25791, backends 25585 / 25586). Checks, with the FissionTest plugin (LimitsProbe):
#   1. a 20 MiB plugin message from backend-a reaches backend-b byte for byte (sha256) through the master, while small messages keep flowing (ping latency)
#   2. a 20 MiB message to the master arrives intact (the master logs size and sha256 of channel fission:selftest)
#   3. an item stack of 300 KB saved data crosses the region border (ItemTransfer, was refused over 64 KiB)
#   4. no link timed out / dropped
#   scripts/limits-test.sh <scratch dir>
# Uses run-master, run-a, run-b of THIS checkout (all ignored by git) and rewrites their fission.yml, security off (loopback only). Starts the three servers itself
# (stopped by PID at the end), waits with scripts/LogWait.java and scripts/PidWait.java (no shell waits).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCR="${1:?usage: limits-test.sh <scratch dir>}"
JAVA_HOME_BOX=/usr/lib/jvm/java-25-openjdk
box() { if command -v distrobox >/dev/null 2>&1; then distrobox enter claude -- bash -lc "$1"; else bash -lc "$1"; fi; }
A_LOG="$ROOT/run-a/backend.log"; B_LOG="$ROOT/run-b/backend.log"; M_LOG="$ROOT/run-master/master.log"
waitfor() { box "cd $ROOT && $JAVA_HOME_BOX/bin/java scripts/LogWait.java '$1' '$2' $3"; }   # log regex millis -> prints the line

checks() {
  fails=0
  pass() { echo "[limits-test] PASS $1"; }; fail() { echo "[limits-test] FAIL $1"; fails=$((fails+1)); }
  field() { sed -n "s/.*$2=\([^ ]*\).*/\1/p" <<<"$1" | head -1; }
  console() { local fd="$1"; shift; eval "echo '$*' >&$fd"; }   # 8 = backend-a, 9 = backend-b

  # ---- 1. 20 MiB a -> b
  N=20971520
  console 8 "fissiontest limitssend backend-b $N"
  sent="$(waitfor "$A_LOG" "LIMITS sent backend-b $N sha256=" 60000)" || { fail "backend-a never started the send"; sent=""; }
  recv="$(waitfor "$B_LOG" "LIMITS recv backend-a $N sha256=" 120000)" || { fail "backend-b did not receive the 20 MiB message"; recv=""; }
  if [ -n "$recv" ]; then
    [ "$(field "$sent" sha256)" = "$(field "$recv" sha256)" ] && pass "20 MiB a->b intact ($(field "$recv" sha256 | cut -c1-12)...)" || fail "hash differs: sent $(field "$sent" sha256) got $(field "$recv" sha256)"
    pings="$(field "$recv" pings)"; maxp="$(field "$recv" maxPingMs)"
    [ "${pings:-0}" -ge 1 ] && [ "${maxp:--1}" -ge 0 ] && [ "${maxp:-99999}" -lt 3000 ] && pass "small messages stayed responsive during the transfer ($pings pings, slowest ${maxp} ms)" || fail "pings during the transfer: n=$pings max=${maxp} ms"
  fi
  waitfor "$A_LOG" "LIMITS written backend-b $N ok" 60000 >/dev/null && pass "sender future completed" || fail "sender future did not complete ok"

  # ---- 2. 20 MiB a -> master
  M=20971521
  console 8 "fissiontest limitssend master $M"
  sent="$(waitfor "$A_LOG" "LIMITS sent master $M sha256=" 60000)" || { fail "no send to master"; sent=""; }
  got="$(waitfor "$M_LOG" "selftest message from backend-a: $M bytes sha256=" 120000)" || { fail "master did not receive the 20 MiB message"; got=""; }
  [ -n "$got" ] && { [ "$(field "$sent" sha256)" = "$(field "$got" sha256)" ] && pass "20 MiB a->master intact" || fail "master hash differs"; }

  # ---- 3. big item across the border (a owns x<0, b owns x>=0)
  console 8 "fission debug bigitem 100 70 100 300000"
  waitfor "$B_LOG" "item transfer received from backend-a: .*bytes saved" 60000 >/dev/null && pass "item over 64 KiB crossed the region border" || fail "item transfer not received on b: $(grep -a 'item\|bigitem' "$A_LOG" | tail -2)"

  # ---- 4. links
  if grep -aqE "timed out|Lost connection to master|send buffer over|link to .*not sent" "$A_LOG" "$B_LOG"; then fail "link problems: $(grep -aE 'timed out|Lost connection|send buffer|not sent' "$A_LOG" "$B_LOG" | head -3)"; else pass "no link stalls, drops or refused sends"; fi
  echo "[limits-test] failures=$fails"
}

find_pid() {  # dir pattern : PID of the JVM with that working directory and command line
  local p; for p in /proc/[0-9]*; do [ "$(readlink "$p/cwd" 2>/dev/null)" = "$1" ] || continue; tr '\0' ' ' < "$p/cmdline" 2>/dev/null | grep -q -- "$2" && { echo "${p#/proc/}"; return 0; }; done; return 1
}
stop_pid() {  # pid : SIGTERM, wait up to 60 s, then SIGKILL
  [ -n "${1:-}" ] || return 0; kill -TERM "$1" 2>/dev/null || return 0
  box "cd $ROOT && $JAVA_HOME_BOX/bin/java scripts/PidWait.java $1 60000" || kill -KILL "$1" 2>/dev/null
}

for p in /proc/[0-9]*; do d="$(readlink "$p/cwd" 2>/dev/null)"; case "$d" in "$ROOT"/run-*) tr '\0' ' ' < "$p/cmdline" 2>/dev/null | grep -qE 'fissionmc' && { echo "a server of this checkout is running (pid ${p#/proc/}): stop it first"; exit 2; };; esac; done
mkdir -p "$SCR" "$ROOT/run-master/plugins" "$ROOT/run-a/plugins/FissionTest" "$ROOT/run-b/plugins/FissionTest"
echo "[limits-test] building the test plugin"
box "cd $ROOT && JAVA_HOME=$JAVA_HOME_BOX bash gradlew :testplugin:jar :backend:classes :backend:jar :master:jar :craft:jar :net:jar :packets:jar :loader:jar :protocol:jar :fission-api:jar :packetevents:jar :packetevents:patchJar --no-daemon -q" || { echo "build failed"; exit 3; }
cp "$ROOT"/testplugin/build/libs/*.jar "$ROOT/run-master/plugins/FissionTest.jar"
touch "$ROOT/run-a/plugins/FissionTest/limits-probe" "$ROOT/run-b/plugins/FissionTest/limits-probe"
cat > "$ROOT/run-master/fission.yml" <<YML
# written by scripts/limits-test.sh
node: { mode: master, listen: "127.0.0.1:25790", security: false, world-root: . }
client: { listen: "127.0.0.1:25791", online-mode: false, motd: "limits test" }
chunk: { store-dir: "$SCR/chunkstore" }
regions:
  default: backend-a
  dimensions:
    overworld:
      - { area: [min, min, 0, max], owner: backend-a }
      - { area: [0, min, max, max], owner: backend-b }
    the_nether:
      - { owner: backend-a }
limits: { max-plugin-message: 32M }
debug: true
YML
for n in a b; do
cat > "$ROOT/run-$n/fission.yml" <<YML
# written by scripts/limits-test.sh
node: { mode: backend, id: backend-$n, master: "127.0.0.1:25790", security: false }
limits: { max-plugin-message: 32M }
debug: true
YML
done
rm -rf "$SCR/chunkstore"
FIFO_A="$SCR/a.stdin"; FIFO_B="$SCR/b.stdin"; rm -f "$FIFO_A" "$FIFO_B"; mkfifo "$FIFO_A" "$FIFO_B"
: > "$M_LOG"; : > "$A_LOG"; : > "$B_LOG"
cleanup() { stop_pid "${PID_B:-}"; stop_pid "${PID_A:-}"; stop_pid "${PID_M:-}"; exec 8>&- 9>&-; rm -f "$FIFO_A" "$FIFO_B"; }
trap cleanup EXIT
echo "[limits-test] starting master"
box "cd $ROOT && JAVA_HOME=$JAVA_HOME_BOX bash gradlew :master:run --no-daemon -q" > "$M_LOG" 2>&1 &
waitfor "$M_LOG" "clients:" 400000 >/dev/null || { echo "master did not come up: see $M_LOG"; exit 1; }
PID_M="$(find_pid "$ROOT/run-master" MasterMainKt)"
echo "[limits-test] starting backend-a (25585)"
box "cd $ROOT && FISSION_DEBUG=1 JAVA_HOME=$JAVA_HOME_BOX bash gradlew :backend:run -PrunDir=run-a -Pport=25585 --no-daemon -q" < "$FIFO_A" > "$A_LOG" 2>&1 &
exec 8>"$FIFO_A"
waitfor "$A_LOG" 'Done \(' 600000 >/dev/null || { echo "backend-a did not report Done: see $A_LOG"; exit 1; }
PID_A="$(find_pid "$ROOT/run-a" net.fissionmc.loader.Bootstrap)"
echo "[limits-test] starting backend-b (25586)"
box "cd $ROOT && FISSION_DEBUG=1 JAVA_HOME=$JAVA_HOME_BOX bash gradlew :backend:run -PrunDir=run-b -Pport=25586 --no-daemon -q" < "$FIFO_B" > "$B_LOG" 2>&1 &
exec 9>"$FIFO_B"
waitfor "$B_LOG" 'Done \(' 600000 >/dev/null || { echo "backend-b did not report Done: see $B_LOG"; exit 1; }
PID_B="$(find_pid "$ROOT/run-b" net.fissionmc.loader.Bootstrap)"
echo "[limits-test] all up (master $PID_M, a $PID_A, b $PID_B)" | tee -a "$SCR/run.out"
checks | tee -a "$SCR/run.out"
cp "$M_LOG" "$SCR/master.log"; cp "$A_LOG" "$SCR/backend-a.log"; cp "$B_LOG" "$SCR/backend-b.log" 2>/dev/null
echo "================ results ================"
grep -a "\[limits-test\] \(PASS\|FAIL\)" "$SCR/run.out" | sed 's/^.*\[limits-test\]/[limits-test]/'
echo "PASS: $(grep -a -c '\[limits-test\] PASS' "$SCR/run.out")  FAIL: $(grep -a -c '\[limits-test\] FAIL' "$SCR/run.out")"
