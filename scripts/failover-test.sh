#!/usr/bin/env bash
# Failover test with real processes: three RedCake nodes + an EXISTING ZooKeeper.
#
#   1. the nodes elect exactly one primary, writes replicate
#   2. kill -9 the primary          -> a replica takes over, no acknowledged write is lost, the other replica
#                                      resumes with a PARTIAL resync
#   3. FAILOVER (planned)           -> the primary role moves in well under a second
#   4. SIGSTOP the primary (a frozen process / long GC pause) for longer than the session timeout, let a successor
#      take over, SIGCONT -> the woken-up old primary REFUSES writes (-CLUSTERDOWN) and rejoins as a replica
#   5. SIGTERM the primary          -> graceful hand-over
#
# Needs: java, redis-cli, curl, bc; a ZooKeeper reachable at $ZK_CONNECT (e.g. docker run -p 2181:2181 zookeeper:3.9).
# Usage: scripts/failover-test.sh [jar]      ZK_CONNECT=host:2181 (default 127.0.0.1:2181)
#                                            ZK_TICK_MS=2000 (the ensemble's tickTime; only used for the time limits)
set -u
JAR="${1:-target/redCake-0.0.1-SNAPSHOT.jar}"
ZK="${ZK_CONNECT:-127.0.0.1:2181}"
CLUSTER="failover-test-$$"
ZK_TICK_MS="${ZK_TICK_MS:-2000}"
SESSION_MS=3000
# ZooKeeper grants at least 2 ticks, and expires sessions on tick boundaries (up to one tick late).
EFFECTIVE_MS=$(( SESSION_MS > 2 * ZK_TICK_MS ? SESSION_MS : 2 * ZK_TICK_MS ))
PORTS=(7101 7102 7103)
METRICS=(9201 9202 9203)
DIR="$(mktemp -d)"
declare -A PID
trap 'for p in "${PORTS[@]}"; do [ -n "${PID[$p]:-}" ] && kill -CONT "${PID[$p]}" 2>/dev/null; [ -n "${PID[$p]:-}" ] && kill "${PID[$p]}" 2>/dev/null; done; rm -rf "$DIR"' EXIT

pass=0; fail=0
check() { # check "description" command...
  local what="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "PASS  $what"; pass=$((pass + 1)); else echo "FAIL  $what"; fail=$((fail + 1)); fi
}
cli() { local port="$1"; shift; redis-cli -p "$port" "$@" 2>&1; }
state() { cli "$1" INFO cluster | tr -d '\r' | sed -n 's/^cluster_state://p'; }
epoch() { cli "$1" INFO cluster | tr -d '\r' | sed -n 's/^cluster_epoch://p'; }

start_node() { # start_node index
  local i="$1" port="${PORTS[$1]}"
  java -jar "$JAR" --port "$port" --metrics-port "${METRICS[$i]}" --aof-file "$DIR/n$i.aof" \
    --zk-connect "$ZK" --cluster-name "$CLUSTER" --advertise-host 127.0.0.1 \
    --zk-session-timeout-ms "$SESSION_MS" >"$DIR/n$i.log" 2>&1 &
  PID[$port]=$!
}

# prints the port of the single node that is leading, waiting up to $2 seconds (default 30)
wait_for_leader() {
  local deadline=$((SECONDS + ${2:-30})) p leaders
  while [ $SECONDS -lt $deadline ]; do
    leaders=()
    for p in $1; do [ "$(state "$p")" = "leading" ] && leaders+=("$p"); done
    if [ "${#leaders[@]}" -eq 1 ]; then echo "${leaders[0]}"; return 0; fi
    sleep 0.2
  done
  return 1
}

live_ports() { local out=(); for p in "${PORTS[@]}"; do kill -0 "${PID[$p]:-0}" 2>/dev/null && out+=("$p"); done; echo "${out[@]}"; }
others() { local leader="$1" out=(); for p in $(live_ports); do [ "$p" != "$leader" ] && out+=("$p"); done; echo "${out[@]}"; }

for i in 0 1 2; do start_node "$i"; done
LEADER="$(wait_for_leader "${PORTS[*]}")" || { echo "no leader elected"; tail -n 20 "$DIR"/n0.log; exit 2; }
echo "primary: $LEADER (epoch $(epoch "$LEADER"))"
check "exactly one leader and the others follow" test "$(for p in $(others "$LEADER"); do state "$p"; done | grep -c following)" = 2
check "write on the primary" test "$(cli "$LEADER" SET k1 v1)" = "OK"
cli "$LEADER" SET counter 0 >/dev/null; for _ in $(seq 1 50); do cli "$LEADER" INCR counter >/dev/null; done
check "replicas acknowledged" test "$(cli "$LEADER" WAIT 2 3000)" = "2"
FOLLOWER="$(others "$LEADER" | awk '{print $1}')"
REPLY="$(cli "$FOLLOWER" SET x 1)"
check "write on a replica is refused with a hint ($REPLY)" bash -c "echo '$REPLY' | grep -q 'primary is 127.0.0.1:$LEADER'"
REPLY="$(cli "$FOLLOWER" REPLICAOF NO ONE)"
check "REPLICAOF is refused in cluster mode" bash -c "echo '$REPLY' | grep -q disabled"

echo "--- 2. kill -9 the primary"
T0=$(date +%s.%N); kill -9 "${PID[$LEADER]}"; DEAD="$LEADER"
LEADER="$(wait_for_leader "$(others "$DEAD")" 60)" || { echo "no successor"; exit 2; }
T1=$(date +%s.%N)
echo "successor: $LEADER after $(echo "$T1 - $T0" | bc) s (session timeout $((EFFECTIVE_MS / 1000)) s, tickTime $((ZK_TICK_MS / 1000)) s)"
check "failover took less than session timeout + tickTime + 4 s" \
  test "$(echo "$T1 - $T0 < ($EFFECTIVE_MS + $ZK_TICK_MS) / 1000 + 4" | bc)" = 1
check "no acknowledged write lost" test "$(cli "$LEADER" GET counter)" = "50"
check "epoch increased" test "$(epoch "$LEADER")" -ge 2
check "new primary accepts writes" test "$(cli "$LEADER" SET k2 v2)" = "OK"
OTHER="$(others "$LEADER" | awk '{print $1}')"
sleep 1.5
check "other replica resumed with a partial resync" grep -q "Partial resync with primary 127.0.0.1:$LEADER" "$DIR/n$(( ${OTHER} - 7101 )).log"
check "other replica follows the new primary" test "$(cli "$OTHER" GET k2)" = "v2"

echo "--- restart the killed node: it rejoins as a replica"
start_node "$((DEAD - 7101))"
for _ in $(seq 1 100); do [ "$(state "$DEAD")" = "following" ] && break; sleep 0.2; done
check "restarted node follows the primary" test "$(state "$DEAD")" = "following"
sleep 1
check "restarted node has the data" test "$(cli "$DEAD" GET k2)" = "v2"

echo "--- 3. planned FAILOVER"
TARGET="$(others "$LEADER" | awk '{print $1}')"
T0=$(date +%s.%N)
check "FAILOVER accepted" test "$(cli "$LEADER" FAILOVER TO 127.0.0.1 "$TARGET")" = "OK"
for _ in $(seq 1 100); do [ "$(state "$TARGET")" = "leading" ] && break; sleep 0.1; done
T1=$(date +%s.%N)
check "target leads after less than 2 s" test "$(echo "$T1 - $T0 < 2" | bc)" = 1
OLD="$LEADER"; LEADER="$TARGET"
check "old primary now follows" bash -c "for _ in \$(seq 1 50); do [ \"\$(redis-cli -p $OLD INFO cluster | tr -d '\r' | sed -n 's/^cluster_state://p')\" = following ] && exit 0; sleep 0.1; done; exit 1"

echo "--- 4. frozen primary (SIGSTOP) while a successor is elected"
FROZEN="$LEADER"
kill -STOP "${PID[$FROZEN]}"
SUCCESSOR="$(wait_for_leader "$(others "$FROZEN")" 60)" || { kill -CONT "${PID[$FROZEN]}"; echo "no successor"; exit 2; }
echo "successor while frozen: $SUCCESSOR"
cli "$SUCCESSOR" SET written-while-frozen yes >/dev/null
kill -CONT "${PID[$FROZEN]}"
REPLY="$(cli "$FROZEN" SET zombie-write 1)"
check "woken-up old primary refuses the write ($REPLY)" bash -c "echo '$REPLY' | grep -qE 'CLUSTERDOWN|READONLY'"
sleep 4
check "old primary now follows the successor" test "$(state "$FROZEN")" = "following"
check "zombie write is nowhere" test "$(cli "$SUCCESSOR" GET zombie-write)" = ""
check "old primary has the write made while it was frozen" test "$(cli "$FROZEN" GET written-while-frozen)" = "yes"
LEADER="$SUCCESSOR"

echo "--- 5. SIGTERM the primary (graceful hand-over)"
T0=$(date +%s.%N); kill -TERM "${PID[$LEADER]}"; GONE="$LEADER"
LEADER="$(wait_for_leader "$(others "$GONE")" 30)" || { echo "no successor"; exit 2; }
T1=$(date +%s.%N)
check "hand-over took less than the session timeout" test "$(echo "$T1 - $T0 < $EFFECTIVE_MS / 1000" | bc)" = 1
check "data survived" test "$(cli "$LEADER" GET k2)" = "v2"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
