# ADR 0008 — Streams and consumer groups

Status: accepted (a new value type, a way for a command to wait, and a way for a command to report what it did; nothing
changes for existing commands)

## Context
Pub/Sub is fire and forget. A subscriber that is down when a message is published never sees it, nothing is remembered,
nothing is acknowledged, and one message goes to *every* subscriber. That is the wrong tool for the work a fleet of agents
does - "summarize this page", "embed this record", "call this tool" - where each job must be

* **kept** until somebody has done it, across restarts of the server (the AOF) and across a failover (replication);
* given to **one** worker, not to all of them, and to a *different* worker for each job;
* **retried** when the worker that took it dies without finishing, without the producer noticing;
* **waited for**: an idle worker must not poll, and must be woken within a millisecond of a job arriving;
* and visible: how many jobs are waiting, who has what, for how long.

Constraints, from the project itself (they are those of ADR 0006 and 0007):
* **Do not slow down what RedCake already does.** Writes are serialized by one lock (ADR 0001); *no wait, of any kind,
  may happen inside it.* A reader that waits for a job must not hold it.
* **Same guarantees as every other type:** the AOF, replication (full and partial), failover (ADR 0005), `maxmemory`,
  `WATCH`, ACL roles, `WRONGTYPE`.
* **A database on the JVM:** a stream is the type that grows for ever by design; the cost per entry must not be the cost of
  a node per entry.
* **Determinism.** A command that hands out "whatever is new right now" must leave primary, replicas and the log in the
  same state, which replaying the command itself cannot guarantee.

## Decision

### API: the Redis Streams command family, not a new queue API
`XADD XLEN XRANGE XREVRANGE XREAD XDEL XTRIM XSETID XINFO` and the consumer-group half `XGROUP XREADGROUP XACK XPENDING
XCLAIM XAUTOCLAIM`, with Redis 7's syntax, replies and error texts - and one internal command, `XRESTORE`
(admin only, below). The client libraries that speak Streams (`redis-py`'s `xadd`, `xreadgroup`, `xautoclaim` ...) work
unchanged.

Alternatives considered:
* **Make Pub/Sub durable.** It would keep Pub/Sub's one-to-all delivery and add a log: a queue needs one-to-one, which is
  what consumer groups are.
* **A reliable queue out of lists** (`LMOVE` to a processing list, a sweeper to put timed-out jobs back). The pieces are
  there, but RedCake has no blocking list commands, "who took it when" would be convention and not data, and every
  application would write the sweeper again - this is `XAUTOCLAIM`.
* **A new `QUEUE.*` API.** No client speaks it.

Not implemented (they postdate Redis 7.4): `XDELEX` / `XACKDEL` and the `KEEPREF | DELREF | ACKED` options (Redis 8.2),
`XREADGROUP ... CLAIM` (8.4).

### The semantics are ported, not guessed
The rules of Streams are subtle: the `ms-*` and `(id` forms, what `COUNT 0` means (null array in `XRANGE`, "all" in
`XREAD`), when `lag` is `nil`, what `entries-read` counts after an `XDEL`, what `XAUTOCLAIM` does with an entry whose
message was deleted. They were taken from Redis 7.4's `t_stream.c` and then **checked against a running Redis 7.4.2**,
three ways:

* a recorded transcript (`src/test/resources/streams`): 464 commands in 11 scenarios - ids, ranges, trimming, groups,
  history reads, `XPENDING`, `XCLAIM`, `XAUTOCLAIM`, `lag` - with the reply Redis gave to each, error texts included. The
  test replays them against RedCake on every build; no Redis installation is needed to run it, and a property re-records
  it from a live Redis;
* the same scenario with `redis-py` 8.1 (RESP2) against both servers: 39 of 40 results are identical;
* random workloads that compare RedCake with *itself* (below).

The differences that remain are deliberate:

| | Redis | RedCake |
|---|---|---|
| `MAXLEN ~ n`, `MINID ~ id` | stops at a node boundary, may leave more than `n` | trims exactly (a stream is never longer than asked; `LIMIT` still bounds the work) |
| `radix-tree-keys`, `-nodes` in `XINFO STREAM` | nodes of the radix tree | the number of blocks (and one more) |
| consumers' `seen-time` / `active-time` | not replicated | not replicated |
| a pending entry whose message was deleted | lost by the AOF rewrite, kept by an RDB | kept everywhere (`XRESTORE`) |

### Data structure: blocks of packed entries
A stream is an *append-only* log with deletes and trims at the ends, looked up by an id that only ever grows. The entries
live in **blocks of at most 128**: three parallel arrays per block (the two halves of the id, and the entry) and the field
names of the block's first entry. A lookup is a binary search over blocks and then inside one; an append touches the last
block; `XTRIM` drops whole blocks; a block that is mostly holes is compacted and an empty one dropped.

An entry is **one `byte[]`**: a flag byte and the values, each with its length - *without the field names whenever they are
the block's own*, which is nearly every entry of a real stream because every record has the same shape (Redis does the same
with its "master entry"). Values are byte strings (ADR 0003), so `String.length()` is the byte length and an entry
round-trips exactly. The id is two unsigned 64-bit numbers kept in signed `long`s and compared with
`Long.compareUnsigned` - `18446744073709551615-18446744073709551615` is a valid id and survives.

Groups and consumers are `TreeMap`s by name (the order `XINFO` lists them in, byte order, as Redis's radix tree gives);
a group's *pending entries list* is a `TreeMap` by id, and the `Pending` object (owner, delivery time, delivery count) is
shared by the group's list and the owner's, so a claim is one update.

**Memory** (heap after a full GC, 100,000 entries per shape; Redis's own `MEMORY USAGE` for the same data):

| | data per entry | RedCake | `TreeMap<id, String[]>` | Redis 7.4.2 |
|---|---|---|---|---|
| event: `type`, `user`, `ts` | 34 B | **68 B** (estimate 70) | 408 B | 38 B |
| job: id, 400-byte prompt, status, priority | 437 B | **463 B** (estimate 464) | 903 B | 497 B |

A conventional map-per-entry design costs 2-6 times as much. Redis is ahead for tiny entries - it delta-encodes the ids
and keeps no per-entry object, where RedCake pays 36 bytes (two longs, a reference, an array header) - and RedCake is
slightly ahead once the values dominate. Packing a block into one array with delta-encoded ids would close the gap for
tiny entries; it is not done because the use case (agent jobs: hundreds of bytes and up) does not need it.

The size is kept **incrementally** and exact to the estimate: every change adds or subtracts what it caused, and tests
recount a stream from scratch after thousands of random operations and require the two to be equal. A pending entry is
estimated at 160 bytes.

An **empty stream is a key** (unlike an empty list or hash): it still owns its last id and its groups, and `XGROUP CREATE
... MKSTREAM` makes one.

### Waiting without a lock: `KeyWaiters`
`XREAD ... BLOCK ms` and `XREADGROUP ... BLOCK ms` wait for entries. The connection is a virtual thread, so waiting costs a
parked thread - a few hundred bytes - and no worker. The design is in three parts:

1. **A command may say that it wants to wait** (`RedCakeCommand.blocking`): which keys, how long, and the command line to
   run again with everything that depends on "now" fixed. For `XREAD`, `$` means "the last id when the command arrived"; it
   is replaced by that id, or the entry that arrives while waiting would become "the last id" and be skipped for ever.
2. **`ClientHandler` runs the command; if the reply is the null array ("nothing yet") it parks** in `KeyWaiters` - not inside
   the write lock, and not even inside the *read* path - until a key it waits on is touched, the time is up, or the client
   is gone, and runs the command again. An `XREADGROUP` that waits is therefore an ordinary write each time it runs, with
   its ordinary logging.
3. **A wake-up cannot be lost.** Every key has a version that `wake` increases, after the data has changed. A waiter
   registers, *then* notes the versions, *then* tries the command, and parks only until a version differs from the one it
   noted: a change after the note shows, a change before it is seen by the command itself. `wake` costs one atomic read when
   nobody waits, which is almost always.

What wakes a waiter: `XADD`; `XGROUP SETID` (it can make entries deliverable again) and `XGROUP DESTROY` (the reader should
learn that its group is gone); removal of the key, by any means - `DEL`, expiry, eviction, `FLUSHALL`, `RENAME` away,
replacement by another type (`XREADGROUP` then fails with `NOGROUP` / `WRONGTYPE`, as in Redis). The commands a *replica*
applies call `wake` too, so a reader waiting on a replica is woken by the stream it replicates.

Details that matter:
* **Many readers, one entry.** All readers of a key are woken by an `XADD`, and each runs `worthTrying` first - a cheap read
  under the key's lock saying whether the command could find anything - so the readers whose turn it is not do not take the
  write path to learn that. They are served in no particular order (Redis serves them in the order they blocked); each entry
  is still given to exactly one of them.
* **A client that hangs up while it waits** is noticed: every 200 ms the connection asks its socket with a one-millisecond
  read (end of stream: gone; timeout: quiet; a byte: the client sent its next command, which is put back). Without it an
  `XREADGROUP` would later hand an entry to a client that is not there. The entry would be claimable after the idle time,
  but a job should not be lost to a ghost at all. The server's idle timeout does not apply to a waiting connection (it
  applies to reading the next command), and stopping the server or `CLIENT KILL` ends the wait within the tick.
* **Inside `MULTI`** a blocking command does not wait (it behaves as if `BLOCK` timed out at once), as in Redis.
* A reader on a replica waits for what replication delivers; `XREADGROUP` is a write and gets `-READONLY` there.

### Replication and the AOF: say what was done
Most stream commands are deterministic and are logged as received. Three kinds are not:

* **`XADD ... *`** chooses an id from the clock and from the stream. The command is rewritten, under the write lock where the
  stream cannot change, to carry the id it will get (`XADD jobs 1793608281293-0 ...`, the one rule shared with the command
  itself): replicas and the replayed log store the same id, not a second reading of their own clocks.
* **`XREADGROUP`, `XCLAIM`, `XAUTOCLAIM`** depend on the clock and on what the stream holds at that moment (`>` hands out
  whatever is new; `XAUTOCLAIM` whatever has been idle long enough). Replaying the command elsewhere would give different
  answers. They report **what they did** (`Propagation.instead`), as plain commands that every node replays the same way:
  `XCLAIM key group consumer 0 id TIME t RETRYCOUNT n FORCE JUSTID LASTID id` per entry delivered, `XGROUP SETID key group id
  ENTRIESREAD n` for the group's new position, `XGROUP CREATECONSUMER` when a consumer came into being, `XACK` for pending
  entries whose message is gone. This is Redis's own scheme, with one addition: a *history* read also reports the new
  delivery time and count of the entries it returned, so that replicas agree about those as well. The server logs these
  instead of the command - also when the command fails half way after changing something - and a command that did nothing
  logs nothing.
* **Snapshots** (AOF rewrite, full sync, promotion) are `XADD` per entry in order (or `XADD key MAXLEN 0 1-1 x y` for a stream
  without entries), `XSETID key <last id> ENTRIESADDED n MAXDELETEDID id` for the history no entry shows, and per group
  `XGROUP CREATE`, `XGROUP CREATECONSUMER` and `XRESTORE key group consumer id time count` for every pending entry,
  produced lazily from a private copy taken under the key's lock, so a stream of millions of entries is never held twice as
  commands.

`XRESTORE` exists because `XCLAIM ... FORCE` refuses a pending entry whose message was deleted (`XDEL`, `XTRIM`) - which
Redis allows to exist, and which Redis's AOF rewrite therefore loses. A snapshot must not change what a client sees, so it
uses its own command; it is admin only, like `VRESTORE`, and replicas and the AOF run it without a role.

How this was checked (all randomized, thousands of operations over groups, `NOACK`, history reads, claims, deletes, trims,
`XSETID`, `XGROUP SETID`, consumer removal): a replica that replays what the primary logged ends with the same
`XINFO STREAM ... FULL` as the primary (25 seeds); a node rebuilt from a snapshot likewise (20 seeds); and with real
processes, primary, replica, `kill -9` plus AOF replay, `BGREWRITEAOF` plus restart, and a replica that joins late all
agree.

### Delivery guarantee
**At least once.** An entry handed to a worker stays pending until `XACK`; if the worker dies or is slow, `XAUTOCLAIM`
(or `XCLAIM`) gives it to another, and a worker that was only slow will see its entry processed twice. Jobs therefore need
to be idempotent, or to check `XPENDING`'s delivery count (the count of times an entry was handed out is the retry counter;
a dead-letter stream is `XADD` plus `XACK` once it passes a limit). Exactly once is not offered by any of the systems this
API comes from.

### Limits and security
* Arguments are bounded by the parser (1,024 words, 1 MiB per argument, 8 MiB per command), so an entry has at most about 500
  fields and a value at most 1 MiB; `XDEL` and `XACK` take about 1,000 ids.
* A stream counts toward `maxmemory`; `XADD`, `XGROUP` and the claiming commands are refused with `-OOM`, `XDEL`, `XTRIM` and
  `XACK` are not. Streams are evicted whole, like any key.
* A blocked connection is a virtual thread and a registration: `--max-clients` bounds them.
* `XAUTOCLAIM` looks at no more than ten times its `COUNT` of pending entries per call.
* The roles are the usual ones: `XRANGE XREAD XLEN XPENDING XINFO` are reads (allowed to `readonly`; a read may also wait),
  every other stream command is a write, `XRESTORE` needs `admin`.
* Known gap: the cost of a read is the client's choice - `XRANGE key - +` on a stream of millions builds the whole reply;
  use `COUNT`. Likewise an `XREADGROUP` without `COUNT` takes everything that is new.

## Measured
A shared two-core laptop; the load generator runs on the same machine, so it is a lower bound for both servers.

| | RedCake | Redis 7.4.2 |
|---|---|---|
| job queue over TCP, 8 workers, 20,000 jobs (`XADD` in batches of 100, `XREADGROUP COUNT 10 BLOCK`, `XACK`), end to end | 15,800-17,100 jobs/s (11,000 on the first, cold run) | 17,200-20,100 jobs/s |
| wake-up: one job every 2 ms, 8 idle workers, `XADD` to delivery | p50 0.26-0.30 ms, p99 1.6-2.5 ms | p50 0.21-0.22 ms, p99 1.1-1.6 ms |
| the structure alone, one thread (warm): add / `XRANGE COUNT 100` / deliver 10 + ack | 2.4-7.2 M / 20-30 k reads / 2.5-4 M entries per second | |
| `XTRIM` of 500,000 entries | 1.5-3.3 ms | |

Existing commands (`redis-benchmark -c 50 -P 16 -d 64`, the jar of the previous commit against this one, 6 pairs of runs in alternating
order, server and load generator pinned to separate cores; the median of the pairs): SET 94 %, GET 97 %, INCR 94 %, HSET 95 %,
ZADD 100 %, LPUSH 97 % of the old throughput - **nothing that stands out from the noise**: single pairs ranged from 66 % to 157 %
on this loaded machine, and two earlier, cruder comparisons gave medians between 82 % and 118 % per command (SET, GET, INCR and
LPUSH in both directions; HSET and ZADD below 100 % in both, 82-92 %; a follow-up of ten more pairs gave HSET 94.5 % and ZADD 99.7 %). The first of them showed the writes 8-12 % lower, which led to making the thread-local bookkeeping that only
`XREADGROUP`, `XCLAIM` and `XAUTOCLAIM` need opt-in (`RedCakeCommand.reportsEffects`) instead of something every write pays for. Details
in `docs/benchmarks.md`.

## Consequences
* A new value type, a new way for a command to wait (`BlockingRequest`, `KeyWaiters`, a small loop in `ClientHandler`) and
  a new way for a command to report what it did (`Propagation`). The first two are general: **blocking list commands**
  (`BLPOP`, `BRPOP`, `BLMOVE`) would be a few lines each on the same machinery; they are not part of this change.
* `ClientHandler.executeWrite` brackets the command with `Propagation.begin/end` (a thread-local flag) and asks the command
  whether it wants to wait (`dispatch`): one virtual call that returns `null`.
* `InMemoryKeyValueStore` calls `KeyWaiters.wake` when it removes a key (one atomic read when nobody waits).
* `BGREWRITEAOF` of a very large stream runs, like every rewrite, under the write lock: writers pause for its duration.
* Delivery is at least once (see above); exactly once is the application's job.

## Alternatives rejected
* **A `TreeMap` of entries.** Simple, and 2-6 times the memory (table above).
* **Waiting inside the write lock, or with a thread per waiting command.** The first stalls every client; the second is
  what virtual threads make unnecessary.
* **Re-evaluating `$` on every retry.** Never delivers.
* **Replicating `XREADGROUP` as itself.** Replicas would hand out different entries at different times; the first failover
  would show it.
* **A restore built on `XCLAIM ... FORCE`.** Loses pending entries of deleted messages (what Redis's AOF rewrite does).

## Not done
* `XDELEX`, `XACKDEL`, `KEEPREF` / `DELREF` / `ACKED`, `XREADGROUP ... CLAIM` (newer than the Redis this was checked against).
* Delta-encoded ids (a smaller tiny entry), see the table.
* Strict first-come-first-served among readers blocked on one key.
* Per-consumer rate statistics, and `CLIENT LIST` flags for blocked clients (`INFO` reports `blocked_clients`).
* Verified against Redis 7.4.2 only; Redis 8's `XINFO` and `XAUTOCLAIM` outputs were not compared.
