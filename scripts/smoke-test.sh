#!/usr/bin/env bash
# End-to-end check of a RUNNING RedCake with redis-cli.
# Usage: scripts/smoke-test.sh [port] [api-key]
set -u
PORT="${1:-6379}"
KEY="${2:-}"
CLI=(redis-cli -p "$PORT")
[ -n "$KEY" ] && CLI+=(-a "$KEY" --no-auth-warning)

pass=0; fail=0
check() { # check <description> <expected> <command...>
  local desc="$1" expected="$2"; shift 2
  local actual; actual="$("${CLI[@]}" "$@" 2>&1)"
  if [ "$actual" == "$expected" ]; then pass=$((pass+1)); echo "ok   $desc"
  else fail=$((fail+1)); echo "FAIL $desc: expected [$expected] got [$actual]"; fi
}

"${CLI[@]}" FLUSHALL >/dev/null
check "PING"                    "PONG"  PING
check "ECHO"                    "hi"    ECHO hi
check "SET"                     "OK"    SET a 1
check "GET"                     "1"     GET a
check "SET NX on existing"      ""      SET a 2 NX
check "INCR"                    "2"     INCR a
check "INCRBY"                  "12"    INCRBY a 10
check "DECR"                    "11"    DECR a
check "DECRBY"                  "6"     DECRBY a 5
check "APPEND"                  "3"     APPEND s abc
check "STRLEN"                  "3"     STRLEN s
check "MSET"                    "OK"    MSET x 1 y 2
check "EXISTS"                  "2"     EXISTS x y zzz
check "EXPIRE"                  "1"     EXPIRE x 100
check "TTL (rounded)"           "100"   TTL x
check "PERSIST"                 "1"     PERSIST x
check "TTL no expiry"           "-1"    TTL x
check "EXPIRE 0 deletes"        "1"     EXPIRE y 0
check "GET deleted"             ""      GET y
check "RENAME"                  "OK"    RENAME x z
check "TYPE"                    "string" TYPE z
check "DEL"                     "1"     DEL z
check "unknown command error"   "ERR unknown command 'NOPE'" NOPE
check "arity error"             "ERR wrong number of arguments for 'get' command" GET
check "INCR non-integer error"  "ERR value is not an integer or out of range" INCR s
check "SELECT 0"                "OK"    SELECT 0

# ---- data types, transactions, introspection (added in 0.2.0)
check "HSET"                    "2"     HSET h a 1 b 2
check "HGET"                    "1"     HGET h a
check "HINCRBY"                 "6"     HINCRBY h a 5
check "HLEN"                    "2"     HLEN h
check "RPUSH"                   "3"     RPUSH l x y z
check "LPOP"                    "x"     LPOP l
check "LLEN"                    "2"     LLEN l
check "SADD"                    "2"     SADD tagset m1 m2
check "SISMEMBER"               "1"     SISMEMBER tagset m1
check "ZADD"                    "2"     ZADD z 1 a 2 b
check "ZSCORE"                  "2"     ZSCORE z b
check "ZRANK"                   "0"     ZRANK z a
check "TYPE hash"               "hash"  TYPE h
check "TYPE zset"               "zset"  TYPE z
check "WRONGTYPE error"         "WRONGTYPE Operation against a key holding the wrong kind of value" GET h
"${CLI[@]}" SET s2 abcd >/dev/null
check "GETRANGE (after SET)"    "bc"    GETRANGE s2 1 2
check "MSETNX"                  "1"     MSETNX n1 a n2 b
role="$("${CLI[@]}" ROLE | head -n 1)"
if [ "$role" == "master" ] || [ "$role" == "slave" ]; then pass=$((pass+1)); echo "ok   ROLE"; else fail=$((fail+1)); echo "FAIL ROLE: got [$role]"; fi
count="$("${CLI[@]}" COMMAND COUNT)"
if [ "${count:-0}" -gt 50 ]; then pass=$((pass+1)); echo "ok   COMMAND COUNT ($count)"; else fail=$((fail+1)); echo "FAIL COMMAND COUNT: got [$count]"; fi
tx="$(printf 'MULTI\nSET t 1\nINCR t\nEXEC\n' | "${CLI[@]}" | tail -n 1)"
if [ "$tx" == "2" ]; then pass=$((pass+1)); echo "ok   MULTI/EXEC"; else fail=$((fail+1)); echo "FAIL MULTI/EXEC: got [$tx]"; fi

echo "---- $pass passed, $fail failed"
[ "$fail" -eq 0 ]
