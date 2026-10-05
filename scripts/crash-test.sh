#!/usr/bin/env bash
# Crash-recovery test: "no acknowledged write is lost with --aof-fsync always".
#
# Each round: start RedCake with an AOF, stream INCRs through redis-cli, kill -9 the server at a random
# moment, restart it on the same AOF, and check that the recovered counter is >= the last value the
# client saw acknowledged. (>= because a write can be durable but its reply lost in the crash.)
#
# Usage: scripts/crash-test.sh [rounds] [jar] [port]
set -u
ROUNDS="${1:-5}"
JAR="${2:-target/redCake-0.0.1-SNAPSHOT.jar}"
PORT="${3:-6395}"
DIR="$(mktemp -d)"
AOF="$DIR/crash.aof"
trap 'kill $SERVER 2>/dev/null; rm -rf "$DIR"' EXIT

start_server() {
  java -jar "$JAR" --port "$PORT" --aof-file "$AOF" --aof-fsync always >"$DIR/server.log" 2>&1 &
  SERVER=$!
  for _ in $(seq 1 100); do redis-cli -p "$PORT" PING >/dev/null 2>&1 && return 0; sleep 0.1; done
  echo "server did not start"; cat "$DIR/server.log"; exit 2
}

fail=0
start_server
for round in $(seq 1 "$ROUNDS"); do
  : >"$DIR/acked"
  # stream increments; every reply line is an acknowledged counter value
  # own process group, so the whole pipeline (yes + redis-cli) can be killed together
  setsid bash -c "yes 'INCR counter' | redis-cli -p $PORT >'$DIR/acked' 2>/dev/null" &
  WRITER=$!
  sleep "0.$((RANDOM % 9 + 5))"          # 0.5 .. 1.4 s of load
  kill -9 "$SERVER" 2>/dev/null; wait "$SERVER" 2>/dev/null
  kill -9 -- "-$WRITER" 2>/dev/null; wait "$WRITER" 2>/dev/null

  last_acked="$(grep -E '^[0-9]+$' "$DIR/acked" | tail -n 1)"
  last_acked="${last_acked:-0}"

  start_server
  recovered="$(redis-cli -p "$PORT" GET counter)"
  recovered="${recovered:-0}"
  if [ "$recovered" -ge "$last_acked" ]; then
    echo "round $round: ok   (last acknowledged $last_acked, recovered $recovered)"
  else
    echo "round $round: LOST ACKNOWLEDGED WRITES (last acknowledged $last_acked, recovered $recovered)"
    fail=1
  fi
done
exit $fail
