# Benchmarks

Reproduce with:

```bash
./mvnw -q clean package -DskipTests
java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6390 &
scripts/benchmark.sh 6390 200000
```

## 2026-10-05 — pass 1 (loopback, shared dev machine, JDK 22, 200k requests)

| Scenario | Before | After |
|---|---|---|
| SET, 50 clients | ~54k req/s | ~54k req/s |
| GET, 50 clients | ~77k req/s | ~79k req/s |
| INCR, 50 clients | ~77k req/s | ~76k req/s |
| SET, 50 clients, `-P 16` | ~19k req/s | ~396k req/s |
| GET, 50 clients, `-P 16` | ~19k req/s | ~905k req/s |
| INCR, 50 clients, `-P 16` | — | ~449k req/s |
| GET, 2000 clients | — | ~47k req/s |
| SET/GET, 1 KiB values | — | ~75k / ~82k req/s |

**Why pipelining improved:** the old handler flushed the socket after every reply and wrote each reply
as 3 separate syscalls. The new handler buffers replies and flushes only when no more request bytes are
waiting (`input.available() == 0`).

**Why non-pipelined numbers are flat:** each request is one network round trip, so throughput is limited
by the loopback and thread hand-off, not by RedCake's code. Further gains there need a different I/O model
(event loop) and are tracked in `Task.md` section 4.

Numbers are single runs on a noisy machine; treat differences under ~10% as noise.

## 2026-10-05 — pass 2 (typed store, binary-safe I/O, stats, ACL checks on every command)

Same machine and method, 200k requests. The extra per-command work (role check, command statistics, slow-log check,
memory accounting) did **not** regress throughput.

| Scenario | req/s |
|---|---|
| SET / GET / INCR, 50 clients | ~91k / ~102k / ~107k |
| SET / GET / INCR, 50 clients, `-P 16` | ~368k / ~1.10M / ~553k |
| GET, 2000 clients | ~67k |
| SET / GET, 1 KiB values | ~90k / ~113k |
| LPUSH / SADD / HSET / ZADD (100k, 50 clients) | ~77k / ~104k / ~105k / ~106k |

Single runs on a shared dev machine; differences under ~10% are noise. Memory-estimate growth during `scripts/soak-test.sh`
with `LPUSH` was exactly the pushed elements (50 000 x 48 B per cycle), i.e. no leak; keys with TTL stayed bounded.

## 2026-11-02 — streams (loopback, shared 2-core laptop, JDK 22)

Method and scripts: `scripts/stream-benchmark.sh` (memory, the data structure, a job queue over TCP; with a `redis-server` on the
PATH it runs the same against Redis 7.4.2, built from source) and the regression comparison below. The load generator runs on the
same machine as the server, so every number is a lower bound; differences under ~10-20 % are noise.

**Memory per entry** (heap after a full GC, 100,000 entries per shape):

| Entry | data | RedCake stream | estimate (`maxmemory`) | `TreeMap<id, String[]>` | Redis 7.4.2 (`MEMORY USAGE`) |
|---|---|---|---|---|---|
| event: `type`, `user`, `ts` | 34 B | 68 B | 70 B | 408 B | 38 B |
| job: id, 400-byte prompt, status, priority | 437 B | 463 B | 464 B | 903 B | 497 B |

**Job queue over TCP** (8 workers: `XREADGROUP COUNT 10 BLOCK`, `XACK`; a producer writing `XADD` in batches of 100; 20,000 jobs;
three consecutive rounds on warm servers; the same client for both):

| | round 1 | round 2 | round 3 |
|---|---|---|---|
| RedCake, jobs/s end to end | 10,951 | 15,784 | 17,098 |
| Redis 7.4.2, jobs/s end to end | 17,244 | 19,008 | 20,122 |
| RedCake, wake-up of an idle worker (p50 / p99) | 0.30 / 2.47 ms | 0.26 / 1.63 ms | 0.29 / 2.24 ms |
| Redis 7.4.2, wake-up (p50 / p99) | 0.22 / 1.13 ms | 0.21 / 1.60 ms | 0.22 / 1.34 ms |

(Round 1 includes JIT warm-up.) The wake-up row is: one job every 2 ms for 8 workers that are all waiting; the time from the
`XADD` to the entry reaching a worker.

**The data structure alone**, one thread, 1,000,000 entries of the job shape (three rounds; the last two are warm): add 2.4-7.2 M
entries/s; `XRANGE ... COUNT 100` from a random id 20,000-30,000 reads/s; `XREADGROUP COUNT 10` + `XACK` 2.5-4.0 M entries/s;
`XTRIM MAXLEN 500000` of 1,000,000 entries 1.5-3.3 ms.

**Existing commands** (`redis-benchmark -c 50 -P 16 -d 64 -n 300000`, a fresh JVM per run, one warm-up run, medians of
6 pairs of runs in alternating order; the server pinned to CPUs 0 and 2, the load generator to 1 and 3; old = the previous
commit, new = with streams):

| | old | new | ratio (median of the pairs) |
|---|---|---|---|
| SET | 401k/s | 358k/s | 94 % |
| GET | 682k/s | 655k/s | 97 % |
| INCR | 478k/s | 431k/s | 94 % |
| HSET | 352k/s | 319k/s | 95 % |
| ZADD | 379k/s | 355k/s | 100 % |
| LPUSH (one growing list, O(n) head insert) | 49k/s | 46k/s | 97 % |

Single pairs ranged from 66 % to 157 % (the machine's load average was above 3 on four hardware threads), so the table says "nothing
that stands out from the noise", not "94 %". Two earlier comparisons (not pinned, the old jar always first) gave medians between 82 %
and 118 % per command (SET, GET, INCR and LPUSH moved in both directions, HSET and ZADD stayed at 82-92 %); the first showed the writes 8-12 % lower, which led to making the
thread-local bookkeeping that only `XREADGROUP`, `XCLAIM` and `XAUTOCLAIM` need opt-in (`RedCakeCommand.reportsEffects`) instead of
something every write pays for.

A follow-up run of 10 more pairs for `HSET` and `ZADD` alone (the two that had come out lowest in the earlier comparisons; 400,000
requests each, same method) gave medians of the pairs of 94.5 % and 99.7 % (single pairs 45-115 % and 70-119 %): a possible loss of a few
percent for `HSET` that this machine cannot separate from its own noise, and nothing for `ZADD`. Nothing on the code path of a hash
write differs from that of a sorted-set write except the value type.
