# ADR 0007 — JSON documents

Status: accepted (a new value type; nothing changes for existing commands)

## Context
Machine-learning applications keep *records*: a text, one or more embeddings, metadata (often nested) and a status
(`new`, `processing`, `done`, a retry counter ...). RedCake could already hold the pieces - strings, hashes, vector sets -
but not the record:

* a **hash** is flat and its values are strings, so nested metadata and an embedding have to be flattened into text
  and parsed again by every reader;
* a record serialized into one **string** is read, changed and written back as a whole: every status change moves the
  whole embedding over the network and two workers updating different fields lose each other's change;
* a record split over several keys cannot be updated atomically.

What is wanted is one key holding a structured document that can be read and changed **by path** - atomically, without
sending the rest - and that replicates, persists and counts toward `maxmemory` like every other type.

Constraints, from the project itself (they are those of ADR 0006):
* **Do not slow down what RedCake already does.** Writes are serialized by one lock (ADR 0001); anything slow inside it
  stalls every client.
* **Same guarantees as every other type:** the append-only file, replication (full and partial), failover (ADR 0005),
  `maxmemory`, `WATCH`, ACL roles and `WRONGTYPE`.
* **A database on the JVM:** a million records of a few kilobytes each must not cost twice their size in objects.
* **Hostile input.** JSON text, paths and the documents that grow out of repeated updates are all client controlled.

## Decision

### API: a JSON type with the RedisJSON commands, not a "document namespace"
A new value type, `ReJSON-RL` (the name `TYPE` reports; it is the type name RedisJSON uses), with
`JSON.SET GET MGET DEL FORGET TYPE NUMINCRBY NUMMULTBY STRAPPEND STRLEN ARRAPPEND ARRINSERT ARRINDEX ARRLEN ARRPOP
ARRTRIM OBJKEYS OBJLEN TOGGLE CLEAR MERGE DEBUG`.

The alternative offered with the requirement was a *document namespace* - `DOC.PUT collection id ...`, collections with
schemas. It would be an API no client library speaks, and a namespace is only useful with queries across documents
("all records whose status is `failed`"), which means secondary indexes: a subsystem larger than the rest of the type.
A JSON value is self-contained like a sorted set, costs nothing for keys that do not use it, and with the RedisJSON
command family the client libraries that know it work unchanged - checked with `redis-py` 8.1 (`r.json()`: set, get by
path, numincrby, arrappend, arrinsert, arrtrim, merge, mget, clear, pipelines ...).

`JSON.MSET`, `JSON.RESP` and `JSON.NUMPOWBY` are not implemented. The semantics were written from RedisJSON's
documentation and are **not checked against a Redis Stack server** (none was available): treat byte-for-byte reply
compatibility as unverified.

### Paths
Both dialects of RedisJSON: **JSONPath** (starts with `$`; any number of matches; replies are arrays with one entry per
match) and **legacy** (`.a.b[0]`; one value; a missing value is an error). Left out, a path is the legacy root `.`, as in
RedisJSON, so `JSON.GET doc` and `JSON.TYPE doc` answer with the bare value.

JSONPath supports names (`.a`, `['a b']`), indexes (negative too), `*`, recursive descent (`..a`, `..*`), slices with a
step, unions (`[0,2]`, `['a','b']`) and filters (`[?(@.price < 10 && @.status == "ok")]` with `== != < <= > >=`,
`&& || !`, `@` and `$` paths, literals, and a bare path as an existence test). The semantics follow RFC 9535 where
RedisJSON's documentation is silent (a union may select a value twice; `!=` is the negation of `==`; ordering a missing
value is false).

Evaluation returns the matches *with their location* (parent, key, depth), so the same code serves reads and changes. It
was checked against a reference implementation (Jayway JsonPath) on more than 5,000 random paths over random documents
for the subset both implement; the differences found were Jayway's: it answers a union of names with one object instead of
the values, and reads a step after `..*` differently from the RFC.

Paths are client input, so they are bounded: 8,192 bytes, 32 levels of nested filter expressions, and **one budget of 10
million node visits per evaluation that nested filters share** - `$..*..*..*..*..*..*` is polynomial in the size of the
document and `$..[?($..[?($..*)])]` cubic, and both are abandoned with an error instead of holding a thread and the
key's lock.

### Value model: what a record costs
* **Strings are byte strings** (ADR 0003): the UTF-8 the client sent, one char per byte. Nothing is decoded or encoded
  again, so a document comes back byte for byte; the parser *validates* UTF-8 (overlong forms, surrogates, > U+10FFFF) and
  lone surrogates in `\u` escapes, so every stored document is valid JSON for any other reader. Lengths are in bytes, as
  in RedisJSON.
* **Numbers** are `Long` when written without fraction or exponent and within 64 bits, `Double` otherwise (an integer
  outside 64 bits becomes a double, as in most JSON libraries; RedisJSON keeps unsigned values up to 2^64-1). The
  distinction survives a round trip: `1` does not come back as `1.0`. Doubles are written with the shortest text that reads
  back as the same double.
* **Objects** keep insertion order. Up to 16 members they are two parallel arrays searched linearly (a record has a
  handful of members, and a `LinkedHashMap` costs a table and 40 bytes per entry for them); beyond that, a `LinkedHashMap`.
* **Arrays of numbers are stored unboxed.** An embedding is hundreds of numbers; as `Object[]` of `Double` it costs about
  20 bytes a number, as a `double[]` 8. An array holding only integers is a `long[]`, only non-integral numbers a
  `double[]`; anything else, or the first value that does not fit, makes it an `Object[]` (the integer/double distinction
  is why a mix is not packed). The representation is invisible to the commands.
* **Member names are shared** between documents through a small fixed table (`KeyPool`): a million records repeat the same
  ten names.
* Depth is limited to 128, like serde_json, so a hostile `[[[[...` cannot overflow the stack.

### Atomic by construction: validate, then apply
The server logs and replicates only commands that succeeded, so a command that fails *after* changing something would
make the primary differ from its log and its replicas. Every operation therefore checks everything it can fail on
(index ranges, nesting depth, results that are not finite numbers, string length) before it changes anything. A random
test runs more than 10,000 operations on random documents and checks, after every one that failed, that the document is
unchanged.

A path with several matches is changed **deepest first, and inside one array from the highest index down**. A match inside
another is then changed before the one around it (nothing is changed inside something already gone, and sizes are not
counted twice), and removing an element does not shift one that is still to be removed. The list of matches cannot simply be
walked backwards: a union such as `$.a[3,1]` or a negative step selects in neither order, and the first version of this
code removed the wrong elements for exactly that reason. Each location is changed once even if a union selects it twice,
and each place gets its own copy of a value that is set into several.

### Memory accounting
The store counts `maxmemory` from an estimate per entry and must not walk a large value on every write. A document keeps
its estimate **incrementally**: every mutator of `JsonObject` / `JsonArray` returns the change in bytes it caused and
`JsonDocument` adds it up, so the cost is proportional to what the command touched. A test recounts the document from
scratch after every one of thousands of random operations and requires the two to be equal to the byte. The estimate
leans to the safe side: 115 % of what the heap holds for the benchmark record below.

### Concurrency and the write lock
* Reads and changes run inside the store's per-key section, like hashes and lists. A document has no lock of its own: its
  operations are microseconds, and the key section is already there.
* **Parsing happens before the write lock.** Decoding a record is the expensive part of storing it, so
  `JSON.SET`, `JSON.MERGE`, `JSON.ARRAPPEND`, `JSON.ARRINSERT` and `JSON.STRAPPEND` parse their JSON arguments in
  `RedCakeCommand.prepare` (the hook introduced for `VADD`, ADR 0006) on the client's own thread, so connections parse in
  parallel and the lock covers only the change. A prepared tree is handed out once, so two equal arguments never share one
  tree. Commands that skip the step (a replica applying the stream, `EXEC`, AOF replay) parse under the lock; the result is
  the same.

### Persistence and replication
* Commands are logged and replicated as received: they contain no relative times and nothing non-deterministic, and the
  same document and the same command give the same result everywhere.
* A **snapshot** (AOF rewrite, full sync, promotion) describes a document as commands. A document whose text fits 256 KB -
  nearly all - is one `JSON.SET key $ <text>`. A larger one is built piece by piece so that no command exceeds what the
  protocol parser accepts (1 MiB per argument, 1,024 words per command): an object becomes `JSON.SET path {}` plus one
  command per member (recursively), an array `JSON.SET path []` plus `JSON.ARRAPPEND` batches of at most 1,000 values or
  256 KB, a long string `JSON.SET path ""` plus `JSON.STRAPPEND` pieces cut at character boundaries. Only public commands
  are used, so the log stays readable and replayable by any node. This matters because a document can grow far beyond what
  one command can carry through repeated appends (a 3.6 MB document built in 270 commands is rewritten and replayed in the
  tests).
* Deleting the whole document (`JSON.DEL key $`) is reported to the store as "this value is gone", so the key and its TTL go
  atomically with the command; an *empty* object or array is a normal document and is kept (unlike an empty hash).
* Failover, `WATCH`, `RENAME`, expiry, eviction, `TYPE` and ACL roles need nothing special: the type plugs into the same
  hooks as the others (`ValueType`, `MemoryEstimator`, the snapshot copy).

### Limits
| Limit | Value |
|---|---|
| nesting of arrays and objects | 128 levels |
| JSON text per command | the parser's: 1 MiB per argument, 8 MiB per command |
| a document | no limit of its own: `maxmemory`, and 256 MiB for one string |
| path | 8,192 bytes, 32 nested filter levels, 10 million node visits |
| snapshot commands | 256 KB of text, 1,000 values |

## Measured
A laptop with 2 cores / 4 threads (i3-1115G4), JDK 22, **shared with a browser and an IDE** and short of memory, so values
wobble by 10-20 % between runs. The record is 6,725 bytes of JSON: about 2 KB of text, a 384-dimensional embedding,
nested metadata and a status. Reproduce with `scripts/json-benchmark.sh`.

In process (one thread unless stated):

| | |
|---|---|
| parse | 77,000 / s (13 us), 515 MB/s |
| write the whole record | 39,000 / s (26 us), 262 MB/s |
| select `$.status` / `$.meta.tags[1]` | 19 / 14 million / s |
| set `$.status`, `JSON.NUMINCRBY`, append a number to the embedding | 16 / 13 / 11 million / s |
| parse the path `$.meta.tags[1]` | 8 million / s (0.13 us) |
| parsing on 1 / 2 / 4 threads | 75,000 / 129,000 / 148,000 per second |
| **memory per record, measured on the heap** | **6,258 bytes** |
| estimate used for `maxmemory` | 7,211 bytes (115 %) |
| the same record as `LinkedHashMap` / `ArrayList<Double>` / boxed numbers | 12,771 bytes (2.0x) |

Through a server over TCP (`redis-benchmark`, 50 clients, 100,000 requests, median of three runs after a warm-up; the load
generator shares the laptop with the server):

| | pipeline 1 | pipeline 16 |
|---|---|---|
| `SET`, the record as one 6.9 KB string | 21,100 / s | 29,900 / s |
| `JSON.SET`, the record as a document | 17,200 / s | 21,300 / s |
| `GET`, the whole string | 53,900 / s | 181,500 / s |
| `JSON.GET`, the whole document | 26,100 / s | 42,000 / s |
| `JSON.GET $.status` | 93,600 / s | 538,900 / s |
| `JSON.GET $.meta.tags` | 97,800 / s | 514,600 / s |
| `JSON.SET $.status` (change one field) | 90,300 / s | 268,800 / s |
| `JSON.NUMINCRBY $.attempts` | 79,000 / s | 263,200 / s |

Structure has a price on whole-record traffic: storing a record as a document runs at about 80 % of storing the same bytes as
a string (it is parsed), reading it whole at about 50 % (384 numbers are written back as text). The gain is in field access:
reading or changing one field runs at the speed of the smallest commands (about 90,000 a second unpipelined, 260,000-540,000
pipelined), moves tens of bytes instead of 6.9 KB, and is atomic.

The first run of the same script showed the first path scenario at 56,000 / s instead of 94,000: the JIT had not yet compiled the path code; the script now warms up before measuring.

## Consequences
* An application can keep one key per record and change a field - a status, a counter, a tag - with one small command that
  is atomic, logged and replicated as exactly that command.
* The embedding inside a record is stored compactly but is **not indexed**: similarity search over embeddings is what
  vector sets are for (`VADD` the embedding under the same id). Nothing yet queries *across* documents.
* A document that grows without bound is a large single value: eviction removes it whole, a snapshot copies its text, and
  a path that touches every node of it is O(size) (bounded by the path budget).
* `JsonObject` and `JsonArray` are the first classes in RedCake that choose their representation by content. That is
  tested against `LinkedHashMap` / `ArrayList` models under random operations, but it is more code than the plain
  collections it replaces.

## Alternatives considered
* **A document namespace** (`DOC.*`): see above.
* **The document stored as text, parsed on demand.** Smallest in memory, but every read of a field and every change parses
  (and every change rewrites) the whole document; "change one field" would be O(size).
* **A JSON library's tree (Jackson, Gson).** A new dependency, strings decoded to UTF-16 and encoded again (so no byte-exact
  round trip), boxed numbers (twice the memory, measured above), and a path library on top.
* **`LinkedHashMap` for every object.** Simpler; costs about 340 bytes more per object, which a record with a few nested
  objects pays several times.
* **A lock per document.** The key section already serializes access, and operations take microseconds.
* **Batching a large object's members with `JSON.MERGE` in snapshots.** Fewer commands for an object with very many small
  members, but a merge treats `null` as "delete", so members that are null or contain nulls need special cases. One
  `JSON.SET` per member is simpler and obviously correct.
* **`BigInteger` for integers beyond 64 bits**, and **`float[]` for arrays whose numbers are all exactly 32-bit floats**
  (half the memory of a `double[]`; many embeddings are, but only if they were printed with 17 digits): both are new
  node types in every switch for a rare case or a 2x gain on some inputs.

## Not done
* `JSON.MSET` (atomic over several keys needs an undo log or two phases), `JSON.RESP`, `JSON.NUMPOWBY`; regular-expression
  and `in` filters, filter functions (`length()`).
* Queries across documents and secondary indexes; using JSON paths for `VSIM ... FILTER` on vector-set attributes.
* Copy-on-write snapshots (the dump copies the text of the document under its key's lock).
* Reply compatibility with Redis Stack has not been verified.
