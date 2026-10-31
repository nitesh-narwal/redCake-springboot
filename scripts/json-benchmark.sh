#!/usr/bin/env bash
# JSON document benchmark.
#
#   scripts/json-benchmark.sh [dimensions] [requests]
#
#   1. The type on its own (JsonBenchmark): parse and write speed, path operations, memory per record against a
#      conventional tree, parsing on several threads.
#   2. A real server over TCP (redis-benchmark): the same record stored as a plain string and as a JSON document,
#      read whole and by path, changed by path, with and without pipelining.
#
# SKIP_TYPE=1 leaves out the first part. Needs the packaged jar (./mvnw -DskipTests package), redis-benchmark and python3. Defaults suit a laptop with
# about 2 GB to spare: 384 dimensions, 100000 requests per scenario, each scenario three times (the median is reported).
set -eu
cd "$(dirname "$0")/.."

DIM="${1:-384}"
N="${2:-100000}"
JAR="target/redCake-0.0.1-SNAPSHOT.jar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; [ -n "${SERVER:-}" ] && kill "$SERVER" 2>/dev/null || true' EXIT

[ -f "$JAR" ] || { echo "build the jar first: ./mvnw -DskipTests package"; exit 1; }
./mvnw -o -q test-compile >/dev/null
./mvnw -o -q dependency:build-classpath -Dmdep.outputFile="$TMP/cp.txt" -Dmdep.includeScope=test >/dev/null
CP="target/test-classes:target/classes:$(cat "$TMP/cp.txt")"

filter() { grep -v -E '^[0-9:. ]+(\[main\] )?(INFO|WARN)|WARNING: Using incubator'; }

if [ -z "${SKIP_TYPE:-}" ]; then
  echo "================ the type on its own ================"
  java -Xmx1200m -cp "$CP" me.niteshh.redcake.json.JsonBenchmark "$DIM" 10000 2>&1 | filter
fi

echo
echo "================ a server over TCP ================"
PORT=$((20000 + RANDOM % 20000))
java -Xmx1g -jar "$JAR" --port "$PORT" >"$TMP/server.log" 2>&1 &
SERVER=$!
for _ in $(seq 1 100); do ss -ltn | grep -q ":$PORT " && break; sleep 0.2; done

# one record of the kind an application stores: a text, an embedding, metadata, a status
RECORD="$(python3 -I - "$DIM" <<'PY'
import json, random, sys
random.seed(1)
dim = int(sys.argv[1])
print(json.dumps({
    "id": "doc-1", "text": " ".join("word%d" % random.randrange(1000) for _ in range(300)),
    "embedding": [round(random.gauss(0, 1), 8) for _ in range(dim)],
    "meta": {"source": "web", "lang": "en", "tags": ["alpha", "beta", "gamma"], "year": 2024},
    "status": "new", "attempts": 0}, separators=(",", ":")))
PY
)"
echo "record: ${#RECORD} bytes"

bench() { # label, pipeline, command...  (one warm-up run, then the median of three)
  local label="$1" pipeline="$2"; shift 2
  local rates=()
  for run in 0 1 2 3; do
    # -q ends with "<rate> requests per second"; the --csv form puts the whole command, commas of the JSON included, in a field
    local rate
    rate="$(redis-benchmark -p "$PORT" -c 50 -n "$N" -P "$pipeline" -r 5000 -q "$@" 2>/dev/null | tr '\r' '\n' \
            | grep -o -E '[0-9]+\.[0-9]+ requests per second' | tail -1 | awk '{print int($1)}')"
    [ "$run" -gt 0 ] && rates+=("$rate")
  done
  printf '%s\n' "${rates[@]}" | sort -n | awk -v label="$label" -v p="$pipeline" 'NR==2{printf "  %-46s pipeline %-3s %10d requests/s\n", label, p, $1}'
}

# fill the keys the reads and the changes work on
redis-benchmark -p "$PORT" -c 50 -n 20000 -r 5000 -q SET "str:__rand_int__" "$RECORD" >/dev/null
redis-benchmark -p "$PORT" -c 50 -n 20000 -r 5000 -q JSON.SET "doc:__rand_int__" '$' "$RECORD" >/dev/null

for P in 1 16; do
  echo "--- pipeline $P"
  bench "SET  (the record as one string)"          "$P" SET "str:__rand_int__" "$RECORD"
  bench "JSON.SET  (the record as a document)"     "$P" JSON.SET "doc:__rand_int__" '$' "$RECORD"
  bench "GET  (the whole string)"                  "$P" GET "str:__rand_int__"
  bench "JSON.GET  (the whole document)"           "$P" JSON.GET "doc:__rand_int__"
  bench "JSON.GET  \$.status"                      "$P" JSON.GET "doc:__rand_int__" '$.status'
  bench "JSON.GET  \$.meta.tags"                   "$P" JSON.GET "doc:__rand_int__" '$.meta.tags'
  bench "JSON.SET  \$.status (change one field)"   "$P" JSON.SET "doc:__rand_int__" '$.status' '"done"'
  bench "JSON.NUMINCRBY  \$.attempts"              "$P" JSON.NUMINCRBY "doc:__rand_int__" '$.attempts' 1
done
