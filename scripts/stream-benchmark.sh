#!/usr/bin/env bash
# Streams benchmark.
#
#   scripts/stream-benchmark.sh [entries] [jobs]
#
#   1. Memory per entry (StreamBenchmark memory): RedCake's block log against a TreeMap of String arrays, and against Redis
#      (MEMORY USAGE) when a redis-server is available.
#   2. The data structure alone (StreamBenchmark speed): adding, range reads, delivering and acknowledging, trimming.
#   3. A job queue over TCP (StreamBenchmark queue): XADD, XREADGROUP COUNT 10 BLOCK, XACK with several workers,
#      then the delay between an XADD and the delivery to a worker that was waiting - against the packaged jar and, when
#      there is a redis-server, against that as well, with the same client.
#
# Needs the packaged jar (./mvnw -DskipTests package). A redis-server is found on the PATH or in $REDIS_SERVER; without
# one the comparison columns are left out. Defaults suit a laptop with about 2 GB to spare: 100000 entries, 20000 jobs.
set -eu
cd "$(dirname "$0")/.."

ENTRIES="${1:-100000}"
JOBS="${2:-20000}"
JAR="target/redCake-0.0.1-SNAPSHOT.jar"
TMP="$(mktemp -d)"
PIDS=()
trap 'rm -rf "$TMP"; for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done' EXIT

[ -f "$JAR" ] || { echo "build the jar first: ./mvnw -DskipTests package"; exit 1; }
./mvnw -o -q test-compile >/dev/null
./mvnw -o -q dependency:build-classpath -Dmdep.outputFile="$TMP/cp.txt" -Dmdep.includeScope=test >/dev/null
CP="target/test-classes:target/classes:$(cat "$TMP/cp.txt")"
BENCH="me.niteshh.redcake.stream.StreamBenchmark"

filter() { grep -v -E '^[0-9:. ]+(\[main\] )?(INFO|WARN)|WARNING: Using incubator'; }
free_port() { echo $((20000 + RANDOM % 20000)); }
wait_port() { for _ in $(seq 1 100); do ss -ltn | grep -q ":$1 " && return 0; sleep 0.2; done; return 1; }

REDIS="${REDIS_SERVER:-$(command -v redis-server || true)}"
REDIS_PORT=""
if [ -n "$REDIS" ]; then
  REDIS_PORT="$(free_port)"
  "$REDIS" --port "$REDIS_PORT" --save "" --appendonly no --bind 127.0.0.1 >"$TMP/redis.log" 2>&1 &
  PIDS+=("$!")
  wait_port "$REDIS_PORT" || { echo "redis-server did not start"; exit 1; }
  echo "comparing with: $("$REDIS" --version | cut -d' ' -f3)"
fi

echo "================ memory ================"
java -Xmx2g -cp "$CP" "$BENCH" memory "$ENTRIES" ${REDIS_PORT:+127.0.0.1:$REDIS_PORT} 2>&1 | filter

echo
echo "================ the data structure alone ================"
java -Xmx2g -cp "$CP" "$BENCH" speed 1000000 2>&1 | filter

PORT="$(free_port)"
java -Xmx1g -jar "$JAR" --port "$PORT" >"$TMP/server.log" 2>&1 &
PIDS+=("$!")
wait_port "$PORT" || { echo "the server did not start"; exit 1; }
echo
echo "================ a job queue over TCP: RedCake ================"
java -cp "$CP" "$BENCH" queue 127.0.0.1 "$PORT" "$JOBS" 8 2>&1 | filter
if [ -n "$REDIS_PORT" ]; then
  echo
  echo "================ a job queue over TCP: Redis ================"
  java -cp "$CP" "$BENCH" queue 127.0.0.1 "$REDIS_PORT" "$JOBS" 8 2>&1 | filter
fi
