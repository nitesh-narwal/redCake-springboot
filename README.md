# RedCake

RedCake is a Redis-compatible in-memory data store written in Java 21 (virtual threads) on Spring Boot (used only
for dependency injection and lifecycle). It speaks RESP over TCP, so `redis-cli`, `redis-benchmark` and most Redis
client libraries can talk to it.

**Status: experimental.** Suitable for learning, local development, protocol experiments and benchmarking. Not a
drop-in production Redis: no sharded cluster, RESP2 only, collection commands are O(n) in places. Automatic failover is
available (opt-in, needs ZooKeeper, see [High availability](#high-availability-automatic-failover)).
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
With automatic failover: `REDCAKE_API_KEY=change-me docker compose -f docker-compose.cluster.yml up --build`
(ZooKeeper + three RedCake nodes that elect their own primary).

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
| | `--min-replicas-to-write N`, `--min-replicas-max-lag S` | refuse writes (`-NOREPLICAS`) unless N replicas acknowledged within S seconds (off, 10) |
| Failover | `--zk-connect HOSTS` | **turns automatic failover on**: `host:port[,host:port...][/chroot]`; excludes `--replicaof` |
| | `--cluster-name`, `--node-id`, `--advertise-host`, `--advertise-port` | znode namespace (redcake), unique node name (`host:port`), address others use to reach this node |
| | `--replica-priority N` | lower is promoted first, `0` never (100) |
| | `--zk-session-timeout-ms`, `--zk-connection-timeout-ms`, `--cluster-election-delay-ms` | failure detection time (10000), (5000), quiet time before promoting (1000) |
| | `REDCAKE_ZK_AUTH`, `--zk-auth-file` | `user:password` for ZooKeeper digest authentication |
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
| Replication | `REPLICAOF/SLAVEOF (incl. NO ONE) ROLE WAIT PSYNC REPLCONF FAILOVER` |
| Operations | `INFO(server clients memory persistence stats replication cluster commandstats keyspace) CONFIG(GET SET) SLOWLOG MONITOR ACL(WHOAMI LIST USERS) BGREWRITEAOF SHUTDOWN TIME` |

Semantics follow Redis: `EXPIRE k 0` deletes the key, `TTL` rounds to the nearest second, collections vanish when empty,
`WRONGTYPE` protects against type confusion, errors carry a code (`ERR NOAUTH WRONGPASS NOPERM READONLY MISCONF OOM LOADING
EXECABORT WRONGTYPE NOPROTO CLUSTERDOWN NOREPLICAS NOTPRIMARY`). Inline commands (`telnet`/`nc`) work. Values are **binary-safe**.
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
| `cluster` | failover: `FailoverCoordinator` (the loop), `ZooKeeperService` (Curator), `ElectionPolicy`, `ClusterState` (the write fence) |
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
- **Failover (opt-in).** ZooKeeper decides who the primary is; a primary writes only while it can prove that (connection + lease),
  so a cut-off or frozen primary stops *before* a successor can exist. The most up-to-date replica is promoted, the others resume
  with a partial resync. ADR 0005.
- **Binary-safe strings.** ISO-8859-1 "byte strings" end to end: lossless, memory-compact, no refactor to `byte[]`.
- **Pipelining.** Replies are flushed only when no further request bytes are pending (~20x throughput with `-P 16`, `docs/benchmarks.md`).

## High availability (automatic failover)

Opt-in. Point every node at the same ZooKeeper ensemble and they elect their own primary - and elect a new one when it
fails. No `--replicaof`, no manual `REPLICAOF NO ONE`. Design and proofs of safety: `docs/adr/0005-zookeeper-failover.md`.

```bash
# three nodes on three hosts, one ZooKeeper ensemble (the same options everywhere except --advertise-host)
export REDCAKE_API_KEY=change-me
java -jar redCake.jar --bind 0.0.0.0 --aof-file data/redcake.aof \
     --zk-connect zk1:2181,zk2:2181,zk3:2181 --advertise-host node1
redis-cli -h node1 INFO cluster          # cluster_state:leading | following | electing
redis-cli -h node1 FAILOVER              # planned: hand the primary role to the best replica, no write lost
```

**Rules**
- A node starts read-only and answers `-LOADING` until it leads or has synced; within about a second the best node wins.
- The primary is the **most up-to-date** eligible replica (highest replication offset). `--replica-priority 0` marks a node that must
  never be promoted; a node that only has an old AOF (older than the last leader's epoch) can never lead.
- A primary writes only while it holds ZooKeeper's `/leader` znode **and** has renewed its lease (2/3 of the session timeout).
  Cut off from ZooKeeper - or frozen - it answers `-CLUSTERDOWN` and steps down; it never keeps writing next to its successor.
- After a failover the other replicas **resume with a partial resync** (no dataset copy); the old primary rejoins as a replica and
  drops writes nobody else received.
- `--advertise-host` must be reachable by the other nodes and clients (and match the TLS certificate with `--tls-replication`);
  node ids and addresses must be unique; all nodes share the same API key.
- Anyone who can write the znodes can choose the primary (and so receive the replication `AUTH`): use `REDCAKE_ZK_AUTH` /
  `--zk-auth-file` (digest ACLs, the nodes warn at startup if it is missing) and `--tls-replication`, which makes replicas verify
  the primary's certificate, so a tampered znode alone cannot capture the API key.
- `REPLICAOF` is refused (ZooKeeper decides). Use `FAILOVER [TO host port] [TIMEOUT ms]` or stop the primary gracefully
  (`SIGTERM`/`SHUTDOWN`): it hands over first, with no waiting for a timeout and no loss.

**What clients see**

| Reply | Meaning |
|---|---|
| `-READONLY replica does not accept writes (the primary is host:port)` | you reached a replica; go to the address shown |
| `-CLUSTERDOWN ...` | no writable primary right now (election running, ZooKeeper unreachable, hand-over); retry |
| `-NOREPLICAS Not enough good replicas to write.` | `--min-replicas-to-write` is not satisfied |
| `-LOADING ...` | node is joining / syncing; retry |

Finding the primary: `ROLE`, `INFO cluster` (`cluster_leader:host:port`), the `-READONLY` hint, or - for a load balancer - HTTP on the
metrics port: `GET /primary` is `200` only on the node that may write, `GET /replica` is `200` on replicas that hold trusted data
(`deploy/haproxy.cfg.example`). Existing clients need no change when a failover happens behind such a balancer.

**Observability.** `INFO cluster` (`cluster_state`, `cluster_epoch`, `cluster_fenced`, `cluster_zk_state`, `cluster_election` - the
reason an election is stuck), Prometheus `redcake_cluster_*` (`is_primary`, `fenced`, `epoch`, `promotions_total`, ...).

**Tuning.** `--zk-session-timeout-ms` (default 10000) is the failure-detection time: an unplanned failover takes about that plus
~1.5 s, plus up to one ZooKeeper `tickTime` (it expires sessions on tick boundaries; measured with `tickTime` 1 s and a 3 s session:
5.1 s from `kill -9` to a writable successor); a planned one well under a second. Lower is faster but less tolerant of ZooKeeper hiccups: a cut-off primary fences
itself after two thirds of it, and the ZooKeeper client waits before every reconnect (measured here after a dropped connection:
0.2-1.1 s with several servers in `--zk-connect`, 1.2-1.7 s with only one), so a session of 2-3 s is lost to a mere network blip
and costs an election. Name every ensemble member in `--zk-connect` and stay at 5 s or more unless you have measured your
network. Replication stays asynchronous: bound the loss of an unplanned failover with `WAIT` and
`--min-replicas-to-write`. Run ZooKeeper with 3 or 5 members; if a majority is down there is no failover and no writes (reads continue).

**If an election is stuck** (`cluster_election:` says "its data (epoch 3) is older than the last leader's epoch 5"): bring back the
node with the newest data. If it is gone for good and you accept the loss, set the znode `/redcake/<cluster>/epoch` to `0` with `zkCli`.

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
- Failover (ZooKeeper, opt-in) keeps replication asynchronous: an unplanned failover can lose writes the old primary had acknowledged
  but no replica had received (use `WAIT` / `--min-replicas-to-write`). No ZooKeeper majority means no failover and no writes.
  Without `--zk-connect` there is no automatic failover: promotion is an explicit `REPLICAOF NO ONE` after fencing the old primary.
- `maxmemory` uses estimates; `BGREWRITEAOF` and the full-sync snapshot copy briefly pause writers.
- Pub/Sub is not replicated and a stuck subscriber can slow its publisher.

## Troubleshooting

- `Unable to bind ... port may already be in use`: `ss -ltnp | grep -E ':6379|:6381'`.
- `Refusing to listen on ... without authentication`: set `REDCAKE_API_KEY`, bind to loopback, or add `--allow-insecure`.
- Replica log `Primary rejected PSYNC` / `Primary authentication failed`: version mismatch / different API keys.
- Replica log `Replica connection lost: ... PKIX` or `No subject alternative names`: the primary's certificate is not in the
  replica's `--tls-truststore` or does not list the host name used in `--replicaof`.
- Clients get `-LOADING`: a replica is applying a full snapshot; retry in a moment (`INFO replication` shows `master_link_status`).
- Clients get `-CLUSTERDOWN`: no writable primary - see `INFO cluster` (`cluster_state`, `cluster_zk_state`, `cluster_election`).
- Replica log `Primary rejected PSYNC: -NOTPRIMARY`: you pointed it at a node that is not (yet) the elected primary; in cluster mode
  nodes find the primary themselves and retry within a fraction of a second.
- Cluster node stays `joining`: it cannot reach ZooKeeper (`--zk-connect`, firewall, `--zk-connection-timeout-ms`).
- Clients get `-OOM`: `maxmemory` reached under `noeviction`; delete data, raise the limit, or pick an eviction policy.
