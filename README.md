# RedCake

RedCake is a Redis-compatible in-memory data store written in Java 21 (virtual threads) on Spring Boot (used only
for dependency injection and lifecycle). It speaks RESP over TCP, so `redis-cli`, `redis-benchmark` and most Redis
client libraries can talk to it.

**Status: experimental.** Suitable for learning, local development, protocol experiments and benchmarking. Not a
drop-in production Redis: no cluster, no automatic failover, RESP2 only, collection commands are O(n) in places.
See `Task.md` for the audit, the roadmap and the progress log, `SECURITY.md` for the threat model and `docs/adr/`
for the design decisions.

## Quick start

```bash
./mvnw clean package
java -jar target/redCake-0.0.1-SNAPSHOT.jar                         # primary on 127.0.0.1:6379
java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6381 --replicaof 127.0.0.1 6379
redis-cli -p 6379 HSET user:1 name Nitesh
redis-cli -p 6381 HGETALL user:1                                      # served by the replica
scripts/smoke-test.sh 6379                                            # ~46 end-to-end checks
java -jar target/redCake-0.0.1-SNAPSHOT.jar --help                    # every option
```

Docker: `REDCAKE_API_KEY=change-me docker compose up --build` starts one primary and two replicas;
`docker compose --profile monitoring up` adds Prometheus and Grafana (import `deploy/grafana-dashboard.json`).

## Options

Options can also be written in a file (`--config redcake.conf`, see `deploy/redcake.conf.example`); the command line wins.

| Area | Option | Meaning (default) |
|---|---|---|
| Network | `--port N`, `--bind ADDR` | listener (6379, 127.0.0.1) |
| | `--max-clients N`, `--timeout S` | client cap (10000), idle disconnect (0 = never; unauthenticated connections always 15 s) |
| Security | `REDCAKE_API_KEY`, `--api-key-file`, `--api-key` | require `AUTH` (prefer env/file: argv is visible in `ps`) |
| | `--acl-file PATH`, `--hash-password` | named users with roles; hash a password read from stdin |
| | `--allow-insecure` | permit a non-loopback bind without credentials (refused by default) |
| | `--tls-keystore`, `--tls-truststore`, `--tls-*-password-file`, `--tls-client-auth`, `--tls-replication` | TLS, mutual TLS, TLS replication |
| Persistence | `--aof-file PATH`, `--aof-fsync always\|everysec\|no` | append-only file (off), durability (everysec) |
| Memory | `--maxmemory SIZE`, `--maxmemory-policy noeviction\|allkeys-random\|volatile-ttl` | limit (0 = none), policy (noeviction) |
| Replication | `--replicaof HOST PORT`, `--repl-backlog-size SIZE` | follow a primary, partial-resync buffer (1mb) |
| Ops | `--metrics-port N`, `--slowlog-threshold-us N`, `--config PATH` | Prometheus endpoint (off), slow log (10000), config file |

Startup rejects unknown options, missing values, duplicates and out-of-range numbers with one readable line (exit code 2).

## Commands (about 90)

| Group | Commands |
|---|---|
| Connection | `PING ECHO QUIT AUTH [user] SELECT(0) HELLO(2) CLIENT(ID SETNAME GETNAME LIST INFO KILL SETINFO) COMMAND(COUNT LIST)` |
| Strings | `SET(NX XX GET EX PX EXAT PXAT KEEPTTL) GET MGET MSET MSETNX SETNX SETEX PSETEX GETSET GETDEL APPEND STRLEN GETRANGE INCR INCRBY DECR DECRBY` |
| Hashes | `HSET HMSET HSETNX HGET HMGET HDEL HEXISTS HGETALL HKEYS HVALS HLEN HINCRBY HSTRLEN` |
| Lists | `LPUSH RPUSH LPOP RPOP LLEN LRANGE LINDEX LSET LREM LTRIM` |
| Sets | `SADD SREM SMEMBERS SISMEMBER SCARD SINTER SUNION SDIFF` |
| Sorted sets | `ZADD(NX XX CH) ZREM ZCARD ZSCORE ZRANK ZINCRBY ZRANGE ZREVRANGE ZRANGEBYSCORE ZCOUNT ZPOPMIN ZPOPMAX` |
| Keys | `DEL UNLINK EXISTS KEYS(glob) TYPE RENAME DBSIZE FLUSHDB FLUSHALL RANDOMKEY` |
| Expiry | `EXPIRE PEXPIRE EXPIREAT PEXPIREAT(NX XX GT LT) EXPIRETIME PEXPIRETIME TTL PTTL PERSIST` |
| Pub/Sub | `SUBSCRIBE UNSUBSCRIBE PSUBSCRIBE PUNSUBSCRIBE PUBLISH PUBSUB` |
| Transactions | `MULTI EXEC DISCARD WATCH UNWATCH` |
| Replication | `REPLICAOF/SLAVEOF (incl. NO ONE) ROLE WAIT PSYNC REPLCONF` |
| Operations | `INFO(server clients memory persistence stats replication commandstats keyspace) CONFIG(GET SET) SLOWLOG MONITOR ACL(WHOAMI LIST USERS) BGREWRITEAOF SHUTDOWN TIME` |

Semantics follow Redis: `EXPIRE k 0` deletes the key, `TTL` rounds to the nearest second, collections vanish when empty,
`WRONGTYPE` protects against type confusion, errors carry a code (`ERR NOAUTH WRONGPASS NOPERM READONLY MISCONF OOM LOADING
EXECABORT WRONGTYPE NOPROTO`). Inline commands (`telnet`/`nc`) work. Values are **binary-safe**.
Not implemented: `SCAN`, blocking list ops, streams, Lua, `HELLO 3`/RESP3, cluster, `SPOP`/`SRANDMEMBER` (non-deterministic,
see ADR 0004).

## Architecture

```text
 clients ──TCP[/TLS]/RESP──▶ RedCakeServer ──virtual thread per connection──▶ ClientHandler
                                                                                  │
   reads: lock-free, parallel   ◀────────── auth · role check · MULTI queue ─────┤
                                                                                  │ writes (primary only)
                                                          ┌──── global write lock (in-memory work only) ────┐
                                                          │ make room → normalize → execute → AOF → backlog │
                                                          │                          → per-replica queues   │
                                                          └──────────────────────────┬───────────────────────┘
                                                              replica writer thread ─┴─▶ replica sockets
```

| Package | Responsibility |
|---|---|
| `cli`, `config` | strict argument/config-file parsing; immutable config objects (a few are runtime-changeable via `CONFIG SET`) |
| `server` | `RedCakeServer` (listener, TLS, limits, protected mode), `ClientHandler` (per-connection pipeline), `Session`, `ClientRegistry`, `AuthFailureTracker` |
| `resp` | `RespParser` (incremental, bounded, inline-capable), `RespWriter`, reply types |
| `command` | `CommandHandler` router, `LambdaCommand`/`CommandProvider`, `commands/reads`, `commands/write`, `commands/types` |
| `store` | `InMemoryKeyValueStore` (atomic `compute` sections, typed values), `ExpirationManager`, `MemoryManager`, `ZSet` |
| `replication` | primary (`ReplicationManager`, `ReplicaConnection`, `ReplicationBacklog`) and replica (`ReplicaSyncManager`) |
| `persistence` | `AppendOnlyLog`, `ExpiryPropagator` |
| `security` | `AclRegistry`, `Role`, `TlsSupport` |
| `pubsub`, `stats`, `metrics` | broker, counters/slow log, Prometheus endpoint |

### Key design decisions (details in `docs/adr/`)

- **Atomic store.** Every mutation is a `ConcurrentHashMap.compute*`; reads are lock-free. One expiry entry per key.
- **Deterministic writes.** Relative times become absolute (`EX 10` → `PXAT <ms>`) with one clock reading, so primary, replicas and
  the AOF agree on every deadline. Key expiry on the primary is propagated as an explicit `DEL`; evictions too.
- **Non-blocking replication.** Under the write lock a command is only *queued* for each replica; a per-replica thread writes batches.
  A replica more than 64 MiB behind is dropped.
- **Replication v2.** Partial resync from a ring-buffer backlog (`PSYNC <replid> <offset>` → `CONTINUE`), otherwise a point-in-time
  `FULLRESYNC` (replica clears its data, answers `-LOADING` until `SYNCED`). Replicas ack every second; `WAIT n ms` blocks until
  `n` replicas acknowledged. `REPLICAOF NO ONE` promotes (new history); `REPLICAOF host port` demotes at runtime.
- **Binary-safe strings.** ISO-8859-1 "byte strings" end to end: lossless, memory-compact, no refactor to `byte[]`.
- **Pipelining.** Replies are flushed only when no further request bytes are pending (~20x throughput with `-P 16`, `docs/benchmarks.md`).

## Persistence (AOF)

`--aof-file data/redcake.aof` logs every successful write (absolute times) as RESP. On start the log is replayed; a torn tail from a
crash is truncated. `BGREWRITEAOF` compacts it (currently synchronous under the write lock). If a disk write fails the node answers
writes with `-MISCONF` and `/health` returns 503 instead of pretending data is safe. `scripts/crash-test.sh` kills the server with
`kill -9` under load and verifies no acknowledged write is lost with `--aof-fsync always`. Only primaries log; a promoted replica
starts a fresh log from its dataset.

## Memory limit

With `--maxmemory` RedCake estimates memory per key (see `MemoryEstimator`). Above the limit: `noeviction` refuses growing commands
with `-OOM` (reads, deletes and `EXPIRE` still work); `allkeys-random` evicts arbitrary keys; `volatile-ttl` evicts the keys closest
to expiry. Evictions replicate as `DEL`. The estimate is intentionally generous and approximate, not an exact byte count.

## Security

See `SECURITY.md`. In short: loopback by default, protected mode, API key and/or ACL roles (`admin`/`readwrite`/`readonly`), brute-force
lockout, TLS + mutual TLS + verified TLS replication, bounded parser, `--maxmemory`/`--max-clients`/`--timeout`, non-root Docker image,
systemd sandboxing (`deploy/redcake.service`). **Do not expose RedCake to an untrusted network.**

## Observability

`INFO` (incl. `commandstats`), `SLOWLOG`, `CLIENT LIST`, `MONITOR`, `CONFIG GET/SET` (`maxmemory`, `maxmemory-policy`, `loglevel`,
`slowlog-*` at runtime), and with `--metrics-port 9100`: `GET /metrics` (Prometheus text, latency histogram) and `GET /health`.

## Build, test, benchmark

```bash
./mvnw clean verify                 # 166 tests: unit, in-JVM replication/TLS/ACL/memory/pub-sub, AOF, fuzz
scripts/smoke-test.sh 6379          # redis-cli checks against a running server
scripts/crash-test.sh 5             # kill -9 recovery
scripts/soak-test.sh 60             # memory-growth watch (use 1440 for a 24 h soak)
scripts/benchmark.sh 6379 200000    # redis-benchmark scenarios
./mvnw -Pcoverage verify            # JaCoCo gate (needs network; not run in the authoring environment)
```

`DifferentialRedisTest` compares RedCake's replies with a real `redis-server` for ~225 commands; it runs automatically when
`redis-server` is on the PATH (`sudo apt install redis-server`) and is skipped otherwise.

## Known limitations

- Collections are plain Java structures: `LPOP`/`LPUSH` on huge lists and `ZRANK` are O(n); `KEYS` is O(keyspace).
- Writes are serialized by one lock (correct and simple, ADR 0001); it is the ceiling for write throughput.
- Replication is RedCake-to-RedCake (own handshake, not an RDB stream); chaining (replica of a replica) is not supported.
- No automatic failover: promotion is an explicit `REPLICAOF NO ONE` after fencing the old primary.
- `maxmemory` uses estimates; `BGREWRITEAOF` and the full-sync snapshot copy briefly pause writers.
- Pub/Sub is not replicated and a stuck subscriber can slow its publisher.

## Troubleshooting

- `Unable to bind ... port may already be in use`: `ss -ltnp | grep -E ':6379|:6381'`.
- `Refusing to listen on ... without authentication`: set `REDCAKE_API_KEY`, bind to loopback, or add `--allow-insecure`.
- Replica log `Primary rejected PSYNC` / `Primary authentication failed`: version mismatch / different API keys.
- Replica log `Replica connection lost: ... PKIX` or `No subject alternative names`: the primary's certificate is not in the
  replica's `--tls-truststore` or does not list the host name used in `--replicaof`.
- Clients get `-LOADING`: a replica is applying a full snapshot; retry in a moment (`INFO replication` shows `master_link_status`).
- Clients get `-OOM`: `maxmemory` reached under `noeviction`; delete data, raise the limit, or pick an eviction policy.
