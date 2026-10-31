#!/usr/bin/env bash
# Vector search benchmark.
#
#   scripts/vector-benchmark.sh [dim] [count] [NOQUANT|Q8|BIN]
#
#   1. The index alone (VectorBenchmark): build rate, memory per vector, search latency / throughput / recall for
#      several exploration factors, scaling with threads, searching while inserting, dump and reload time.
#      Run twice - with the SIMD kernel (JVM option --add-modules jdk.incubator.vector) and with the portable one.
#   2. A real server over TCP (VectorLoad): bulk load, VSIM throughput with 1-8 clients, insert rate with several
#      clients, and what a stream of VADD does to the latency of an ordinary SET.
#
# Needs the packaged jar (./mvnw -DskipTests package) and the compiled tests (the script compiles them).
# Defaults are sized for a laptop: 384 dimensions, 50000 vectors, a few minutes in all.
set -eu
cd "$(dirname "$0")/.."

DIM="${1:-384}"
COUNT="${2:-50000}"
QUANT="${3:-Q8}"
JAR="target/redCake-0.0.1-SNAPSHOT.jar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; [ -n "${SERVER:-}" ] && kill "$SERVER" 2>/dev/null || true' EXIT

[ -f "$JAR" ] || { echo "build the jar first: ./mvnw -DskipTests package"; exit 1; }
./mvnw -o -q test-compile >/dev/null
./mvnw -o -q dependency:build-classpath -Dmdep.outputFile="$TMP/cp.txt" -Dmdep.includeScope=test >/dev/null
CP="target/test-classes:target/classes:$(cat "$TMP/cp.txt")"

if java --list-modules | grep -q '^jdk.incubator.vector'; then
  SIMD="--add-modules jdk.incubator.vector"
else
  SIMD=""
  echo "(this JVM has no jdk.incubator.vector: only the portable kernel can be measured)"
fi

filter() { grep -v -E '^[0-9:. ]+(\[main\] )?(INFO|WARN)|WARNING: Using incubator'; }

echo "================ index only ================"
for MODE in simd scalar; do
  [ "$MODE" = simd ] && [ -z "$SIMD" ] && continue
  echo "---- kernel: $MODE"
  java $SIMD -Xmx2g -Dredcake.vector.kernel="$MODE" -cp "$CP" me.niteshh.redcake.vector.VectorBenchmark \
       "$DIM" "$COUNT" "$QUANT" 500 2>&1 | filter
done

echo "================ server over TCP ================"
for MODE in simd scalar; do
  [ "$MODE" = simd ] && [ -z "$SIMD" ] && continue
  PORT=$((20000 + RANDOM % 20000))
  FLAGS=""
  [ "$MODE" = simd ] && FLAGS="$SIMD"
  java -Xmx2g $FLAGS -jar "$JAR" --port "$PORT" >"$TMP/server-$MODE.log" 2>&1 &
  SERVER=$!
  for _ in $(seq 1 100); do ss -ltn | grep -q ":$PORT " && break; sleep 0.2; done
  echo "---- server kernel: $(grep -o 'Vector search kernel: .*' "$TMP/server-$MODE.log" | head -1)"
  java -Xmx512m -cp "$CP" me.niteshh.redcake.vector.VectorLoad 127.0.0.1 "$PORT" "$DIM" "$((COUNT < 30000 ? COUNT : 30000))" 2>&1 | filter
  kill "$SERVER"; wait "$SERVER" 2>/dev/null || true
  SERVER=""
done
