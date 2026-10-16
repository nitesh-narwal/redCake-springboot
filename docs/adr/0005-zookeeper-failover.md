# ADR 0005 — Automatic failover with ZooKeeper

Status: accepted (opt-in: `--zk-connect`; without it RedCake behaves exactly as before)

## Context
Until now a dead primary meant manual work: pick a replica, run `REPLICAOF NO ONE`, re-point the others, and make sure
the old primary does not come back and accept writes. ADR 0002 forbids the shortcut ("a replica must never promote
itself without quorum and fencing"). What is missing is a *source of truth* that several nodes can agree on, and a
rule that makes the old primary stop on its own.

## Decision
**ZooKeeper is the single source of truth for who the primary is.** Every RedCake node is a ZooKeeper client (Apache
Curator). The roles are *derived* from ZooKeeper state by one loop (`FailoverCoordinator.reconcile`) that re-reads the
state every tick and on every watch event, so lost or reordered notifications cannot matter.

### ZooKeeper layout (`/redcake/<cluster-name>/...`)
| znode | type | content |
|---|---|---|
| `/epoch` | persistent | highest leader epoch ever granted; changed only by compare-and-set |
| `/leader` | **ephemeral** | the primary: node id, address, epoch. Exists exactly as long as its owner's session |
| `/nodes/<id>` | ephemeral | one record per live node: role, replication id, offset, data epoch, priority, key count, "loading" |
| `/failover` | ephemeral | optional hint of a planned hand-over: "give it to X" |

### Election
When `/leader` is absent, every node waits a **settle period** (`--cluster-election-delay-ms`, so that all nodes publish
their final offset), then ranks the registered nodes with `ElectionPolicy` and the node of rank *r* tries to claim
leadership *r* x (delay) later. The claim is **one ZooKeeper multi-transaction**: "bump `/epoch` from the version I read
*and* create `/leader`". ZooKeeper applies it atomically in one global order, so of any number of simultaneous claimants
exactly one wins and every epoch number is issued once. The ranking only decides who is *tried first*; correctness never
depends on it.

Eligible: not the failed leader, not in the middle of a full resync (`loading`), not `--replica-priority 0`, and - if its
data was merely *restored from disk* (it has neither led nor synced since it started) - not older than the last leader's
epoch (see "Cold start"). A running replica is never judged by that rule: its data tracks the stream and its lag is what the
offset ranking is for; without this distinction a primary that dies moments after its election, before its replicas finished
syncing with it, would leave nobody eligible.
Order: operator hand-over target, lowest priority number (Redis `replica-priority` semantics), newest data lineage,
**highest replication offset** (least data lost), most keys, node id.

### Fencing: never two writers
A node accepts a write only if `ClusterState.mayWrite()` holds, checked **inside the write lock for every write**
(and before an `EXEC` that contains writes):

1. it is the leader, and its ZooKeeper connection is `CONNECTED` (a `SUSPENDED`/`LOST` connection fences *immediately*), and
2. its **lease** is unexpired. The lease is renewed by a successful read of its own `/leader` znode (owner = my session,
   epoch = mine). It lasts **2/3 of the session timeout**, measured from when the read *started*.

Why this is safe: when the read succeeded, ZooKeeper had just refreshed the session, so the session cannot expire before
`start + T`. The primary stops at `start + 2T/3`, i.e. at least `T/3` *before* ZooKeeper can delete `/leader` and anybody else
can be elected. A primary that is cut off, or frozen by a long GC/VM pause, therefore stops by itself; on waking up its first
write sees an expired lease (`nanoTime` is monotonic). The same primary also refuses `PSYNC`, so no replica can attach to a
fenced node. Refused writes get `-CLUSTERDOWN`.

When the connection stays suspended for a whole session timeout the primary **demotes itself** (becomes a read-only node without
a primary), and when it finds `/leader` missing or owned by somebody else it does the same. It then follows the new leader.

### Becoming a primary / following
* `promote` (winner): stop the replica sync, start a **new replication history** but keep the old id valid up to the current
  offset (`replid2`, Redis "PSYNC2"), switch the AOF on from the in-memory dataset, record the epoch next to the AOF, open the fence.
* `follow` (everybody else): `ReplicationManager.followPrimary(host, port)`. A former primary keeps its history, so it asks to
  *continue* at its offset; the new primary compares replid/offset and answers `CONTINUE` if the node is a prefix of its own
  history (no copy at all) or `FULLRESYNC` if the node has writes the new primary never saw (they are discarded - that is
  the unavoidable cost of asynchronous replication).
* Every replica **mirrors the stream it applies into its own backlog**, so after a promotion the other replicas can resume from
  it instead of copying the dataset again. A primary that answers `-NOTPRIMARY` (still promoting) is retried after ~150 ms.

### Planned hand-over (no waiting for a timeout, no loss)
`FAILOVER [TO host port] [TIMEOUT ms]` on the primary and the graceful shutdown (`SIGTERM`/`SHUTDOWN`, run before the
replication layer stops) do: pause writes, wait until all replicas acknowledged everything, (for a named target: wait until its
published offset shows it has applied it), write a `/failover` hint and release `/leader`. If the replicas do not catch up in time a
named `FAILOVER` changes nothing. The old primary then follows the new one with a partial resync.

### Cold start and total outage
A node starts **undecided**: read-only, no primary, answering `-LOADING` (it may only have replayed an old AOF) until it leads or
has completed a sync. With an AOF it replays the file but does not log until it is elected.

Replicas do not write an AOF, so after a power failure of everything the files on disk have different ages. Each primary
writes `<aof>.epoch` ("this file belongs to epoch N") *before* accepting its first write. The election refuses a node that
persists data, whose data came only from its disk, and whose epoch is older than the cluster's `/epoch`: **a stale AOF can
never take over a cluster that has moved on.** A brand new node with no history is likewise ineligible for an established
cluster. Nodes without an AOF have nothing to protect and are always eligible (they rank last). In a brand new cluster the node holding the most keys wins ties, so the first start of a
node that already has data does not wipe it.

## Timing (T = `--zk-session-timeout-ms`, default 10 s)
| What | Value |
|---|---|
| Failure detected (ZooKeeper deletes `/leader`) | between T and T + `tickTime` after the last heartbeat (ZooKeeper expires sessions on tick boundaries; its default tick is 2 s) |
| Primary stops writing when cut off | at most 2T/3 after its last proof |
| Settle + staggering | `--cluster-election-delay-ms` (1 s) + 2 ticks per rank |
| Typical unplanned failover | about T + 1.5 s, plus up to one `tickTime` (measured with tick 1 s, T = 3 s: 5.1 s) |
| Planned hand-over / graceful shutdown | well under a second |

The ZooKeeper client itself waits before every reconnect attempt: a random 0-1 s, plus a fixed 1 s when its connect string
names a single server. Measured against the embedded server after a dropped connection: 0.2-1.1 s with several addresses,
1.2-1.7 s with one. A session of 2 s therefore did not survive a one-second blip with a single address (4 of 10 rounds lost it),
while it always did with two or more. The default (10 s) has ample room; a very short `T` turns network blips into elections,
so `--zk-connect` should list every ensemble member and `T` should stay at 5 s or more. The end-to-end tests that need a
session to survive a drop use the longest session the test server grants (4 s) and list the proxy twice, like an ensemble.

## What happens when ... (all covered by `ClusterFailoverTest`)
| Situation | Behaviour |
|---|---|
| Cluster starts for the first time | all nodes register; the best candidate claims epoch 1, the rest follow |
| Primary process killed / machine lost | session expires; best replica promoted; others resume with a partial resync |
| Primary cut off from ZooKeeper, still running | fences at once (`-CLUSTERDOWN`), demotes itself after T; on reconnect becomes a replica; unreplicated writes discarded |
| Silent partition / frozen process | no event arrives; the lease runs out first; same outcome |
| ZooKeeper blip shorter than the session | primary is fenced briefly, proves itself again, resumes; no election |
| ZooKeeper down longer than the session | every node loses its znodes; on return a new election; data intact, no full resyncs |
| Replica crashes and restarts | rejoins, full or partial resync |
| Several nodes want to lead | the atomic claim lets exactly one win |
| Best candidate dies right after the leader | the next rank claims after one stagger |
| Old primary restarts quickly | removes its own ghost `/leader` at once instead of making everybody wait |
| Only stale / empty / `priority 0` nodes are left | no election; writes `-CLUSTERDOWN`; reads continue; `INFO cluster` says why |
| Operator wants to move the role | `FAILOVER`; or stop the primary gracefully |
| Primary has too few replicas | `--min-replicas-to-write` refuses writes with `-NOREPLICAS` |

## Guarantees and honest limits
* **At most one node acknowledges writes at a time**, assuming ZooKeeper is correct and clocks run at roughly the same *rate*
  (they need not be synchronised). One window remains: a process frozen *between* the fence check and the reply can still
  acknowledge one write after waking up; that write is lost, it does not corrupt the new primary.
* **Replication is asynchronous**, so an unplanned failover can lose the writes the old primary acknowledged but no replica had
  received. Bound it with `WAIT` and `--min-replicas-to-write`. Planned hand-overs lose nothing.
* **ZooKeeper unavailable = no failover and no writes** on the primary after the lease (CP choice). Reads keep working.
* A restarted node's replication offset restarts at 0, so a live replica outranks a restarted ex-primary; the AOF tail that
  never reached a replica is lost.
* Node ids and advertised addresses must be unique. Two live nodes with the same id fight over one znode.
* Break-glass when only stale nodes are left and the operator accepts the loss: set the znode `/redcake/<cluster>/epoch` to `0`
  with `zkCli`. The election then accepts any persistent node (still preferring the newest data among those present) and
  continues counting from 1. This is deliberately a manual, visible act.

## Alternatives considered
* **Sentinel-style monitors.** A second kind of process to run and secure, with its own quorum protocol; ZooKeeper is already
  that protocol, battle-tested, and most deployments that want failover already run it.
* **Raft inside RedCake (e.g. Apache Ratis).** The "right" long-term answer for a database, but a large new subsystem; the roadmap
  keeps it open (`Task.md` 3.5).
* **Curator `LeaderLatch`.** Elects the node that registered first, not the one with the most data, has no epoch and no lease
  semantics; we need all three, so we use Curator for the connection and write the election on top.
* **Failure detection by the replication link.** A broken TCP connection is a hint, not a decision; ZooKeeper's session is.
