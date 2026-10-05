#!/usr/bin/env bash
# Throughput benchmark with redis-benchmark. Usage: scripts/benchmark.sh [port] [requests]
PORT="${1:-6390}"
N="${2:-500000}"
run() { echo "== $*"; redis-benchmark -p "$PORT" -q "$@" 2>&1 | tr '\r' '\n' | grep "requests per second" | tail -n 5; }

run -t set,get,incr -n "$N" -c 50
run -t set,get,incr -n "$N" -c 50 -P 16          # pipelined: shows flush-on-drain benefit
run -t get          -n "$N" -c 2000              # many concurrent connections
run -t set,get      -n "$N" -c 50 -d 1024        # 1 KiB values
