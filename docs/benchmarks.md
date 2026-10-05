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
