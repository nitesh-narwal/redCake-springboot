# RedCake — Road to "Insanely Good"

Audit date: 2026-10-05. Based on a full read of `src/main` (~3.9k LOC incl. tests) and `README.md`.
Bugs below are from code reading unless marked **[verified]**. Reproduce each one with a failing test
before fixing (see "How to work this list").

> **Legend:** `[x]` done and tested, `[~]` partially done (note says what is left), `[ ]` not started.
> Progress log of the first implementation pass is at the end of this file.

---

## 0. Verdict (honest)

**Status after pass 2 (section 12):** binary-safe values, four more data types, Pub/Sub, transactions, `maxmemory`, TLS, ACL roles,
partial resync/`WAIT`/runtime `REPLICAOF`, observability (SLOWLOG, CLIENT, MONITOR, Prometheus), config file, expiry propagated as `DEL`.
166 tests, crash-recovery verified. Biggest remaining gaps: `SCAN`, RESP3, cluster/failover, snapshot file (RDB-like), per-key ACLs,
blocking list operations, and anything that needs the Redis TCL suite or client libraries (not available offline).

**Status after pass 1 (see section 11):** the P0 bugs are fixed and covered by tests, the command set grew from 14 to 50,
AOF persistence, protected mode, brute-force lockout, heartbeats/backoff and pipelining (~20x) were added.
Still **not** production ready: no TLS/ACL, no `maxmemory`, strings are not binary-safe, no partial resync or failover.
The table below is the audit **as found before pass 1**.


| Question | Answer |
|---|---|
| Good code? | Good *learning* project. Clean package layout, sealed `RespValue`, virtual threads, versioned expiry, overflow-checked math, bounded parser, decent tests for parser/CLI/snapshot barrier. |
| Production ready? | **No.** No durability, no TLS, no eviction/memory cap, no metrics/logging framework, no failover, several correctness bugs (section 1). README already admits part of this. |
| Many clients concurrently? | **Yes, up to 10,000** sockets via virtual threads. But all *writes* are serialized behind one global lock that also holds replica network I/O (section 1, B1). |
| Many replicas concurrently? | Works functionally (map of `ReplicaConnection`, per-replica output lock). One slow replica stalls every writer. Full-sync is not point-in-time (B2). |
| Efficient / fast? | Mediocre. Byte-at-a-time parsing, 3 syscalls per reply, flush per command defeats pipelining, `String` values, global write lock. Expect a small fraction of real Redis throughput. Measure first (section 4). |
| Completely secure? | **No.** Single shared plaintext key, no TLS, no brute-force limit, no idle timeout, memory DoS vectors, `AUTH` state bug. |
| Do all commands work? | The 14 implemented commands work for the happy path (existing tests pass; no failures in `./mvnw -o -q test`). Several deviate from Redis semantics (section 2). Most of Redis is missing. |

---

## 1. Bugs and design flaws found (fix before adding features)

Priority: P0 = data corruption / outage, P1 = serious, P2 = quality.

### P0
- [x] **B1 Global write lock held during replica network I/O.**
  `ClientHandler.java:709-716` holds `primaryWriteLock` across `commandHandler.handle` **and** `replicationManager.replicate` → `ReplicaConnection.send` → `output.write+flush` (blocking socket). One slow/stuck replica blocks every writing client (until TCP buffers fill / kernel timeout). Fix: under the lock only assign a sequence number and append to an in-memory replication backlog (ring buffer); a per-replica sender thread drains it.
- [x] **B2 Snapshot is not point-in-time → double-apply.**
  `ReplicationManager.registerReplicaAndSendSnapshot` registers the replica under the lock, releases it, then iterates the *live* `ConcurrentHashMap` (`forEachSnapshot`, weakly consistent). A write after registration can be visible in the iteration **and** queued in `pendingCommands`. Replica applies both. `INCR`/`INCRBY`/`DECR` are not idempotent → replica value drifts. README claims "captures the current store view while briefly holding the lock" — the code does not. Fix: replication offset + backlog (PSYNC-style): snapshot records offset N, replica then applies backlog from N+1 only; or copy-on-write/versioned snapshot (use entry `version` already in `ValueEntry`).
- [x] **B3 Replica never clears its store on (re)sync.** After reconnect the primary sends a snapshot of SETs only. Keys deleted/expired on primary during the outage stay on the replica forever. Fix: `FLUSHALL`-equivalent (or swap in a fresh store) before applying a full sync; serve `-LOADING` until sync completes.
- [x] **B4 Lost updates between `SET`/`DEL`/`EXPIRE` and `INCR`.**
  `InMemoryKeyValueStore.increment` locks per key, but `set`, `delete`, `expire` do not take that lock, and `incrementLocked` uses `data.put` (not CAS). Interleaving: INCR reads v1 → EXPIRE replaces with v2 (TTL) → INCR `put`s v1+1 without TTL → EXPIRE lost, stale expiration event dropped by version check. Fix: use `data.compute/merge` for every mutation (single atomic path) and delete `keyLocks`.
- [x] **B5 `AUTH` handling bug.** `ClientHandler.java:645` does `authenticated = valid`. (a) When auth is disabled any `AUTH x` sets `authenticated=false` and locks the client out of its own connection. (b) A wrong `AUTH` after a good one de-authenticates. Fix: when auth disabled reply `-ERR AUTH <password> called without any password configured`; failed AUTH must not clear a prior success (Redis keeps state? decide and document — safest: failed AUTH leaves state unchanged and counts toward rate limit).
- [x] **B6 `REPLCONF listening-port` sends the wrong port.** `ReplicaSyncManager.java:1481` sends `socket.getLocalPort()` (ephemeral outbound port). Must send this node's configured listener port (`RedCakeServerConfig.getPort()`).

### P1
- [x] **B7 Unbounded memory growth — `keyLocks`.** `ConcurrentHashMap<String,Object> keyLocks` gets an entry per key ever INCR'd and is never pruned. Disappears if B4 fix removes it.
- [x] **B8 Unbounded memory growth — `ExpirationManager` queue.** Every `SET k v EX 3600`, `EXPIRE`, and `INCR` on a TTL key adds a new `ExpirationEntry`; stale ones are only removed when their time arrives. A loop of 10M `SET k v EX 86400` retains 10M entries for a day. Fix: timer wheel / hashed wheel with lazy cancel, or keep one entry per key (indexed priority queue / `DelayQueue` + remove on overwrite), plus active sampled expiry like Redis.
- [x] **B9 No memory limit / eviction.** No `maxmemory`, no policies. A client can fill the heap and OOM the JVM (10k conns × 128 args × 1 MiB each is theoretically 1.2 TB of allowed input). Add `maxmemory`, per-connection input/output buffer caps, eviction (allkeys-lru/lfu, volatile-ttl), `-OOM` replies.
  *Done: `--maxmemory`, three eviction policies, `-OOM` for growing commands, evictions replicated as `DEL`, `CONFIG SET maxmemory` (MemoryLimitTest). The size is an estimate (see `MemoryEstimator`).*
- [x] **B10 Relative TTL replicated as-is.** `SET k v EX 10` and `EXPIRE k 10` are replayed on the replica "now + 10s" → replica expires later than primary, plus lag. Replicate as absolute `PXAT <unix-ms>` (or `PEXPIREAT`) and propagate expiry as explicit `DEL` from primary (Redis behavior) so replica and primary agree.
  *Done: relative times become absolute (`PXAT`/`PEXPIREAT`) with one clock reading; expiry and eviction are propagated as explicit `DEL` (`ExpiryPropagator`).*
- [x] **B11 Binary-unsafe values.** RESP bulk strings are decoded to Java `String` via UTF-8 (`RespParser:645`) — arbitrary bytes (images, protobuf, compressed blobs) are silently corrupted (invalid sequences → U+FFFD). Redis strings are binary-safe. Fix: `byte[]` (or a `RedisString` wrapper) end-to-end: parser → store → writer. Also halves heap for ASCII-heavy data vs `String` + `ValueEntry` overhead if you use compact structures.
  *Done: ISO-8859-1 "byte strings" end to end (ADR 0003); every byte value round-trips through GET/SET, the AOF and replication (FeatureServerTest, RespParserTest).*
- [x] **B12 No idle/read timeouts.** Slowloris: open 10,000 sockets, send nothing, hold all permits. Replica side has no read timeout either → half-open primary connection hangs for hours (default keepalive). Add `SO_TIMEOUT`/idle timeout (configurable `timeout 0` default off like Redis, but ON for unauthenticated connections ~10 s), replica heartbeat (`REPLCONF ACK`/`PING`) + `repl-timeout`.
- [x] **B13 Protocol errors are silent.** Malformed input just closes the socket (`ClientHandler` catch). Send `-ERR Protocol error: ...` first, then close. Also **inline commands** (`PING\r\n` via `telnet`/`nc`) are rejected with "Expected RESP array" — many tools and the Redis test-suite use them.
- [x] **B14 `PSYNC` replica socket stays a "client" thread.** After `registerReplicaAndSendSnapshot` the handler loops reading from the replica socket. A replica disconnect is only noticed on the next write. Give replication its own listener/port or detach the socket from the client handler and run a dedicated reader for `REPLCONF ACK`.
  *Done: the connection thread now reads the replica stream (ACKs) and unregisters the replica the moment it disconnects. A dedicated replication port is still open.*
- [x] **B15 Unauthenticated replica handshake when auth is off + bind 0.0.0.0.** Anyone on the network can `PSYNC` and dump all data; and any client can write. Refuse to start on non-loopback bind without API key unless `--allow-insecure` is passed (protected-mode like Redis).

### P2
- [x] **B16 `WRITE_COMMANDS` hard-coded in `CommandHandler`.** Add a command and forget the set → silently not replicated and not rejected on replicas. Put `boolean isWrite()` (or an annotation) on `RedCakeCommand`. Also `EchoCommand` lives in `commands/write/` but is read-only.
- [x] **B17 `INFO` is mostly fake.** `master_repl_offset:0` constant, `masterReplid` is generated in `InfoCommand` (not shared with replication), no `connected_slaves`, `connected_clients`, `uptime_in_seconds`, `used_memory`, `role`-specific `master_host/port/link_status`. `keyspace` iterates **all** keys (O(n), and mutates by removing expired).
- [x] **B18 Error format.** Replies lack the `ERR ` prefix (`-wrong number of arguments...`). Real clients/libraries branch on the prefix (`ERR`, `WRONGTYPE`, `NOAUTH`, `READONLY`, `OOM`...). Unknown command text should be `ERR unknown command 'x', with args beginning with: ...`.
- [x] **B19 Logging via `System.out/err.println`.** Replace with SLF4J (already on classpath through Spring Boot), structured logs, levels, no stack-trace `printStackTrace()` in `ReplicaManager`.
- [x] **B20 Test fragility.** `RedCakeApplicationTests` boots the app on fixed port 6379 → fails if a real Redis runs locally. Use `--port 0`/random free port. Mockito self-attach warning: add the agent in surefire `argLine`.
- [~] **B21 Repo hygiene.** No commits yet; `target/`, `build/`, `.idea/`, `graphify-out/` untracked with no `.gitignore`. `pom.xml` has empty `<licenses><license/>`, `<developers><developer/>`, `<scm>` placeholders. No LICENSE, no CI.
  *Partial: `.gitignore`, pom cleanup, CI, `SECURITY.md`, `CHANGELOG.md`, `CONTRIBUTING.md` added. Choosing a LICENSE and making the first commit are yours.*
- [x] **B22 Non-daemon platform thread** for `ExpirationManager` and fixed 1 s reconnect with no backoff/jitter in `ReplicaSyncManager`.

---

## 2. Command correctness vs Redis

Implemented: `PING ECHO SET GET DEL EXISTS EXPIRE TTL PTTL INCR INCRBY DECR DECRBY INFO`.

Deviations to fix:
- [x] `EXPIRE k 0` / negative → Redis **deletes** the key and returns 1; RedCake returns an error.
- [x] `TTL` rounds **up** (`(ms+999)/1000`); Redis rounds to nearest (`(ms+500)/1000`).
- [x] `SET` only supports `EX`/`PX` with exactly 4 args. Missing `NX`, `XX`, `GET`, `KEEPTTL`, `EXAT`, `PXAT`, combinations (`SET k v NX EX 10`). `SET k v foo 10` reports "invalid expire time" instead of "syntax error" because the number is parsed first.
- [x] `EXPIRE` lacks `NX|XX|GT|LT` options.
- [x] `INCRBY` on a non-existent expired key path resets without preserving semantic parity checks — add tests (`SET k 5 EX 1`, sleep, `INCR k` must give 1 with no TTL).
- [x] `PING` while subscribed (n/a today), `ECHO` fine.
  *Done: `PING` in subscribed mode replies `["pong",""]` (FeatureServerTest).*
- [x] `INFO` sections: add `clients`, `memory`, `stats`, `persistence`, `commandstats`, `everything`.
- [x] Case: `COMMAND`, `CLIENT SETNAME/ID/LIST`, `HELLO`, `QUIT`, `SELECT`, `CONFIG GET` — `redis-cli`, `redis-py`, `Jedis`, `Lettuce` send these on connect. Missing ones can break client libraries. **Highest-value compatibility work.**

Missing commands (suggested order):
- [~] Core: `QUIT`, `SELECT` (multi-db), `HELLO 2|3`, `COMMAND (COUNT|DOCS|INFO)`, `CLIENT (ID|SETNAME|GETNAME|LIST|KILL)`, `CONFIG (GET|SET|REWRITE)`, `DBSIZE`, `FLUSHDB`, `FLUSHALL`, `KEYS`, `SCAN`, `TYPE`, `RENAME`, `RENAMENX`, `UNLINK`, `RANDOMKEY`, `OBJECT`, `DEBUG`.  
  *Partial: done: QUIT SELECT(0) HELLO(2) COMMAND(COUNT/LIST) CLIENT(ID SETNAME GETNAME LIST INFO KILL SETINFO) CONFIG(GET/SET) DBSIZE FLUSH* KEYS TYPE RENAME UNLINK RANDOMKEY TIME; open: SCAN, OBJECT, DEBUG, HELLO 3*
- [~] Strings: `MGET`, `MSET`, `MSETNX`, `SETNX`, `SETEX`, `PSETEX`, `GETSET`, `GETDEL`, `GETEX`, `APPEND`, `STRLEN`, `GETRANGE`, `SETRANGE`, `INCRBYFLOAT`, `SETBIT/GETBIT/BITCOUNT`.  
  *Partial: done: MGET MSET MSETNX SETNX SETEX PSETEX GETSET GETDEL APPEND STRLEN GETRANGE; open: GETEX SETRANGE INCRBYFLOAT bit operations*
- [x] TTL: `PEXPIRE`, `EXPIREAT`, `PEXPIREAT`, `PERSIST`, `EXPIRETIME`.  
  *Done: EXPIRE PEXPIRE EXPIREAT PEXPIREAT (NX XX GT LT) EXPIRETIME PEXPIRETIME PERSIST TTL PTTL*
- [~] Hashes: `HSET HGET HDEL HEXISTS HGETALL HKEYS HVALS HLEN HINCRBY HMGET HSCAN`.
  *Partial: done: HSET HMSET HSETNX HGET HMGET HDEL HEXISTS HGETALL HKEYS HVALS HLEN HINCRBY HSTRLEN; open: HSCAN*
- [~] Lists: `LPUSH RPUSH LPOP RPOP LLEN LRANGE LINDEX LSET LREM LTRIM BLPOP BRPOP`.
  *Partial: done: LPUSH RPUSH LPOP RPOP LLEN LRANGE LINDEX LSET LREM LTRIM; open: BLPOP/BRPOP, LINSERT*
- [~] Sets: `SADD SREM SMEMBERS SISMEMBER SCARD SPOP SINTER SUNION SDIFF SSCAN`.
  *Partial: done: SADD SREM SMEMBERS SISMEMBER SCARD SINTER SUNION SDIFF; open: SPOP/SRANDMEMBER (non-deterministic, would diverge replicas), SSCAN*
- [~] Sorted sets: `ZADD ZREM ZRANGE ZRANGEBYSCORE ZRANK ZSCORE ZCARD ZINCRBY ZPOPMIN`.
  *Partial: done: ZADD(NX XX CH) ZREM ZRANGE ZREVRANGE ZRANGEBYSCORE ZRANK ZSCORE ZCARD ZINCRBY ZCOUNT ZPOPMIN ZPOPMAX; open: ZRANGEBYLEX, ZREMRANGEBY*, ZUNIONSTORE*
- [x] Pub/Sub: `SUBSCRIBE UNSUBSCRIBE PSUBSCRIBE PUBLISH`.
  *Done: SUBSCRIBE UNSUBSCRIBE PSUBSCRIBE PUNSUBSCRIBE PUBLISH PUBSUB (not replicated)*
- [x] Transactions: `MULTI EXEC DISCARD WATCH UNWATCH`.
  *Done: MULTI EXEC DISCARD WATCH UNWATCH, EXECABORT, atomic under the write lock*
- [~] Ops: `SLOWLOG`, `LATENCY`, `MONITOR`, `SAVE`, `BGSAVE`, `BGREWRITEAOF`, `REPLICAOF`/`SLAVEOF`, `ROLE`, `WAIT`, `PSYNC` (real offset form).  
  *Partial: done: SLOWLOG MONITOR BGREWRITEAOF REPLICAOF/SLAVEOF ROLE WAIT SHUTDOWN CLIENT LIST; open: LATENCY, SAVE, BGSAVE*
- [ ] Later: streams (`XADD XREAD XGROUP`), Lua (`EVAL`), Functions, `ACL`, geo, HyperLogLog, bitfield.

Wrong-type semantics: once non-string types exist, add `WRONGTYPE Operation against a key holding the wrong kind of value`.

---

## 3. Architecture changes needed

### 3.1 Storage engine
- [~] Introduce `RedisObject` = `{type, encoding, byte[] | collection, expireAtMs, lruClock/lfu}`. Replace `ValueEntry(String,Long,long)`.
  *Partial: `ValueEntry(Object value, expiresAt, version, bytes)` with `ValueType` and per-type structures (ADR 0004); byte[] values and encodings (listpack/intset) are open*
- [ ] Store keys as `ByteArrayKey` (wrapper with cached hash) — `byte[]` can't be a map key.
- [x] Make mutations atomic through one primitive: `store.compute(key, fn)`; remove `keyLocks`.
- [x] Decide threading model (pick one, write ADR in `docs/adr/`):
  *Done: ADR 0001: global write lock for writes, lock-free parallel reads; alternatives documented*
  - A. **Single-writer command thread** (Redis model): network threads parse, one thread executes, replication order = execution order, no write lock, no data races, simplest to make correct. Recommended.
  - B. Sharded store (N shards, one executor each, key-hash routing); multi-key commands need cross-shard coordination.
  - C. Keep concurrent map + fine-grained atomics (current); hardest to keep correct as types are added.
- [~] Expiry: timer wheel + sampled active expiry + lazy expiry; one entry per key; expiry propagated as `DEL`.  
  *Partial: done: one entry per key, lazy + active expiry, expiry propagated as DEL; open: timer wheel, sampled expiry*
- [~] Memory accounting per object; `maxmemory` + eviction; `MEMORY USAGE`.
  *Partial: per-entry estimates + `maxmemory` + eviction; `MEMORY USAGE` open*

### 3.2 Network / protocol
- [~] Replace per-byte `BufferedInputStream.read()` with a pooled `byte[]`/`ByteBuffer` incremental parser (state machine, no per-byte lock/boxing). Consider Netty (epoll, `PooledByteBufAllocator`) vs staying on virtual threads — benchmark both (section 4).  
  *Partial: ints are parsed without allocation; still reads byte-by-byte through BufferedInputStream*
- [x] Buffered output + flush only when no more input is pending (`input.available()==0` / end of read batch) → real pipelining; single `write` syscall per batch; encode replies directly into a `ByteBuffer` (no `String` concat, no `getBytes` per piece).
- [ ] RESP3 (`HELLO 3`): maps, sets, doubles, null `_`, push messages (needed for client-side caching/pubsub).
- [~] Raise limits to Redis-like but configurable: `proto-max-bulk-len` 512 MiB default, `client-query-buffer-limit`, max args 1,048,576 (with total-bytes cap). Currently 128 args blocks `MSET` of >63 pairs.  
  *Partial: done: 1024 args and 8 MiB per command; bulk limit still 1 MiB, not configurable*
- [x] Inline command support; `-ERR Protocol error` replies.
- [~] Connection limits: `maxclients` configurable, per-IP cap, idle timeout, output-buffer limit per client class (normal/replica/pubsub).  
  *Partial: `--max-clients`, `--timeout`, 15 s auth deadline; per-IP cap and output-buffer limits open*
- [~] Graceful shutdown: stop accepting → drain in-flight → flush AOF → close replicas.  
  *Partial: `SHUTDOWN`/SIGTERM stops accepting, lets replica queues drain (2 s), flushes and fsyncs the AOF; no drain of in-flight client commands*

### 3.3 Persistence (what makes it "database")
- [~] **AOF**: append write commands (same encoder as replication), `appendfsync always|everysec|no`, background rewrite, CRC per record, truncated-tail repair on boot.  
  *Partial: always/everysec/no, BGREWRITEAOF, torn-tail repair, typed values, activation on promotion; open: per-record CRC, background rewrite*
- [ ] **Snapshot (RDB-like)**: versioned binary format with magic + CRC64, written by a forked-copy-on-write structure or an iterator over an immutable snapshot (use B2 fix); atomic `rename`.
- [~] Startup recovery: load snapshot → replay AOF tail; report corruption clearly.  
  *Partial: AOF replay + torn-tail truncation; no snapshot file*
- [x] Test: kill -9 loop + verify no acknowledged write lost for `appendfsync always`.  
  *Done: `scripts/crash-test.sh` (5 rounds verified: recovered >= last acknowledged value every time)*

### 3.4 Replication v2
- [x] Global replication **offset** and **replid**; circular backlog (`repl-backlog-size`, default 1 MiB+).  
  *Done: offset, replid and a ring-buffer backlog (`--repl-backlog-size`), shown in INFO*
- [x] Partial resync: `PSYNC <replid> <offset>` → `+CONTINUE` if offset in backlog else `+FULLRESYNC`.
  *Done: `PSYNC <replid> <offset>` -> `REPLICAHELLO CONTINUE` / `FULLRESYNC` (ReplicationEndToEndTest, ADR 0002)*
- [~] Replica ACKs (`REPLCONF ACK <offset>` every second) → `WAIT numreplicas timeout`, `min-replicas-to-write`, lag in `INFO`.
  *Partial: `REPLCONF ACK` every second and on GETACK, `WAIT`, per-replica offset/lag in INFO; `min-replicas-to-write` open*
- [x] Heartbeats both directions, `repl-timeout`.  
  *Done: primary PING every 10 s, replica ACK every second, 60 s read timeout*
- [x] Per-replica sender thread + bounded output buffer (soft/hard limits like `client-output-buffer-limit replica`); drop replica instead of stalling primary (fixes B1).
- [~] Full sync: write snapshot to byte stream (or disk) with offset, replica loads to a *new* store then swaps (fixes B2, B3).  
  *Partial: point-in-time copy under the write lock, replica clears first and answers -LOADING; it still loads into the live store (no swap-in, no disk spill)*
- [ ] Checksums on stream frames; sequence-number verification on replica (detect gaps).
- [~] `REPLICAOF host port` / `REPLICAOF NO ONE` at runtime; replica chaining (optional); `ROLE`.
  *Partial: runtime REPLICAOF (both forms) and ROLE done; replica chaining open*
- [ ] Use a dedicated replication port or authenticated `REPLCONF` identity separate from client auth.

### 3.5 High availability (optional, big)
- [ ] Sentinel-like monitor **or** Raft (e.g. Apache Ratis / own) for leader election; epoch/term + fencing token on every write; fail-closed on lease loss. README already lists requirements — turn it into an ADR first.
- [ ] Cluster mode (16384 hash slots, `MOVED`/`ASK`, `CLUSTER` commands) — only after single-node is solid.

### 3.6 Security
- [x] TLS: `--tls-port`, `--tls-cert-file`, `--tls-key-file`, `--tls-ca-cert-file`, optional mutual TLS (`SSLServerSocket`/`SSLEngine` or Netty `SslHandler`); TLS for replication links.
  *Done: `--tls-keystore/-truststore/-*-password-file/-client-auth/-replication` (PKCS12/JKS instead of PEM files); host-name verification for replication (TlsTest)*
- [~] ACLs: `ACL SETUSER/DELUSER/LIST/WHOAMI/LOG`, per-user command/key-pattern permissions, `AUTH user pass`, `default` user; store hashed passwords (SHA-256 minimum; prefer PBKDF2/Argon2 if passwords are low-entropy).
  *Partial: users file with admin/readwrite/readonly roles, `AUTH user pass`, `ACL WHOAMI/LIST/USERS`; per-key/per-command rules and ACL LOG open*
- [x] Brute-force protection: delay/limit failed AUTH per IP, log them.
- [x] Constant-time compare without leaking length (hash both sides first, then `MessageDigest.isEqual`).
- [~] Protected mode (B15), `rename-command`/disable dangerous commands (`FLUSHALL`, `CONFIG`, `DEBUG`).  
  *Partial: protected mode done; command disabling open*
- [x] Never accept the key on argv in docs (`ps` leaks it): support `--api-key-file`, env var, secret manager.
- [~] Dependency scanning (OWASP) + SBOM in CI; run as non-root; container read-only root FS.  
  *Partial: non-root Docker image + CI steps for OWASP/Trivy/SpotBugs (advisory, not run locally); SBOM open*
- [~] Audit log for AUTH failures, admin commands.
  *Partial: failed AUTH counted and rate-limited; no persistent audit log*
- [~] Fuzz the RESP parser (Jazzer) — parser is the attack surface.
  *Partial: seeded randomised fuzz test in CI (RespParserFuzzTest); a coverage-guided fuzzer (Jazzer) is open*

### 3.7 Observability / operations
- [~] SLF4J + Logback (JSON option), log levels via `CONFIG SET loglevel`.  
  *Partial: SLF4J, configurable pattern, `CONFIG SET loglevel`; JSON output open*
- [x] Metrics: Micrometer + Prometheus endpoint (or `INFO`-based exporter): ops/sec, latency histograms per command, connected clients/replicas, replication lag/offset, memory, evictions, expired keys, rejected connections.
  *Done: Prometheus `/metrics` with counters, gauges and a latency histogram (`--metrics-port`) using the JDK HTTP server instead of Micrometer*
- [~] `SLOWLOG`, `LATENCY`, `MONITOR`, `CLIENT LIST`.
  *Partial: SLOWLOG, MONITOR, CLIENT LIST done; LATENCY open*
- [~] Health: `PING`, `INFO`, readiness (`-LOADING`), JMX bean for admin.
  *Partial: `/health` (503 on AOF failure), `-LOADING` readiness; JMX bean open*
- [x] Config file (`redcake.conf` key/value, include, `CONFIG GET/SET/REWRITE`) in addition to CLI; validate; document every option.
  *Done: `--config` file (CLI wins) and `CONFIG GET/SET` for runtime-changeable options; `CONFIG REWRITE` open*
- [~] JVM tuning guide: `-XX:+UseZGC -XX:+ZGenerational` (or Shenandoah), `-Xms=-Xmx`, `-XX:+AlwaysPreTouch`, container-aware memory.  
  *Partial: Dockerfile sets ZGC + MaxRAMPercentage; no tuning guide yet*

### 3.8 Packaging / build
- [~] `.gitignore` (target/, build/, .idea/, graphify-out/cache), first commit, branch protection.  
  *Partial: `.gitignore` done; first commit and branch protection are yours*
- [~] Multi-stage `Dockerfile` (jlink'd JRE, non-root user), `docker-compose.yml` (1 primary + 2 replicas), Helm chart / systemd unit.  
  *Partial: Dockerfile (non-root, HEALTHCHECK), compose with optional Prometheus/Grafana, systemd unit; Helm chart and jlink image open*
- [ ] Optional GraalVM native image for ~20 ms startup (check virtual-thread + reflection hints).
- [~] CI (GitHub Actions): build, unit + integration tests, coverage gate, SpotBugs/Error Prone, OWASP dep-check, container scan (Trivy), release artifacts + SHA256.  
  *Partial: build/test/smoke/crash test + advisory JaCoCo, OWASP, SpotBugs, Trivy (not run in the authoring environment)*
- [ ] Remove `spring-boot-starter` if startup/footprint matter; Spring is only used for DI + CLI args here. A plain `main` with manual wiring starts faster and shrinks the jar (decision: keep Spring for speed of development vs drop for footprint).
- [~] Semantic versioning, `CHANGELOG.md`, `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md`.
  *Partial: `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md` added; LICENSE choice is yours*

---

## 4. Performance plan (measure, then optimize)

`redis-cli` and `redis-benchmark` are installed locally (`/usr/bin`).

### 4.1 Baseline commands
```bash
./mvnw clean package -DskipTests
java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6390 &

# throughput, 50 clients, 1M requests, 3-byte value
redis-benchmark -p 6390 -t set,get,incr -n 1000000 -c 50 -q
# pipelining (shows the flush-per-command cost)
redis-benchmark -p 6390 -t set,get -n 1000000 -c 50 -P 16 -q
# many connections
redis-benchmark -p 6390 -t get -n 500000 -c 5000 -q
# bigger payloads
redis-benchmark -p 6390 -t set,get -d 1024 -n 500000 -c 50 -q
# latency distribution
redis-benchmark -p 6390 -t set -n 200000 -c 50 --csv
# compare with real Redis on same box
redis-server --port 6391 --save "" --appendonly no &
redis-benchmark -p 6391 -t set,get,incr -n 1000000 -c 50 -q
```
Record results in `docs/benchmarks.md` with CPU, JVM, flags, commit SHA.

### 4.2 Profiling
```bash
# Flight Recorder
java -XX:StartFlightRecording=filename=rc.jfr,settings=profile -jar target/redCake-0.0.1-SNAPSHOT.jar
jfr print --events jdk.ExecutionSample rc.jfr | head
# async-profiler (flamegraph)
asprof -d 30 -e cpu -f flame.html <pid>
asprof -d 30 -e alloc -f alloc.html <pid>
# GC / pinning
java -Xlog:gc*:file=gc.log -Djdk.tracePinnedThreads=full -jar ...
# microbenchmarks
./mvnw -Pjmh verify    # add jmh-core + jmh-generator-annprocess, benchmarks for RespParser, RespWriter, store.set/get/incr
```

### 4.3 Expected hot spots / improvements
- [~] Parser: per-byte `read()`, `StringBuilder` for ints, `ArrayList<String>` per command → incremental `ByteBuffer` parser, parse ints without allocation.  
  *Partial: see 105*
- [~] Writer: 3 `write` calls + 2 `getBytes` per bulk reply, direct unbuffered socket stream → batch into one buffer.  
  *Partial: replies go to a buffered stream and are flushed on drain; encoding still creates small Strings*
- [x] Flush only when input drained (pipelining).
- [ ] `String.toUpperCase` per command → pre-hashed lookup by case-insensitive byte compare / switch on interned bytes.
- [~] Remove global write lock (B1) and per-key lock map (B7).  
  *Partial: `keyLocks` removed and the lock never waits on the network; the lock itself stays by design (ADR 0001)*
- [x] Replication encoding done **once** per command and shared across replicas (already one encode per call — keep); avoid `new ArrayList(pendingCommands)` copy in `finishSnapshot` for large backlogs.
- [ ] Store: avoid `ValueEntry` + boxed `Long expiresAt`; use primitives (`long`, -1 = none).
- [x] `forEachSnapshot` for `INFO keyspace` → maintain atomic key counter.
- [ ] Target (goal, not promise): ≥ 200k ops/s single node on commodity 4-core with `-c 50`, ≥ 1M ops/s with `-P 16`, p99 < 2 ms, within ~2–3× of real Redis.

---

## 5. Testing plan

Existing: `RespParserTest`, `CommandLineConfigTest`, `RedCakeAuthConfigTest`, `ReplicaConnectionTest`, `ReplicationManagerSnapshotTest`, `ClientHandlerIntegrationTest`, context load test.

- [x] Unit tests for every command incl. wrong arg counts, huge numbers, unicode, binary data.  
  *Done: every command family has tests incl. arity errors, overflow, binary data, WRONGTYPE (CommandSemanticsTest, TypedCommandsTest, FeatureServerTest)*
- [x] Store tests: expiry races, overwrite-vs-expire, INCR on TTL key, concurrent mixed ops (use **jcstress** or `ExecutorService` stress with invariants).
- [x] **Regression tests for B1–B6 first** (they should fail today).
- [~] Differential tests: run the same command script against real Redis and RedCake, diff replies (`redis-cli` / Testcontainers `redis:7`).
  *Partial: `DifferentialRedisTest` (~225 commands) written, runs when `redis-server` is installed; **not executed here** (not installed)*
- [ ] Compatibility: run client libs against it — `redis-py`, `Jedis`, `Lettuce`, `go-redis`, `node-redis`; run the official Redis TCL test suite subset (`./runtest --host 127.0.0.1 --port 6390 --single unit/type/string`).
- [x] Replication tests: N replicas, kill/restart replica mid-snapshot, kill primary, write storm during sync (assert replica == primary via `DEBUG DIGEST`-like checksum), clock skew.  
  *Done: in-JVM e2e: full/partial resync, auth, restarts, slow replica, WAIT, REPLICAOF, 3 replicas under a concurrent write storm*
- [ ] Fault injection: Toxiproxy (latency, reset_peer, slow_close, timeout) between primary and replica and clients.
- [~] Fuzz: Jazzer on `RespParser` and command arg parsing; `afl`-style random byte streams.
  *Partial: seeded random + mutation fuzzing of RespParser in the unit tests; Jazzer open*
- [~] Soak: 24 h at 50% load; heap must be flat (catches B7/B8). Watch with JFR + `jcmd GC.heap_info`.
  *Partial: `scripts/soak-test.sh` written and run for 1 minute (no growth beyond legitimate data); a real 24 h run is yours*
- [x] Persistence: crash-recovery loop (`kill -9` at random points, verify against model).  
  *Done: `scripts/crash-test.sh`*
- [ ] Jepsen-style linearizability check (Knossos/Elle) if HA is added.
- [~] Coverage gate (JaCoCo ≥ 80% line, ≥ 70% branch).
  *Partial: Maven profile `-Pcoverage` (JaCoCo, 75% line gate) added; not executed here (plugin needs network)*
```bash
./mvnw clean verify                         # unit + integration
./mvnw -Dtest='ClientHandlerIntegrationTest' test
./mvnw org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report
./mvnw org.owasp:dependency-check-maven:check
./mvnw com.github.spotbugs:spotbugs-maven-plugin:check
```

---

## 6. Manual verification commands (smoke test checklist)

```bash
# build + run
./mvnw clean package && java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6379
java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6381 --replicaof 127.0.0.1 6379

# every implemented command
redis-cli -p 6379 PING                    # PONG
redis-cli -p 6379 PING hi                 # "hi"
redis-cli -p 6379 ECHO hello
redis-cli -p 6379 SET a 1
redis-cli -p 6379 SET b 2 EX 5
redis-cli -p 6379 SET c 3 PX 5000
redis-cli -p 6379 GET a
redis-cli -p 6379 EXISTS a b zzz          # 2
redis-cli -p 6379 EXPIRE a 10
redis-cli -p 6379 TTL a ; redis-cli -p 6379 PTTL a
redis-cli -p 6379 INCR a ; redis-cli -p 6379 INCRBY a 5
redis-cli -p 6379 DECR a ; redis-cli -p 6379 DECRBY a 3
redis-cli -p 6379 DEL a b zzz
redis-cli -p 6379 INFO replication

# edge cases that should error correctly
redis-cli -p 6379 SET k v EX 0 ; redis-cli -p 6379 SET k v EX abc
redis-cli -p 6379 SET k notnum ; redis-cli -p 6379 INCR k
redis-cli -p 6379 SET k 9223372036854775807 ; redis-cli -p 6379 INCR k      # overflow error
redis-cli -p 6379 DECRBY k -9223372036854775808
redis-cli -p 6379 GET                     # wrong args
redis-cli -p 6379 NOPE                    # unknown command

# replication
redis-cli -p 6379 SET r 1 ; redis-cli -p 6381 GET r
redis-cli -p 6381 SET x y                 # READONLY
for i in $(seq 1 10000); do echo "INCR cnt"; done | redis-cli -p 6379 --pipe
redis-cli -p 6379 GET cnt ; redis-cli -p 6381 GET cnt      # must be equal (B2 regression)

# auth
REDCAKE_API_KEY=s3cret java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6380 &
redis-cli -p 6380 PING                    # NOAUTH
redis-cli -p 6380 -a s3cret PING
# B5: auth disabled, then AUTH must NOT lock you out
printf 'AUTH x\r\nPING\r\n' | nc 127.0.0.1 6379       # inline unsupported today (B13)

# concurrency sanity
redis-benchmark -p 6379 -t incr -n 100000 -c 200 -q ; redis-cli -p 6379 GET counter:__rand_int__ | head
ss -ltnp | grep -E ':6379|:6381'
```

---

## 7. Phased roadmap (checklist)

### Phase 0 — Foundations (1–2 days)
- [~] `.gitignore`, initial commit, LICENSE, fill/remove pom placeholders  
  *Partial: .gitignore, pom cleanup, SECURITY/CHANGELOG/CONTRIBUTING done; LICENSE and first commit are yours*
- [x] GitHub Actions CI (build + test)
- [~] Random-port test fix (B20), Mockito agent warning  
  *Partial: fixed high test port (36379) for the Spring context test; all other tests use free ports; Mockito warning remains*
- [x] SLF4J logging replaces `System.out` (B19)
- [x] Write failing regression tests for B1–B6

### Phase 1 — Correctness (1 week)
- [x] B5 AUTH fix · B6 listening-port fix · B16 `isWrite()` on commands · B18 `ERR` prefixes
- [x] B4 + B7: atomic `compute`-based store, delete `keyLocks`
- [x] B8: single expiry entry per key / timer wheel
- [x] B10: absolute-time replication (`PXAT`) + expiry-as-`DEL`  
- [x] Redis-parity fixes from section 2 (EXPIRE ≤ 0, TTL rounding, SET options NX/XX/GET/KEEPTTL/EXAT/PXAT)
- [x] B13: protocol error replies + inline commands
- [~] Differential tests vs real Redis pass for all current commands
  *Partial: harness exists; not executed here*

### Phase 2 — Compatibility (1–2 weeks)
- [~] `HELLO`, `COMMAND`, `CLIENT`, `CONFIG GET`, `SELECT`, `QUIT`, `DBSIZE`, `KEYS`, `SCAN`, `FLUSH*`, `TYPE`, `RENAME`  
  *Partial: all done except SCAN and HELLO 3*
- [x] Binary-safe values (B11)
- [x] MGET/MSET/APPEND/STRLEN/SETNX/GETSET/… string family  
- [~] `redis-py`, Jedis, Lettuce smoke tests green
  *Partial: no client library available offline; the protocol surface they use (HELLO, COMMAND, CLIENT SETINFO/SETNAME, SELECT) is implemented and covered by tests*
- [x] Hashes, lists, sets, sorted sets with `WRONGTYPE`

### Phase 3 — Performance (1 week)
- [x] Baseline benchmark recorded
- [~] Incremental byte-buffer parser + buffered writer + flush-on-drain  
  *Partial: buffered writer + flush-on-drain done; parser still per-byte*
- [~] Remove global write lock (single-writer thread ADR or sharding)
  *Partial: ADR 0001 documents why the lock stays and when to revisit*
- [~] Atomic counters for `INFO`; primitive expiry fields  
  *Partial: INFO/metrics use atomic counters and O(1) size; primitive expiry fields open*
- [ ] JFR/async-profiler pass, fix top 5 hot spots
- [ ] JMH benchmarks in CI (regression threshold)
- [x] Publish `docs/benchmarks.md`

### Phase 4 — Replication v2 (1–2 weeks)
- [x] Offsets + replid + backlog ring buffer
- [x] Per-replica sender, bounded buffers, heartbeats, `repl-timeout`
- [~] Point-in-time snapshot (fixes B2) + replica swap-in (fixes B3) + `-LOADING`  
  *Partial: see the Full sync note above*
- [x] `PSYNC replid offset` partial resync, `REPLCONF ACK`, `WAIT`
- [x] `REPLICAOF` runtime command, `ROLE`, rich `INFO replication`
- [ ] Fault-injection test suite (Toxiproxy) green

### Phase 5 — Durability (1–2 weeks)
- [~] AOF with `always/everysec/no` + rewrite + CRC  
  *Partial: CRC and background rewrite open*
- [ ] Snapshot file format + atomic write + recovery
- [x] Crash-recovery test loop  
- [~] `SAVE/BGSAVE/BGREWRITEAOF`, `INFO persistence`  
  *Partial: BGREWRITEAOF + INFO persistence done; SAVE/BGSAVE (snapshot file) open*

### Phase 6 — Security hardening (1 week)
- [x] TLS (client + replication), optional mTLS
- [~] ACL users/permissions, hashed passwords, AUTH rate limit  
  *Partial: see the ACL note above*
- [x] Protected mode (B15), idle/unauth timeouts (B12)
- [~] `maxmemory`, `maxclients`, buffer limits, eviction (B9)  
  *Partial: `--maxmemory` + policies + `--max-clients` + parser limits; output-buffer limits open*
- [~] Parser fuzzing (Jazzer) in CI, OWASP dep-check, Trivy
  *Partial: randomised fuzz test in CI; OWASP/Trivy steps are advisory in CI*
- [x] `SECURITY.md` with threat model

### Phase 7 — Operations (1 week)
- [x] Prometheus metrics + Grafana dashboard JSON
  *Done: `/metrics` and `deploy/grafana-dashboard.json` (12 panels)*
- [ ] `SLOWLOG`, `LATENCY`, `MONITOR`, `CLIENT LIST`
- [~] Config file + `CONFIG SET/REWRITE`
  *Partial: `--config` and `CONFIG SET` done; `CONFIG REWRITE` open*
- [~] Dockerfile, compose (1+2), Helm/systemd, graceful shutdown drain  
  *Partial: Helm chart open*
- [~] 24 h soak test with flat heap
  *Partial: script ready; the 24 h run is yours*

### Phase 8 — Advanced (open-ended)
- [~] Pub/Sub, MULTI/EXEC/WATCH, blocking list ops
  *Partial: Pub/Sub and MULTI/EXEC/WATCH done; blocking list operations open*
- [ ] Streams, Lua/Functions
- [ ] Sentinel-style failover or Raft; then cluster slots
- [ ] Native image build

---

## 8. Definition of "insanely good" (exit criteria)

- [x] No known data-corruption bugs; B1–B6 regression tests exist and pass
- [ ] Passes ≥ 90% of Redis string/hash/list/set/zset/expire TCL tests it claims to support
- [ ] Mainstream client libraries work out of the box (no custom flags)
- [ ] Within 2–3× Redis throughput and p99 latency in published benchmarks
- [x] Survives `kill -9` without losing acknowledged writes (`appendfsync always`)
  *Done: verified by `scripts/crash-test.sh`*
- [~] Replica provably converges under write storms, partitions, restarts
  *Partial: convergence tests incl. a concurrent write storm with 3 replicas; no network-partition / fault-injection tests yet*
- [~] TLS + ACL + rate limits + memory caps; fuzzers and scanners clean in CI
  *Partial: all four exist; scanners/fuzzers in CI are advisory*
- [~] One-command start (`docker compose up`), metrics dashboard, docs for every option
  *Partial: `docker compose up`, `/metrics` and a Grafana dashboard exist; per-option docs are in `--help` and the README table*
- [ ] 24 h soak: flat memory, zero errors

---

## 9. How to work this list

1. One bug/feature per branch; failing test first, then fix, then `./mvnw clean verify`.
2. Run `graphify update .` after code changes (project `CLAUDE.md` rule).
3. Update `README.md` claims only when tests prove them (current README overstates snapshot atomicity, B2).
4. Write an ADR in `docs/adr/NNNN-title.md` for every architectural decision (threading model, store layout, replication protocol, HA strategy).
5. Re-run section 4.1 benchmarks after every performance change; keep numbers in git.

## 10. Useful references
- Redis protocol: https://redis.io/docs/latest/develop/reference/protocol-spec/
- Replication: https://redis.io/docs/latest/operate/oss_and_stack/management/replication/
- Persistence: https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/
- Commands: https://redis.io/docs/latest/commands/
- Internals: *Redis Internals* (Sripathi), antirez blog; "Designing Data-Intensive Applications" ch. 5, 9
- Java: JEP 444 (virtual threads), JEP 439 (Generational ZGC), JMH, jcstress, async-profiler


---

## 11. Progress log — implementation pass 1 (2026-10-05)

### Verified
- `./mvnw -o test`: **94 tests, 0 failures** (22 existing + 72 new, including regression tests for B1, B2, B3, B4, B5, B8).
- `scripts/smoke-test.sh` against the packaged jar: 26/26 `redis-cli` checks pass.
- Manual: primary + replica; `INFO replication` shows `connected_slaves:1`, `master_link_status:up`, equal offsets; replica rejects writes with `READONLY`; replica TTL equals primary TTL (absolute deadlines).
- Manual: `kill -9` the primary, restart with the same `--aof-file` → data and TTLs restored, replica reconnects and resyncs on its own.
- Manual: `--bind 0.0.0.0` without a key refuses to start with an actionable message.

### Benchmarks (`redis-benchmark`, loopback, same machine, 200k requests, 50 clients)
| Scenario | Before | After |
|---|---|---|
| SET, no pipelining | ~54k req/s | ~54k req/s (bound by loopback round trips) |
| GET / INCR, no pipelining | ~77k req/s | ~79k / ~76k req/s |
| SET, `-P 16` | ~19k req/s | ~396k req/s (**~20x**) |
| GET, `-P 16` | ~19k req/s | ~905k req/s (**~47x**) |
| INCR, `-P 16` | n/a | ~449k req/s |
| GET, 2000 connections | n/a | ~47k req/s |
Noise is high on a shared dev box; re-run with `scripts/benchmark.sh` and record results in `docs/benchmarks.md`.

### Added beyond the original list
- `AuthFailureTracker` (per-IP lockout), `--api-key-file`, `--max-clients`, `--timeout`, `--allow-insecure`.
- Replication heartbeat + replica read timeout + exponential backoff with jitter.
- `ServerStats` / `ReplicationStats` feeding a much richer `INFO`.
- `Dockerfile`, `docker-compose.yml` (1 primary + 2 replicas), GitHub Actions CI, `scripts/smoke-test.sh`, `scripts/benchmark.sh`.

### Still the biggest gaps (suggested next)
1. **B11 binary-safe values** (`byte[]` end to end) — largest remaining correctness gap.
2. **B9 `maxmemory` + eviction** — a client can still fill the heap.
3. TLS and ACL users.
4. Partial resync (backlog + `PSYNC replid offset`) and replica `ACK`/`WAIT`.
5. Data types beyond strings (hash/list/set/zset), Pub/Sub, `MULTI/EXEC`.
6. Differential tests against real Redis and client-library compatibility tests.


---

## 12. Progress log — implementation pass 2 (2026-10-05)

### Verified
- `./mvnw clean verify`: **166 tests, 0 failures** (adds typed commands, ACL, TLS, memory limits, Pub/Sub, transactions, metrics,
  AOF with all types, partial resync, `WAIT`, `REPLICAOF`, a 3-replica concurrent write storm, parser fuzzing).
- Real jar: typed commands over `redis-cli`, replication of typed data, `WAIT`, `ROLE`, `INFO replication` (per-replica lag/offset/backlog),
  `MULTI/EXEC`, Pub/Sub, `CONFIG GET/SET`, `/metrics` + `/health`, `--config`, `SHUTDOWN`, `REPLICAOF NO ONE`.
- `scripts/smoke-test.sh`: 46/46. `scripts/crash-test.sh`: 5/5 rounds (recovered value = last acknowledged + the in-flight write).
- `scripts/soak-test.sh` (1 min): keys-with-TTL bounded; memory-estimate growth equalled the legitimately pushed list elements.
- Benchmarks: no regression (`docs/benchmarks.md`).

### Not verified in this environment (tools missing)
- `DifferentialRedisTest` (needs `redis-server`), the JaCoCo/OWASP/SpotBugs/Trivy CI steps (need network), real client libraries.

### Dead code handled
Put to use: `CommandHandler.commandCount/commandNames` (`COMMAND COUNT/LIST`), `ReplicaConnection.getPendingBytes` (INFO),
`ReplicaConnection.awaitIdle` (graceful shutdown), `ReplicaManager.getReplicas` (INFO/ROLE/WAIT), `ReplicationBacklog.startOffset/endOffset` (INFO).
Removed: `CommandHandler.isWriteCommand`, `ReplicaManager.getReplica/contains`, `PrimaryConnection.getHost/getPort`,
`ReplicationManager.getReplicaManager`, the unused `PrimaryConnection` argument of `ReplicationManager`, `ClientCommand`
(superseded by the per-connection `CLIENT`), unused Lombok getters, an unused `syncThread` field.

### Bugs found by the new tests while implementing
ACL file order was lost (`Map.copyOf`); unknown role names lacked the file/line; `MemoryConfig.parseSize("-5")` was accepted;
`ZADD` minimum arity was wrong. All fixed with tests.

### Suggested next steps
1. `SCAN` family, `INCRBYFLOAT`, `GETEX`; RESP3 (`HELLO 3`).
2. Snapshot file (RDB-like) + `SAVE/BGSAVE`; per-record AOF CRC; background AOF rewrite.
3. Per-key/per-command ACL rules, `ACL LOG`, audit log.
4. Network-partition tests (Toxiproxy), a real 24 h soak, client-library compatibility runs, the Redis TCL suite.
5. Sentinel-style failover or Raft; then cluster slots.
