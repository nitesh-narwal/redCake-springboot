# ADR 0001 — Threading model and write ordering

Status: accepted (revisit when write throughput becomes the bottleneck)

## Context
RedCake must (a) serve thousands of clients, (b) give replicas, the append-only file (AOF) and
`MULTI/EXEC` one deterministic order of writes, and (c) stay simple enough to be obviously correct.

## Decision
* **One virtual thread per connection** (blocking I/O). Reads run lock-free and in parallel on the
  concurrent store.
* **All writes on a primary pass through one `ReentrantLock`** (`ReplicationManager.primaryWriteLock`).
  Inside it: make room (`maxmemory`) → normalize (relative → absolute times) → execute → append to AOF →
  append to the replication backlog → queue for each replica.
* **Nothing inside the lock waits on the network.** Replica queues are in memory; a per-replica virtual
  thread writes to the socket. The AOF write goes to a buffer (`always` mode also fsyncs — that is the
  deliberate durability/latency trade-off of that mode).
* The store itself is still safe without the lock (`ConcurrentHashMap.compute`), so reads, the expiry
  worker and Pub/Sub never wait for it.

## Consequences
* Replicas, AOF and transactions see exactly the primary's order; a snapshot is point-in-time because it is
  taken under the same lock.
* Write throughput is bounded by one core of "execute + encode" work (measured: well above 100k writes/s
  pipelined on a laptop, see `docs/benchmarks.md`).
* Long write-lock sections (`KEYS` is a read and exempt; `BGREWRITEAOF` and full-sync snapshot copies are
  not) pause writers; both are O(keys) and documented.

## Alternatives considered
* **Single command-executor thread (Redis model).** Simplest reasoning, no lock, but reads would queue behind
  writes and we would lose parallel reads. Candidate if the lock ever shows up in profiles.
* **Sharded store, one executor per shard.** Scales writes, but multi-key commands and a global replication
  order need cross-shard coordination. Not justified yet.
* **Fine-grained locking only.** Cannot give replicas a total order without a sequencer — which is the lock.
