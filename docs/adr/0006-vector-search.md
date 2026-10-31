# ADR 0006 — Vector search and embedding storage

Status: accepted (a new value type; nothing changes for existing commands)

## Context
Applications that work with embeddings - semantic search, retrieval-augmented generation, recommendations,
de-duplication - need to keep high-dimensional vectors next to their keys and ask "which stored vectors are most
similar to this one?". A store that only holds strings forces the application to run a second system for that.

Constraints, from the project itself:
* **Do not slow down what RedCake already does.** Writes are serialized by one lock (ADR 0001); anything slow inside
  it stalls every client.
* **Same guarantees as every other type:** the append-only file, replication (full and partial), failover (ADR 0005),
  `maxmemory`, `WATCH`, ACL roles and `WRONGTYPE` must all work, and a restart must not take hours.
* **An in-memory database on the JVM:** per-object overhead and garbage-collector pressure matter more than usual
  when a single index holds millions of entries.

## Decision

### API: Redis 8 "vector sets"
A new value type, `vectorset` (`TYPE` says so), with the commands `VADD VSIM VREM VCARD VDIM VEMB VINFO VGETATTR VSETATTR
VISMEMBER VRANDMEMBER VLINKS`, modelled on Redis 8, so client libraries that know them need nothing new.

Not RediSearch's `FT.CREATE`/`FT.SEARCH`: that is a secondary-indexing engine (schemas, tokenizers, a query language)
over hashes, a subsystem bigger than the rest of RedCake, where a vector set is one self-contained type like a sorted set.

Differences from Redis 8: `REDUCE` and `FILTER` answer with an error; `CAS` and `NOTHREAD` are accepted and ignored;
`VINFO` has extra fields. The semantics were written from Redis's documentation, **not checked against a Redis 8 server**
(none was available), so treat byte-for-byte compatibility as unverified. Scores follow Redis: `(cosine + 1) / 2`.

### Index: HNSW
A Hierarchical Navigable Small World graph. Sub-linear queries (cost grows with the exploration factor `EF`, not with
the number of elements), high recall, inserts and deletes one element at a time with no training step - unlike IVF/PQ,
which need a representative sample first, and unlike a flat scan, which is O(n) per query. Defaults `M 16`,
build `EF 200`, search `EF 100`. A set with fewer than `2 x EF` elements is scanned exactly, because the graph would
visit most of it anyway.

Two choices make deletes exact and cheap, which most HNSW implementations do not offer:
* **Links are reciprocal** (a links to b exactly when b links to a). Removing an element knows everybody that points
  at it, unlinks it, reconnects its neighbours to each other (most similar pairs first), and frees the slot for reuse:
  no tombstones, no periodic rebuild, memory does not grow with churn. When a node already has the maximum number of
  links, the one to drop is chosen by a diversity test over its few farthest links, and a link is never dropped if that
  would leave its other end without any.
* **The level of an element comes from a hash of its name**, not from a random generator, so the same commands build the
  same graph on the primary, on a replica and when the append-only file is replayed (see "Replication" for the one
  exception).

### Storage: int8 by default
Every vector is normalized to unit length (similarity is the cosine) and stored as
* `Q8` (default): 8-bit integers plus one scale per vector, **a quarter of float32** for a ~1.6 % loss of recall against
  the exact float32 answer (0.984 at EF 100; the graph search itself finds 100 % of what an exhaustive scan of the same
  codes finds). The scale is chosen so that the reconstruction has length exactly 1, so the integer dot product times
  the two scales *is* the cosine of the stored vectors, with no correction per pair;
* `NOQUANT`: float32, exact;
* `BIN`: one bit per dimension, compared by Hamming distance - 32x smaller, coarse (it keeps clusters together but cannot
  rank neighbours inside a tight cluster).

The original length is kept (4 bytes) so `VEMB ... RAW` can return it.

### Memory layout: pages of primitive arrays
No object per element and none per link. Slots (one per element) live in a few primitive arrays - codes, scales, levels,
level-0 links, names - cut into pages of about 256 KB that are added as the set grows and never copied. The garbage
collector sees tens of large arrays instead of millions of small objects, and the name index is an open-addressing table
of ints. Measured: 639 bytes per 384-dimensional vector including its name and links (float32 alone would be 1,536); the
incremental estimate used for `maxmemory` matches the measured heap within 3 %.

For small dimensions the graph dominates: at M 16 the links cost 132 bytes per element, so a 64-dimensional int8 vector
takes about 315 bytes - *more* than its float32 form (256). Use a smaller `M`, or `BIN`, if that matters.

### Distance kernels: portable and SIMD
The inner loop of a search is the dot product. Two interchangeable kernels:
* **portable**: plain Java, always available;
* **SIMD**: the JDK's Vector API (`jdk.incubator.vector`), used automatically when the JVM was started with
  `--add-modules jdk.incubator.vector`. Cache-resident, one 768-dimensional int8 dot product measured 60 ns against
  500 ns portable; fetching vectors from memory at random, as a graph search does, it is still about 3.5x faster.

The SIMD class is created by reflection and only when the module is present, so `java -jar redcake.jar` keeps working
everywhere and says in its log which kernel it chose. The Dockerfile enables the flag when the JVM has the module (a JRE
without it would refuse to start if the flag were unconditional). The int8 sums are exact integers, so **both kernels
produce identical scores and identical graphs on int8 data**; the float32 sums differ in the last bits. The Vector API is
still incubating and has changed between JDK releases; everything that touches it is one 100-line class.

### Concurrency: searches never wait for the write lock
* `VSIM` and the other reads do **not** use the store's per-key section (`read` would serialize all searches on a hot
  index). `KeyValueStore.peek` hands out the live object and the set synchronizes itself with a read/write lock: searches
  run in parallel with each other.
* An insert is two steps of very different weight. Finding the neighbours of the new vector is a read-only graph search
  (about 90 % of the work: 540 us against 53 us in the measurement below); only wiring the node in changes links. So
  `VADD` **does the search before taking the server's write lock** (`RedCakeCommand.prepare`, called on the client's own
  thread, so connections do it in parallel) and keeps only the wiring inside it; the set's exclusive lock is likewise only
  held for the wiring. The search result (a *plan*) stays valid while other elements are merely inserted - they are just
  not among the neighbours, as in any parallel HNSW build - and is thrown away and redone when something was *removed*
  (a neighbour's slot could have been reused) or the top of the graph moved. Commands that run without the prepare step
  (a replica applying the stream, `EXEC`, AOF replay) simply search under the lock; the result is the same.
* Lock order is always: server write lock, then the store's key section, then the set's lock; searches take only the last.

### Persistence and replication
* The AOF and the replication stream carry `VADD` with the vector rewritten to an `FP32` blob (`normalize`), so a
  `VALUES` list is not re-parsed and every node reads identical bits.
* Re-inserting a million vectors takes the better part of an hour, so a **snapshot** (AOF rewrite, full sync to a replica,
  promotion after a failover) is a compact binary *dump of the graph*, not a list of `VADD`s: `VRESTORE key HEADER`,
  `VRESTORE key NODES <first id> <chunk>` (each chunk under 512 KB because the parser caps an argument at 1 MB) and
  `VRESTORE key END`. Elements are renumbered densely, links included. Loading 21,000 vectors took 0.07 s against 17 s
  (SIMD) or 52 s (portable) to insert them again. The dump is larger than a log of plain inserts by the links
  (132 bytes per element at M 16), which is the price of not searching on load.
* `VRESTORE` is administrative (needs the admin role in an ACL) and validates everything it reads: lengths, link counts,
  that every link points at an existing node that reaches the level of the link, that the entry point is the highest
  node. A set refuses searches until `END` has validated it, and a failed restore deletes the key. A test flips random bits in
  dumps and checks that nothing crashes.
* The elements, vectors and attributes of a replica always equal the primary's. The **graph** is equivalent, not always
  identical: a primary that used a plan made before another connection's insert wired its node a little differently from
  a replica that searched under the lock. Search results are approximate by nature; they can differ in rare near-ties.
* Taking the snapshot copies the set into the dump under the set's read lock while the server holds its write lock, like
  every other type; a set of 52,000 vectors took 0.06 s. The transient extra memory is the size of the dump.

### Limits (they bound what one command can ask for)
| Limit | Value |
|---|---|
| dimension | 1 - 16,384 (`VALUES` also needs the whole command under 1,024 arguments: use `FP32` above ~1,000) |
| `EF` (build and search), `COUNT` | at most 10,000 |
| `M` | 4 - 128 |
| element name / attribute | 64 KB / 256 KB |

### Memory accounting
`estimatedBytes()` is kept incrementally (O(1)) from the pages that are really allocated, the names, attributes and the index,
and counts toward `maxmemory`; evicting the key frees the whole set. Because memory is allocated a page at a time, the
first vector of a set "costs" one page (about 150 KB at 128 dimensions): the estimate is what the JVM holds.

## Measured
All numbers on a laptop with 2 cores / 4 threads (i3-1115G4, AVX-512), JDK 22, **shared with a browser and an IDE**
(load average 3 - 4), so absolute values wobbled by 2x from run to run; the table shows the **median of 3 runs that
alternated between the kernels**, min - max in brackets. 20,000 vectors of 384 dimensions (clustered, deliberately not
easy), int8, M 16, `EF 100`, `COUNT 10`. Reproduce with `scripts/vector-benchmark.sh`.

| | SIMD kernel | portable kernel |
|---|---|---|
| insert, one thread | 1,150 / s (990 - 1,650) | 390 / s (390 - 530) |
| search latency p50 / p99 | 0.32 ms / 1.0 ms | 0.72 ms / 3.0 ms |
| searches per second, 1 / 2 / 4 threads | 2,950 / 4,000 / 5,400 | 1,230 / 2,000 / 2,200 |
| exhaustive scan of all 20,000 | 1.4 ms | 6.6 ms |
| recall@10 against an exhaustive scan of the same codes | 1.000 | 1.000 |
| recall@10 against exact float32 cosine | 0.984 | 0.984 |
| one writer + two readers at once | 370 inserts + 2,100 searches / s | 160 + 960 |
| memory per vector (float32: 1,536 bytes) | 639 bytes | 639 bytes |
| load a dump of the 21,000 vectors / insert them again | 0.07 s / 17 s | 0.06 s / 52 s |

Through the server over TCP (15,000 x 384, a load generator on the same machine): `VSIM` 1,360 / s from one client
(p50 0.56 ms) and 6,900 / s from eight, against 700 and 2,800 with the portable kernel; one pipelined connection loads
1,500 `VADD` / s (480 portable). **An ordinary `SET` had a p50 of 38 us while another client streamed about 860 `VADD`
per second, against 34 us on an idle server** (before the search was moved out of the write lock it was 475 us).
The exclusive part of an insert took 53 us against 539 us for the shared-lock search. Inserting from several connections at
once did not get faster on this 2-core machine (the load generator competes for the same cores); in-process it scaled
1,500 -> 2,100 -> 2,650 inserts / s with 1 / 2 / 4 threads.

Other storage, single runs (20,000 x 384, SIMD): float32 recall 1.000 at 1,789 bytes per vector; 1 bit per dimension
298 bytes per vector, recall against float32 only 0.31 (rank inside a cluster is lost) but 4x faster searches.

No regression for existing commands: see "Regression check" in the changelog entry (old and new jar, same
`redis-benchmark`, alternating runs).

## Consequences
* RedCake can be the only store behind a retrieval application; the cost is paid only by keys that are vector sets.
* Recall is not 100 %: it is a function of `EF`, `M` and the quantization, and the numbers above are for one kind of data.
  `VSIM ... TRUTH` does an exact scan when an answer must be exact.
* A vector set is a large single value: eviction removes it whole, a snapshot of it briefly doubles its memory, and the
  hot path of the server gained one default method call (`prepare`) per write.
* `VectorSet` is a large class (about 2,000 lines). It is cohesive - storage, graph and dump share the same arrays - and
  the dump code is the first candidate to split out if it grows.

## Alternatives considered
* **Tombstones and periodic rebuild** for deletes: simpler links, but memory grows with churn and a rebuild of a large index
  takes minutes under a lock.
* **An object per node** (`Node`, `int[]` per level): idiomatic, but at millions of entries the headers and the garbage
  collector's work are a large fraction of the memory.
* **Lock-free concurrent graph** (as in JVector): searches would never wait for an insert at all. Considerably harder to get
  right, and the shared/exclusive split already leaves only ~10 % of an insert exclusive.
* **IVF / product quantization**: smaller, but needs training data and a re-training story; HNSW is incremental.
* **FFI to a native library** (faiss): faster still, but ships native code and another memory manager into a JVM database.
* **`FT.SEARCH` compatibility:** see above.

## Not done
* `FILTER` expressions over the JSON attributes, `REDUCE` (random projection) - the attribute strings are stored and returned.
* Copy-on-write snapshots (the dump copies the set), a background compaction, per-element TTL.
* The pre-lock `prepare` hook is generic, but only `VADD` uses it.
