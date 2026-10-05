#!/usr/bin/env bash
# Long-running load test that watches for memory growth (leaks in the expiry schedule, the AOF, ...).
# A healthy run shows used_memory / JVM heap oscillating around a constant level, and "keys with ttl"
# staying bounded.
#
# Usage: scripts/soak-test.sh [minutes] [jar] [port]      (the real soak is 1440 minutes = 24 h)
set -u
MINUTES="${1:-1}"
JAR="${2:-target/redCake-0.0.1-SNAPSHOT.jar}"
PORT="${3:-6396}"
DIR="$(mktemp -d)"
trap 'kill $SERVER 2>/dev/null; rm -rf "$DIR"' EXIT

java -XX:+UseZGC -jar "$JAR" --port "$PORT" --maxmemory 128mb --maxmemory-policy allkeys-random \
     --aof-file "$DIR/soak.aof" >"$DIR/server.log" 2>&1 &
SERVER=$!
sleep 4
CLI=(redis-cli -p "$PORT")
echo "time  used_memory  jvm_heap  keys  expiring  evicted  connected_clients"
end=$((SECONDS + MINUTES * 60))
while [ $SECONDS -lt $end ]; do
  # mixed workload: plain writes, TTL writes (the leak-prone path), reads, counters, typed values
  # (no LPUSH: redis-benchmark pushes onto ONE list forever, which is legitimate growth, not a leak)
  redis-benchmark -p "$PORT" -q -n 50000 -c 20 -t set,get,incr,hset,sadd,zadd >/dev/null 2>&1
  redis-benchmark -p "$PORT" -q -n 50000 -c 20 -r 100000 SET "ttl:__rand_int__" value EX 5 >/dev/null 2>&1
  info="$("${CLI[@]}" INFO | tr -d '\r')"
  field() { echo "$info" | grep "^$1:" | cut -d: -f2; }
  printf '%4ds %12s %9s %6s %9s %8s %6s\n' "$SECONDS" "$(field used_memory)" "$(field used_memory_jvm)" \
         "$(echo "$info" | grep -o 'db0:keys=[0-9]*' | cut -d= -f2)" \
         "$(echo "$info" | grep -o 'expires=[0-9]*' | cut -d= -f2)" "$(field evicted_keys)" "$(field connected_clients)"
  sleep 2
done
