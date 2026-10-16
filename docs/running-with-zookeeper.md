# Running RedCake with ZooKeeper - step by step

This guide shows how to start a RedCake cluster that **fails over by itself**: several RedCake nodes plus ZooKeeper.
When the primary dies, one of the replicas becomes the new primary and takes over the writes - nobody has to run
`REPLICAOF NO ONE`, re-point the other replicas, or make sure the old primary stays down.

It is a how-to. The design and the safety argument are in [`adr/0005-zookeeper-failover.md`](adr/0005-zookeeper-failover.md);
the short option summary is in the [README](../README.md#high-availability-automatic-failover).

Failover is **opt-in**. A RedCake that is started without `--zk-connect` behaves exactly as it did before.

**Contents**

1. [How it fits together](#1-how-it-fits-together)
2. [What you need](#2-what-you-need)
3. [Quick start on one machine](#3-quick-start-on-one-machine) - ZooKeeper and three nodes in about 15 minutes, then break them on purpose
4. [Production-style setup](#4-production-style-setup) - three machines, a three-member ZooKeeper ensemble, security, operations
5. [Docker Compose](#5-docker-compose)
6. [Reference](#6-reference) - options, `INFO cluster`, replies, endpoints, znodes, timing
7. [Troubleshooting](#7-troubleshooting)
8. [FAQ](#8-faq)
9. [How this guide was verified](#9-how-this-guide-was-verified)

---

## 1. How it fits together

```
               ZooKeeper   (1 server to try it out, 3 or 5 in production)
        /redcake/<cluster>/{ leader, epoch, nodes/<id>, failover }
             ^                   ^                   ^
             | session           | session           | session
      +------+-------+    +------+-------+    +------+-------+
      |    node1     |    |    node2     |    |    node3     |
      |   PRIMARY    |===>|   REPLICA    |    |   REPLICA    |
      +------+-------+ \  +--------------+    +--------------+
             ^          \==============================^
             |           replication stream: node to node, NOT through ZooKeeper
   clients: writes go here, reads can go anywhere
```

* **ZooKeeper is the referee.** It stores a handful of tiny znodes (who the primary is, a counter, one entry per live node).
  It never sees your data. If ZooKeeper has no majority, there is no failover (see the [FAQ](#8-faq)).
* **RedCake nodes hold the data.** Each node keeps a ZooKeeper session, takes part in the election and replicates directly
  from the primary.
* **Clients** are ordinary Redis clients. Writes must reach the primary; see [Clients and load balancer](#47-clients-and-load-balancer).

**What happens when the primary dies**

1. It stops sending heartbeats. ZooKeeper expires its session (10 s by default, plus up to one ZooKeeper tick) and deletes the
   ephemeral `/leader` znode by itself.
2. The replicas notice that there is no leader, wait one short settle period (1 s, so that every node has published its final
   replication offset) and rank themselves: priority first, then the newest data lineage, then the **highest replication offset**
   (the least data lost). The best node claims the role with **one atomic ZooKeeper transaction** (bump the epoch + create `/leader`).
   Of any number of simultaneous claimants exactly one wins.
3. The winner promotes itself, starts a new replication history and begins writing its append-only file.
4. The other replicas follow the winner and **resume with a partial resync** - no dataset copy.
5. When the old primary comes back it finds that it is no longer the leader and rejoins as a replica; writes that no replica had
   received are dropped.

**Why two nodes cannot accept writes at the same time.** A primary writes only while it is connected to ZooKeeper **and** holds a
fresh *lease* (two thirds of the session timeout, renewed continuously). Cut off from ZooKeeper, or frozen by a long pause, it
stops by itself *before* ZooKeeper can hand the role to somebody else, and answers `-CLUSTERDOWN`.

**One honest caveat.** Replication is asynchronous. If a primary dies right after acknowledging a write that no replica had
received yet, that write is lost. Planned hand-overs (`FAILOVER`, graceful shutdown) lose nothing. To bound the loss of an
unplanned failover use `WAIT` and `--min-replicas-to-write`.

---

## 2. What you need

| Item | Details |
|---|---|
| Java | 21 or newer for RedCake (the build targets 21). The ZooKeeper tarball needs a Java runtime too; the Docker image brings its own |
| RedCake jar | `./mvnw -DskipTests package` produces `target/redCake-0.0.1-SNAPSHOT.jar` |
| ZooKeeper | a 3.9.x server. RedCake bundles the 3.9.6 client (Curator 5.9.0), so it needs nothing else. 1 server to try it, 3 or 5 for production |
| `redis-cli` | any Redis client works; the examples use `redis-cli` |
| `curl`, `nc` | optional: HTTP health checks and the ZooKeeper `srvr` check |

The commands assume a bash or zsh shell on Linux or macOS.

**How many servers**

| | Minimum | Recommended | Why |
|---|---|---|---|
| RedCake nodes | 2 (one node works but cannot fail over) | 3 | after one failure you still have a primary *and* a replica |
| ZooKeeper servers | 1 (for trying it out) | 3 or 5, always an odd number | 3 tolerate 1 failure, 5 tolerate 2 |

**Ports**

| Port | Who talks | Used for |
|---|---|---|
| `--port` (6379) | clients and the other RedCake nodes | commands **and** replication (same port) |
| `--metrics-port` (9100, off by default) | monitoring, load balancers | HTTP: `/metrics`, `/health`, `/primary`, `/replica` - unauthenticated, keep it private |
| 2181 | RedCake nodes to ZooKeeper | ZooKeeper client port |
| 2888, 3888 | ZooKeeper servers among themselves | follower-to-leader traffic and leader election (ensembles only) |
| 8080 | - | ZooKeeper's admin web server; the examples switch it off with `admin.enableServer=false` |

---

## 3. Quick start on one machine

You will start one ZooKeeper and three RedCake nodes on your own machine, look at the cluster, and then kill things.

The demo uses ports **7001-7003** (RedCake) and **9101-9103** (metrics), so a RedCake or Redis that already runs on the default
port 6379 is left alone.

### Step 1: Start ZooKeeper

Pick one option.

**Option A - Docker**

```bash
docker run -d --name zookeeper -p 2181:2181 zookeeper:3.9
```

**Option B - the Apache tarball**

Download `apache-zookeeper-3.9.x-bin.tar.gz` (the file with `-bin` in its name) from
<https://zookeeper.apache.org/releases.html>, then:

```bash
tar xzf apache-zookeeper-3.9.*-bin.tar.gz
cd apache-zookeeper-3.9.*-bin
mkdir -p "$HOME/zookeeper-data"
cat > conf/zoo.cfg <<EOF
tickTime=2000
dataDir=$HOME/zookeeper-data
clientPort=2181
admin.enableServer=false
EOF
bin/zkServer.sh start
bin/zkServer.sh status        # Mode: standalone
```

What the four lines mean:

| Line | Meaning |
|---|---|
| `tickTime=2000` | ZooKeeper's base time unit in ms. Its minimum session timeout is 2 ticks, and it expires sessions on tick boundaries, so a failover can be up to one tick later than the session timeout |
| `dataDir=...` | where ZooKeeper keeps its snapshots. Do not leave it under `/tmp` (the sample config does) |
| `clientPort=2181` | the port RedCake connects to |
| `admin.enableServer=false` | stops ZooKeeper from also grabbing port 8080 |

**Check that ZooKeeper is serving**

```bash
echo srvr | nc -w 2 127.0.0.1 2181
```

Expected (abridged):

```
Zookeeper version: 3.9.6-a355171b081b5b60749db8f19cca1528b0df936f, built on 2026-09-03 19:29 UTC
...
Mode: standalone
Node count: 5
```

If it answers `This ZooKeeper instance is not currently serving requests`, it was asked a moment too early. Wait a few seconds
and ask again.

### Step 2: Build RedCake

```bash
cd /path/to/redCake
./mvnw -DskipTests package
mkdir -p ~/redcake-demo/node1 ~/redcake-demo/node2 ~/redcake-demo/node3
cp target/redCake-0.0.1-SNAPSHOT.jar ~/redcake-demo/redcake.jar
```

The nodes run from a **copy** of the jar on purpose: rebuilding replaces `target/...jar`, and replacing a jar underneath a
running JVM can crash it.

### Step 3: Start three RedCake nodes

Open three terminals. The nodes differ only in port, data directory and id.

**Terminal 1**

```bash
java -jar ~/redcake-demo/redcake.jar \
  --port 7001 --metrics-port 9101 \
  --aof-file ~/redcake-demo/node1/redcake.aof \
  --zk-connect 127.0.0.1:2181 --cluster-name demo \
  --node-id node1 --advertise-host 127.0.0.1
```

**Terminal 2**

```bash
java -jar ~/redcake-demo/redcake.jar \
  --port 7002 --metrics-port 9102 \
  --aof-file ~/redcake-demo/node2/redcake.aof \
  --zk-connect 127.0.0.1:2181 --cluster-name demo \
  --node-id node2 --advertise-host 127.0.0.1
```

**Terminal 3**

```bash
java -jar ~/redcake-demo/redcake.jar \
  --port 7003 --metrics-port 9103 \
  --aof-file ~/redcake-demo/node3/redcake.aof \
  --zk-connect 127.0.0.1:2181 --cluster-name demo \
  --node-id node3 --advertise-host 127.0.0.1
```

| Option | Why it is there |
|---|---|
| `--port` | client port, and also the port the replicas connect to |
| `--metrics-port` | turns on the HTTP endpoints (`/primary`, `/replica`, `/health`, `/metrics`) |
| `--aof-file` | persistence. **Every node needs its own file.** Only the current primary writes its file |
| `--zk-connect` | **turns cluster mode on.** One `host:port`, or a comma-separated list of every ZooKeeper server |
| `--cluster-name` | nodes with the same name on the same ZooKeeper form one cluster. Several clusters can share one ZooKeeper |
| `--node-id` | unique name of this node (default: `advertise-host:advertise-port`) |
| `--advertise-host` | the address the *other nodes and clients* use to reach this node. Here everything is on one machine |

All options, with defaults, are in [section 6.1](#61-command-line-options).

**What you should see.** Within a second or two one node wins the election and the other two follow it. With identical (empty)
data the node with the smallest id wins, so it is normally `node1`. Abridged logs, timestamps removed:

```
# node1 - the winner
Cluster mode: node node1 (127.0.0.1:7001), cluster 'demo', ZooKeeper 127.0.0.1:2181, priority 100, session timeout 10000 ms, durable data epoch -1
WARN  No ZooKeeper credentials (REDCAKE_ZK_AUTH / --zk-auth-file): anyone who can reach ZooKeeper can read the election state ...
ZooKeeper connection: CONNECTED
Election: candidates=node1(offset 0),node2(offset 0),node3(offset 0)
Won the election: epoch 1, node1 (rank 0, offset 0)
Promoted to primary: epoch 1, replication id fc510df0e1f42eb83bfd215622ad5504a37c57f4, offset 0

# node2 and node3
Replica without a primary: waiting for the cluster coordinator to assign one
Election: candidates=node1(offset 0),node2(offset 0),node3(offset 0)
Following node1 at 127.0.0.1:7001 (epoch 1)
Full resync with primary 127.0.0.1:7001 (offset 0)
```

The `No ZooKeeper credentials` warning is expected in a demo. [Section 4.6](#46-secure-it) shows how to remove it.

### Step 4: Check the cluster

```bash
redis-cli -p 7001 INFO cluster
```

```
# Cluster
cluster_enabled:1
cluster_name:demo
cluster_node_id:node1
cluster_state:leading
cluster_zk_state:connected
cluster_fenced:0
cluster_epoch:1
cluster_leader_id:node1
cluster_leader:127.0.0.1:7001
cluster_data_epoch:1
cluster_data_trusted:1
cluster_members:3
cluster_lease_remaining_ms:6595
cluster_promotions:1
cluster_demotions:0
cluster_election:candidates=node1(offset 0),node2(offset 0),node3(offset 0)
```

On a replica (`-p 7002`) the interesting lines are `cluster_state:following`, the same `cluster_epoch:1` and
`cluster_leader:127.0.0.1:7001`. Every field is explained in [section 6.2](#62-info-cluster-fields).

A handy helper for the rest of this guide - one line per node:

```bash
cluster_status() {
  for p in 7001 7002 7003; do
    line=$(redis-cli -p "$p" INFO cluster 2>/dev/null | tr -d '\r' | grep -E '^cluster_(state|epoch|leader):' | tr '\n' ' ')
    echo "$p  ${line:-(down)}"
  done
}
cluster_status
```

```
7001  cluster_state:leading cluster_epoch:1 cluster_leader:127.0.0.1:7001
7002  cluster_state:following cluster_epoch:1 cluster_leader:127.0.0.1:7001
7003  cluster_state:following cluster_epoch:1 cluster_leader:127.0.0.1:7001
```

### Step 5: Use the cluster

```bash
redis-cli -p 7001 SET greeting hello       # OK                     writes go to the primary
redis-cli -p 7001 WAIT 2 3000              # 2                      both replicas have it
redis-cli -p 7002 GET greeting             # hello                  replicas serve reads
redis-cli -p 7002 SET greeting x           # READONLY replica does not accept writes (the primary is 127.0.0.1:7001)
redis-cli -p 7001 ROLE                     # "master" + the list of replicas (a replica answers "slave")
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9101/primary    # 200 - only the primary says 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9102/primary    # 503
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9102/replica    # 200 - replicas that hold a trusted dataset
```

Note that the `READONLY` reply tells the client where the primary is.

### Step 6: Break it on purpose

Keep `cluster_status` handy. The timings below were measured with the default settings (10 s session timeout, ZooKeeper
`tickTime` 2000). Use the node that `cluster_status` shows as `leading` wherever the text says "the primary" - below it is `node1`.

#### Experiment 1: Crash the primary (`kill -9`)

```bash
kill -9 "$(pgrep -f 'redcake[.]jar.*--port 7001')"
```

(`pgrep -f` finds the process by its command line; the `[.]` instead of `.` only keeps the pattern from also matching the shell
that runs it, which matters if you paste the command into a `bash -c '...'` or a script. `ps aux | grep redcake.jar` shows the
same thing.)

Run `cluster_status` every few seconds. For about ten seconds the survivors still believe in the old primary, because
ZooKeeper is waiting for the dead session to expire:

```
7001  (down)
7002  cluster_state:following cluster_epoch:1 cluster_leader:127.0.0.1:7001
7003  cluster_state:following cluster_epoch:1 cluster_leader:127.0.0.1:7001
```

Then a replica takes over:

```
7001  (down)
7002  cluster_state:leading cluster_epoch:2 cluster_leader:127.0.0.1:7002
7003  cluster_state:following cluster_epoch:2 cluster_leader:127.0.0.1:7002
```

Measured: **12.8 s** from `kill -9` to the first accepted write. The new primary has all acknowledged data:

```bash
redis-cli -p 7002 SET after-crash 2        # OK
redis-cli -p 7002 GET greeting             # hello
```

Where the time goes: the 10 s session timeout, plus ZooKeeper's rounding up to its next tick (0-2 s), plus the 1 s settle delay,
plus a fraction of a second for the election and the promotion. The logs show it:

```
# node3
Replica connection lost: Failed to connect to primary at 127.0.0.1:7001       (repeats while nobody is primary)
No primary in the cluster: election starts (cluster epoch so far: 1)
Election: candidates=node2(offset 179),node3(offset 179)
Following node2 at 127.0.0.1:7002 (epoch 2)
Partial resync with primary 127.0.0.1:7002 (offset 179)                         <- no dataset copy

# node2
Won the election: epoch 2, node2 (rank 0, offset 179)
Promoting to primary
Promoted to primary: epoch 2, replication id 33f315ff2b7293707cbd81239e95be635659df13, offset 179
```

To fail over faster lower `--zk-session-timeout-ms` - but read the warning in [section 6.7](#67-timing-cheat-sheet) first.

#### Experiment 2: Bring the crashed node back

Start node1 again with exactly the same command as in step 3. It comes back as a **replica** of node2:

```
AOF replayed: 0 commands                  <- only what had reached the disk; irrelevant, see below
AOF: logging starts when this node is elected primary
Following node2 at 127.0.0.1:7002 (epoch 2)
Full resync with primary 127.0.0.1:7002 (offset 245)
```

```bash
cluster_status
redis-cli -p 7001 GET after-crash          # 2 - it received everything the new primary has
```

Things worth knowing:

* A **restarted node always does a full resync** (its replication offset starts again at 0); the replica that stayed up used a
  partial one.
* For a moment after start `cluster_data_trusted` is `0` and clients get `-LOADING ...` from that node; it becomes `1` when the
  copy is complete.
* Its append-only file may be missing the last second (`--aof-fsync everysec`). That does not matter: the node copies everything
  from the new primary. In a cluster the replicas, not the file, are what protect a crashed primary's data.
* **Only the primary writes an append-only file.** A replica's file stays as it was when the node last led (the `<file>.epoch`
  file next to it records which epoch that was). A node whose file is older than the cluster's epoch can never become primary.

#### Experiment 3: Planned hand-over with `FAILOVER`

Send it to the current primary (node2 now):

```bash
redis-cli -p 7002 FAILOVER TO 127.0.0.1 7001      # OK
cluster_status
```

```
7001  cluster_state:leading cluster_epoch:3 cluster_leader:127.0.0.1:7001
7002  cluster_state:following cluster_epoch:3 cluster_leader:127.0.0.1:7001
7003  cluster_state:following cluster_epoch:3 cluster_leader:127.0.0.1:7001
```

Measured: **0.25 s and 0.43 s** in two runs, and no write is lost: the primary pauses writes, waits until the target has everything, releases `/leader`,
and becomes a replica of the target. The old primary now answers `READONLY ... (the primary is 127.0.0.1:7001)`.

* `FAILOVER` without `TO` hands the role to the best replica.
* `FAILOVER TO host port TIMEOUT ms` limits how long it waits for the replicas to catch up. If they do not, nothing changes and
  you get `FAILOVER refused: replicas did not catch up within ...`.
* The `host port` must be the target's **advertised** address.

#### Experiment 4: Stop the primary gracefully

In the primary's terminal press `Ctrl+C`, or from another one:

```bash
kill -TERM "$(pgrep -f 'redcake[.]jar.*--port 7001')"     # or: redis-cli -p 7001 SHUTDOWN
```

A node that is the primary hands the role over *before* it shuts down. Measured over four runs (`SIGTERM` and `SHUTDOWN`): the new
primary was leading between **0.1 s and 1.7 s** later. The log says
`Giving up the primary role: handed over`. Nothing waits for a session timeout, and nothing is lost. `systemctl stop` and
`docker stop` send `SIGTERM`, so they hand over as well.

#### Experiment 5: Lose ZooKeeper

```bash
docker stop zookeeper          # or: bin/zkServer.sh stop
```

What the primary does, measured:

| Time | What you see |
|---|---|
| +0.5 s | writes are refused: `CLUSTERDOWN this primary lost its ZooKeeper connection and is fenced; writes are paused`. `INFO cluster` shows `cluster_state:leading cluster_zk_state:suspended cluster_fenced:1`. **Reads keep working** |
| after about 10 s (the session timeout) | the primary gives up the role: `cluster_state:electing cluster_zk_state:lost`. Writes now say `CLUSTERDOWN no primary is elected; writes are paused until a new primary is promoted` |

Start ZooKeeper again (`docker start zookeeper` or `bin/zkServer.sh start`). Measured: a writable primary again **2.3 s** after
ZooKeeper was back, with a higher epoch, all data intact, and the replicas resuming with a partial resync.

This is the price of consistency: **without a ZooKeeper majority RedCake stops accepting writes rather than risk two primaries.**
A ZooKeeper outage shorter than the session timeout only fences the primary for a moment; it proves itself again and carries on
without any election (covered by an automated test).

#### Experiment 6: Freeze the primary (optional)

A process that is frozen - a very long GC pause, a VM that was suspended - cannot tell that the world moved on.

**While the node is frozen, do not query it** (and do not run `cluster_status`): a frozen process never answers and `redis-cli`
then waits for ever. Ask the other two nodes. On Linux `timeout 3 redis-cli ...` puts a limit on any query.

```bash
kill -STOP "$(pgrep -f 'redcake[.]jar.*--port 7001')"      # freeze the primary
sleep 17                                                    # the others notice and elect a new primary
redis-cli -p 7002 INFO cluster | grep -E '^cluster_(state|epoch|leader):'
redis-cli -p 7003 INFO cluster | grep -E '^cluster_(state|epoch|leader):'
kill -CONT "$(pgrep -f 'redcake[.]jar.*--port 7001')"      # wake it up
redis-cli -p 7001 SET zombie 1
```

The woken-up node refuses the write: `CLUSTERDOWN this primary could not renew its leadership lease and is fenced; writes are paused`
(or, a moment later, `READONLY ...`), and within seconds it follows the new primary. `zombie` exists nowhere.

#### The same, automated

[`scripts/failover-test.sh`](../scripts/failover-test.sh) runs `kill -9`, a restart, `FAILOVER`, `SIGSTOP`/`SIGCONT` and `SIGTERM`
against three real processes and checks 22 things (no acknowledged write lost, partial resync, zombie write refused, ...). It
needs the built jar, a ZooKeeper, `redis-cli`, `curl` and `bc`, and takes about two minutes:

```bash
ZK_CONNECT=127.0.0.1:2181 scripts/failover-test.sh                 # ZooKeeper with the default tickTime of 2000
ZK_CONNECT=127.0.0.1:2181 ZK_TICK_MS=1000 scripts/failover-test.sh  # if yours is configured differently
```

It uses its own ports (7101-7103, 9201-9203) and a temporary directory.

### Step 7: Clean up

`Ctrl+C` in each RedCake terminal (they hand over and exit), then stop ZooKeeper **and throw away its state** together with the
nodes' data:

```bash
docker rm -f zookeeper                                   # Option A: the container and its state are gone
bin/zkServer.sh stop && rm -rf "$HOME/zookeeper-data"    # Option B
rm -rf ~/redcake-demo
```

Delete both sides, not just one. ZooKeeper remembers the cluster's epoch; nodes that start with empty data against a ZooKeeper
that remembers an epoch are refused as "older than the last leader" and no primary is ever elected (see
[Troubleshooting](#7-troubleshooting)). If you only want to start the demo over, `deleteall /redcake/demo` in `zkCli.sh` also
resets ZooKeeper's side.

---

## 4. Production-style setup

Three RedCake machines, three ZooKeeper servers. The steps are the same as the quick start; what changes is that nothing is on
`127.0.0.1` any more, there are secrets, and you need to operate it.

### 4.1 Plan

| Decision | Advice |
|---|---|
| Layout | ZooKeeper on its own small machines is best. Sharing the RedCake machines works for small clusters, but a long GC pause or memory pressure on a shared machine delays ZooKeeper's heartbeats and can cause an election |
| `--cluster-name` | one name per logical cluster, e.g. `production`. Letters, digits, `.`, `_`, `-` |
| `--node-id` | stable and **unique**: `node1`, `node2`, `node3`. Two live nodes with the same id fight over one znode |
| `--advertise-host` | a DNS name or IP that **every other node and every client** can reach. Not `127.0.0.1`, not `0.0.0.0`. With `--tls-replication` it must also match the certificate |
| Secrets | one RedCake API key and one ZooKeeper credential, the same on all nodes, kept in a file with mode `600` |
| Who may never be primary | give that node `--replica-priority 0` (a reporting or disaster-recovery replica). Lower numbers are promoted first; the default is 100 |

### 4.2 The ZooKeeper ensemble

On each of `zk1`, `zk2`, `zk3` install the tarball as in [step 1](#step-1-start-zookeeper). Use **the same** `conf/zoo.cfg` on all
three:

```properties
tickTime=2000
initLimit=10
syncLimit=5
dataDir=/var/lib/zookeeper
clientPort=2181
admin.enableServer=false
# keep the data directory from growing for ever
autopurge.snapRetainCount=3
autopurge.purgeInterval=1

server.1=zk1.example.com:2888:3888
server.2=zk2.example.com:2888:3888
server.3=zk3.example.com:2888:3888
```

and give every server its identity - the number must match its `server.N` line:

```bash
mkdir -p /var/lib/zookeeper
echo 1 > /var/lib/zookeeper/myid        # on zk1;  echo 2 on zk2;  echo 3 on zk3
bin/zkServer.sh start
```

Check (from any machine):

```bash
for h in zk1.example.com zk2.example.com zk3.example.com; do
  printf '%s  ' "$h"; echo srvr | nc -w 2 "$h" 2181 | grep '^Mode'
done
# one "Mode: leader" and two "Mode: follower"
```

* Open 2181 to the RedCake nodes, and 2888 and 3888 between the ZooKeeper servers only.
* The ensemble needs a **majority** (2 of 3, 3 of 5). Losing one member is harmless: it was tested by stopping the ensemble's
  leader while a RedCake cluster ran - ZooKeeper elected a new leader, RedCake did not notice.
* Restart or patch ZooKeeper **one member at a time**, and wait for it to rejoin before the next one.

### 4.3 RedCake configuration

Everything can be a command-line option, or a line in a config file (the same name without the `--`; command-line options win).
`/etc/redcake/redcake.conf` on node1 - on node2 and node3 only `node-id` and `advertise-host` change:

```properties
port 6379
bind 0.0.0.0
metrics-port 9100
aof-file /var/lib/redcake/redcake.aof
aof-fsync everysec

zk-connect zk1.example.com:2181,zk2.example.com:2181,zk3.example.com:2181
cluster-name production
node-id node1
advertise-host node1.example.com

# replica-priority 100
# min-replicas-to-write 1
```

**List every ZooKeeper server in `zk-connect`.** The ZooKeeper client waits before each reconnect (measured after a dropped
connection: 0.2-1.1 s with several servers, 1.2-1.7 s with one). With a single address a network blip is far more likely to
outlast a short session and cost an election.

Secrets go in the environment, never in the config file or on the command line (where `ps` shows them). With systemd,
`/etc/redcake/env`, mode `600`:

```
REDCAKE_API_KEY=<a long random secret, the same on every node>
REDCAKE_ZK_AUTH=redcake:<another long random secret, the same on every node>
```

The repository ships a systemd unit, [`deploy/redcake.service`](../deploy/redcake.service); its header explains the install. It runs
`java -jar /opt/redcake/redcake.jar --config /etc/redcake/redcake.conf` and reads `/etc/redcake/env`. `TimeoutStopSec=30` leaves
plenty of time for the hand-over on `systemctl stop`. Without systemd:

```bash
REDCAKE_API_KEY=... REDCAKE_ZK_AUTH=redcake:... java -jar redcake.jar --config /etc/redcake/redcake.conf
```

Because the nodes listen on `0.0.0.0`, RedCake insists on credentials. Forget the key and it refuses to start:

```
Refusing to listen on 0.0.0.0 without authentication. Set REDCAKE_API_KEY (or --api-key-file), bind to 127.0.0.1, or pass --allow-insecure if the network is trusted.
```

### 4.4 First start - and moving existing data in

Start ZooKeeper first, then the RedCake nodes in any order. Each node starts read-only and answers `-LOADING` until it leads or
has synced; within about a second the best node wins.

> **If one of the nodes already holds data** - for example a standalone RedCake you are converting - **start that node first,
> alone, and wait until it reports `cluster_state:leading`.** Start the others afterwards.
>
> A node that joins an established cluster becomes a replica and **its dataset is replaced by the primary's**, exactly as with any
> Redis replica. Tested with a 5-key append-only file:
>
> | Start order | Result |
> |---|---|
> | empty node first, node with data second | the empty node led; the node with the data followed it and ended with **0 keys** |
> | node with data first, empty node second | the node with the data led with 5 keys; the other node received all 5 |
>
> (If you start all nodes at the same moment the node with the most keys wins the first election, but do not rely on timing.)
>
> If it happens anyway: the node's append-only file is **not touched while it is a replica** (it was byte-identical afterwards).
> Stop that node at once and move its file aside, before it can be promoted - a promotion rewrites the file from memory.

Migration, step by step:

1. Stop the standalone RedCake gracefully (`SIGTERM` or `SHUTDOWN`) so its append-only file is complete.
2. Put that file where node1's `aof-file` points. A standalone RedCake's file works as it is.
3. Start **only node1** with the cluster options. Wait for `redis-cli -h node1 INFO cluster` to say `cluster_state:leading` and
   check `DBSIZE`.
4. Start node2 and node3. They copy the data (`Full resync with primary ...`).

### 4.5 Verify

From any machine:

```bash
export REDISCLI_AUTH='<the API key>'          # redis-cli sends it as AUTH
for h in node1 node2 node3; do
  echo "== $h"
  redis-cli -h $h.example.com INFO cluster | grep -E '^cluster_(state|epoch|leader|zk_state):'
done
```

You want exactly one `leading` and two `following`, the same `cluster_epoch` and `cluster_leader` everywhere, and
`cluster_zk_state:connected`. Then run the experiments of [step 6](#step-6-break-it-on-purpose) once, on the real machines, before
you trust it.

### 4.6 Secure it

Anyone who can write the cluster's znodes can choose the primary - and a replica sends the API key (`AUTH`) to whoever the
znodes say is primary. So:

1. **API key** - `REDCAKE_API_KEY`, identical on all nodes. Clients and replicas must authenticate; without it:
   `NOAUTH authentication required`.
2. **ZooKeeper credentials** - `REDCAKE_ZK_AUTH=user:password` (or `--zk-auth-file path`), identical on all nodes. The cluster's
   znodes are then created readable and writable only by that identity. Tested:

   ```
   --- without credentials
   ls /redcake                          -> [demo, secure-demo]            (the shared root is open on purpose, so clusters can coexist)
   get /redcake/secure-demo/epoch       -> KeeperErrorCode = NoAuth for /redcake/secure-demo/epoch
   --- with credentials
   get /redcake/secure-demo/epoch       -> 1
   ACL of /redcake/secure-demo/epoch    -> [digest:redcake:<hash> perms=31]
   ```

   Nodes warn at startup when no credentials are set, and the warning disappears once they are. Decide on this **before the first
   start**. To add credentials later, the safe route is to stop all RedCake nodes, delete the cluster's znodes (`deleteall /redcake/<cluster>`
   in `zkCli`), and start them again with the credentials. That exact sequence was not tested; it is the situation of a ZooKeeper
   that lost its data, which was (see [4.9](#49-day-2-operations)): the epoch counter restarts, the data stays.
3. **TLS between the nodes** - `--tls-keystore ... --tls-truststore ... --tls-replication` makes replicas verify the primary's
   certificate, so a tampered znode alone cannot capture the API key. The certificate must cover each node's `advertise-host`.
   See the README's TLS options. (Not exercised in this guide.)
4. **Network** - ZooKeeper's ports only for the RedCake nodes and the ZooKeeper peers; the RedCake port for clients and nodes;
   the metrics port (unauthenticated) only for your monitoring and load balancer. RedCake does not encrypt its ZooKeeper
   connection, so keep ZooKeeper on a private network.

### 4.7 Clients and load balancer

RedCake speaks the plain Redis protocol. It does **not** implement Sentinel or Redis Cluster discovery, so a stock client will
not find a new primary on its own. You have three options.

**A. A load balancer in front (best - clients need no change).** Run the health checks against the metrics port:

* `GET /primary` is `200` only on the node that may accept writes right now (leader with a fresh lease), `503` elsewhere.
* `GET /replica` is `200` on replicas that hold a dataset they can vouch for.

[`deploy/haproxy.cfg.example`](../deploy/haproxy.cfg.example) is a ready template: port 6400 always goes to the primary (writes),
port 6401 is spread over the replicas (reads that may be slightly stale). It checks every second and drops connections to a node
that stopped being the primary, so clients reconnect instead of sitting on a half-dead connection. (The two endpoints were tested;
the HAProxy file itself is a template that was not run.)

**B. Follow the `-READONLY` hint.** Writing to a replica returns `READONLY replica does not accept writes (the primary is host:port)`.
A client that reconnects to that address on `-READONLY` or `-CLUSTERDOWN` (with a short back-off) rides through failovers.

**C. Ask the nodes.** For scripts and small tools:

```bash
find_primary() {
  for hp in node1.example.com:6379 node2.example.com:6379 node3.example.com:6379; do
    if redis-cli -h "${hp%:*}" -p "${hp#*:}" INFO cluster 2>/dev/null | tr -d '\r' \
         | grep -q '^cluster_state:leading$'; then echo "$hp"; return 0; fi
  done
  return 1
}
```

Whatever you choose, expect `-CLUSTERDOWN` for the length of a failover (about 12-14 s after a crash with the defaults) and
retry. For writes that must not be lost use `WAIT <replicas> <ms>` after the write, or run the primary with
`--min-replicas-to-write 1` (it then answers `-NOREPLICAS Not enough good replicas to write.` when no replica is keeping up).

### 4.8 Monitoring and alerts

Each node exposes `http://node:9100/metrics` (Prometheus format). Cluster metrics:

```
redcake_cluster_enabled 1
redcake_cluster_is_primary 1
redcake_cluster_fenced 0
redcake_cluster_epoch 1
redcake_cluster_zk_connected 1
redcake_cluster_members 3
redcake_cluster_lease_remaining_milliseconds 6579
redcake_cluster_promotions_total 1
redcake_cluster_demotions_total 0
```

Suggested alerts:

| Alert | Expression | Meaning |
|---|---|---|
| no primary | `sum(redcake_cluster_is_primary) == 0` for 30 s | writes are failing |
| two primaries | `sum(redcake_cluster_is_primary) > 1` for 30 s | should never last; investigate at once |
| a primary that cannot write | `redcake_cluster_fenced == 1` for 15 s | it lost ZooKeeper or its lease |
| ZooKeeper unreachable | `redcake_cluster_zk_connected == 0` for 15 s | per node |
| failovers happening | `increase(redcake_cluster_promotions_total[1h]) > 0` | a failover took place - find out why |

Also watch ZooKeeper itself (`echo mntr | nc zk1 2181` needs `4lw.commands.whitelist=mntr` in `zoo.cfg`), and the disk of the
primary's append-only file (a failing disk makes writes answer `-MISCONF` and `/health` return 503).

### 4.9 Day-2 operations

All of these are built from steps tested in [section 3](#3-quick-start-on-one-machine).

| Task | How |
|---|---|
| Restart or patch a **replica** | stop it gracefully, do the work, start it. It does a full resync |
| Maintain the **primary** | `redis-cli -h <primary> FAILOVER` first (or stop it gracefully - same effect), wait until `INFO cluster` on another node says `leading`, then work on the old one |
| Rolling upgrade of RedCake | replicas one at a time, wait until each is `following` with `cluster_data_trusted:1`; then `FAILOVER`; then the old primary |
| Replace a dead machine | start RedCake on the new machine with an empty data directory. It joins as a replica and copies everything. Reusing the dead node's `node-id` is fine as long as the old process is really gone |
| Remove a node for good | stop it. Its registration disappears with its session |
| ZooKeeper maintenance | one member at a time, waiting for it to rejoin. RedCake keeps running. Never take down a majority |
| Backup | back up the **primary's** append-only file. `BGREWRITEAOF` compacts it first, but it runs under the write lock, so do it off-peak. A replica's file may be old by design |
| Everything down at once | start ZooKeeper, then the RedCake nodes. The node that was primary last has the newest data epoch and wins; a node whose file is older than the cluster's epoch can never lead |
| Election is stuck | `cluster_election` in `INFO cluster` says why, e.g. `candidates=none; excluded: node2 (its data (epoch -1) is older than the last leader's epoch 1)`. Bring back the node with the newest data. If it is gone for good - or you deliberately deleted the nodes' data - set the znode with `zkCli`: `set /redcake/<cluster>/epoch 0`. Tested: a leader appeared 0.6 s later and the epoch restarted at 1. It is a deliberate, manual act: from then on any data that is present is accepted |
| ZooKeeper lost its data | tested: wiped ZooKeeper's data directory under a running cluster and started it again; the nodes re-registered, re-elected within 6 s and **kept all their data**. The epoch counter restarts from 0 and with it the guard against stale files, so if you have to do this, make sure the node with the newest data is running |

---

## 5. Docker Compose

[`docker-compose.cluster.yml`](../docker-compose.cluster.yml) starts one ZooKeeper and three RedCake nodes that elect their own primary
(and, with a profile, an HAProxy in front of them):

```bash
REDCAKE_API_KEY=change-me docker compose -f docker-compose.cluster.yml up --build

redis-cli -p 6379 -a change-me INFO cluster                       # any node answers; who leads?
docker compose -f docker-compose.cluster.yml kill node1           # kill the primary...
redis-cli -p 6380 -a change-me INFO cluster                       # ...and a replica takes over in about 12-14 s
redis-cli -p 6380 -a change-me FAILOVER                           # planned hand-over, no write lost

docker compose -f docker-compose.cluster.yml --profile lb up      # HAProxy: 6400 = writes, 6401 = reads
```

* The nodes are published on host ports 6379, 6380 and 6381. If a RedCake or Redis already listens on 6379 on your machine, stop
  it or change the left-hand side of the `ports:` entry of `node1`.
* `--advertise-host` is the compose service name (`node1`, `node2`, `node3`) - how the nodes reach each other. A `-READONLY`
  hint therefore names `node2:6379`, which only exists inside the compose network; from the host use `INFO cluster` and the
  published ports.
* One ZooKeeper is enough to try this out; a real deployment runs a 3- or 5-member ensemble.
* Each node has its own volume for its append-only file. `docker stop` sends `SIGTERM`, so a stopped primary hands over.

*This compose file and the HAProxy profile were not run while this guide was written* - Docker was installed but the images were
not available. Treat them as a template and report anything that does not start.

---

## 6. Reference

### 6.1 Command-line options

Every option can also be a line in the config file (`--config PATH`), without the leading `--`.

| Option | Default | Meaning |
|---|---|---|
| `--zk-connect HOSTS` | off | **Turns failover on.** `host:port[,host:port...]`. Cannot be combined with `--replicaof` |
| `--cluster-name NAME` | `redcake` | znode namespace; nodes with the same name form one cluster. 1-100 characters of `A-Z a-z 0-9 . _ -` |
| `--node-id ID` | `advertise-host:advertise-port` | unique id of this node |
| `--advertise-host HOST` | the bind address (the machine's address for `0.0.0.0`) | how others reach this node. Always set it explicitly |
| `--advertise-port PORT` | `--port` | port to advertise (when it differs from the listening port, e.g. behind NAT) |
| `--replica-priority N` | `100` | lower is promoted first; `0` = never promote |
| `--zk-session-timeout-ms N` | `10000` | failure-detection time; also bounds a cut-off primary. Keep at 5000 or more |
| `--zk-connection-timeout-ms N` | `5000` | how long a connection attempt may take. Keep it at or below the session timeout |
| `--cluster-election-delay-ms N` | `1000` | quiet time before an election, and spacing between candidates |
| `--zk-auth-file PATH` | none | `user:password` for ZooKeeper digest auth; or the env var `REDCAKE_ZK_AUTH` |
| `--min-replicas-to-write N` | `0` (off) | refuse writes with fewer than N good replicas |
| `--min-replicas-max-lag SECONDS` | `10` | a replica is "good" if it acknowledged this recently |

Related, not specific to the cluster: `--port`, `--bind`, `--metrics-port`, `--aof-file`, `--aof-fsync`, `--api-key-file` / env
`REDCAKE_API_KEY`, `--tls-*`, `--maxmemory`, `--config`. `java -jar redcake.jar --help` lists everything (it prints the options
and then a Spring startup stack trace - that is only how the program exits; ignore it).

With `--zk-connect`, `REPLICAOF` is refused (`REPLICAOF is disabled: ZooKeeper decides the roles in cluster mode ...`): ZooKeeper
decides who the primary is.

### 6.2 `INFO cluster` fields

| Field | Meaning |
|---|---|
| `cluster_enabled` | `1` when started with `--zk-connect` |
| `cluster_name`, `cluster_node_id` | `--cluster-name`, `--node-id` |
| `cluster_state` | `joining` (has not reached ZooKeeper yet), `electing` (no primary), `following`, `leading` |
| `cluster_zk_state` | `connected`; `suspended` (connection lost, session may still be alive); `lost` (session expired); `disconnected` (not connected yet) |
| `cluster_fenced` | `1`: this node holds the leader znode but may not write right now (lost connection, or lease not renewed) |
| `cluster_epoch` | epoch of the current primary; every election raises it |
| `cluster_leader_id`, `cluster_leader` | id and `host:port` of the primary (empty when there is none) |
| `cluster_data_epoch` | the epoch this node's data belongs to |
| `cluster_data_trusted` | `1`: the data is current enough to serve (the node led, or finished a sync). `0`: still joining or syncing, clients get `-LOADING` |
| `cluster_members` | how many nodes were registered when *this node* last ran an election. It is `0` on a node that joined an already running cluster and has not seen an election; do not use it as a live node count |
| `cluster_lease_remaining_ms` | primary only: time left on the write lease. Renewed continuously, so on a healthy primary it hovers just below two thirds of the session timeout (about 6.6 s with the defaults); `0` on replicas |
| `cluster_promotions`, `cluster_demotions` | how often this node became / stopped being primary since it started |
| `cluster_election` | the last election's candidates - or, when an election cannot finish, the reason |

### 6.3 Commands

| Command | Where | Effect |
|---|---|---|
| `INFO cluster` | any node | the table above |
| `ROLE` | any node | `master` + replica list, or `slave` + primary |
| `FAILOVER [TO host port] [TIMEOUT ms]` | the primary | planned hand-over, nothing lost |
| `WAIT n ms` | the primary | blocks until `n` replicas acknowledged the preceding writes |
| `SHUTDOWN` / `SIGTERM` / `Ctrl+C` | any node | graceful stop; a primary hands over first |
| `REPLICAOF` | - | refused in cluster mode |

### 6.4 Replies and what to do about them

| Reply | Meaning | What to do |
|---|---|---|
| `READONLY replica does not accept writes (the primary is host:port)` | you wrote to a replica | send writes to the address shown |
| `CLUSTERDOWN this primary lost its ZooKeeper connection and is fenced; writes are paused` | the primary cannot reach ZooKeeper | wait; fix ZooKeeper or the network. It recovers by itself |
| `CLUSTERDOWN this primary could not renew its leadership lease and is fenced; writes are paused` | slow or frozen primary, or a silent partition | wait; look for long pauses |
| `CLUSTERDOWN no primary is elected; writes are paused until a new primary is promoted` | an election is running or cannot finish | retry; if it persists read `cluster_election` |
| `NOREPLICAS Not enough good replicas to write.` | `--min-replicas-to-write` is not satisfied | bring replicas back |
| `LOADING this node is joining the cluster and has no trusted dataset yet` | the node is starting or syncing | retry in a moment |
| `NOTPRIMARY this node is not the cluster primary right now; ...` | a replica asked a non-primary for a sync | nothing - nodes retry within a fraction of a second |
| `NOAUTH authentication required` | API key missing | `redis-cli -a ...` or `REDISCLI_AUTH` |
| `FAILOVER needs cluster mode (--zk-connect)` | `FAILOVER` on a node without cluster mode | - |
| `FAILOVER refused: this node is not the primary...; send FAILOVER there` | wrong node | use the primary |
| `FAILOVER refused: host:port is not a replica of this primary` | wrong address | use the target's advertised `host port` |
| `FAILOVER refused: replicas did not catch up within N ms` | a replica is lagging or down | check it, or raise `TIMEOUT` |
| `FAILOVER refused: no eligible replica to take over` | none qualifies (`priority 0`, still syncing, ...) | check `INFO cluster` on the replicas |

### 6.5 HTTP endpoints (on `--metrics-port`)

| Endpoint | Answer |
|---|---|
| `/primary` | `200` on the node that may accept writes right now, `503` otherwise |
| `/replica` | `200` on a replica that holds a trusted dataset, `503` otherwise |
| `/health` | `200` if the node is healthy (`503` when the append-only file cannot be written) |
| `/metrics` | Prometheus metrics, including `redcake_cluster_*` ([section 4.8](#48-monitoring-and-alerts)) |

### 6.6 What is stored in ZooKeeper

Under `/redcake/<cluster-name>/`:

| znode | Type | Content |
|---|---|---|
| `epoch` | persistent | the highest epoch ever granted; changed only by compare-and-set |
| `leader` | **ephemeral** | the primary: node, host, port, epoch. Exists exactly as long as its owner's session |
| `nodes/<id>` | ephemeral | one record per live node: role, replication id and offset, data epoch, priority, key count |
| `failover` | ephemeral | a hint during a planned hand-over |

Real content from the demo cluster (`zkCli.sh -server ... get ...`):

```
get /redcake/demo/epoch                 1
get /redcake/demo/leader
    node=node1  host=127.0.0.1  port=7001  epoch=1  incarnation=6cb52292b287  since=1792150540934
get /redcake/demo/nodes/node2
    node=node2  host=127.0.0.1  port=7002  priority=100  role=follower  replid=74a9f219...  offset=431
    data-epoch=1  durable=1  restored=0  loading=0  keys=2  epoch=1  incarnation=55798b9af839  updated=1792150768838
```

(One `key=value` per line in the real znodes.) The znodes hold only election metadata - never your data - so ZooKeeper's load from
RedCake is a few small writes per second at most.

### 6.7 Timing cheat sheet

`T` = `--zk-session-timeout-ms` (10 s). Measured values are from this guide's runs.

| Event | Time |
|---|---|
| Primary killed or machine lost, until a new primary accepts writes | between `T` and `T` + one ZooKeeper tick (expiry is on tick boundaries) + about 1.5 s. Defaults: **12-14 s**; measured 12.8 s |
| Planned `FAILOVER` | measured 0.25-0.45 s |
| Graceful stop of the primary (`SIGTERM`, `SHUTDOWN`) | measured 0.1-1.7 s |
| Primary loses ZooKeeper: writes refused | within about 0.5 s; at the latest after the lease (two thirds of `T`, 6.7 s) when the network fails silently |
| Primary gives up the role after losing ZooKeeper | after `T` |
| ZooKeeper comes back after a long outage, until a writable primary | measured 2.3 s |

**Do not make `T` very small.** After a dropped connection the ZooKeeper client alone needs up to about 2 s to reconnect. In a
test with a 2 s session and a one-address connect string, 4 of 10 one-second network blips lost the session (and so caused an
election); with two or more addresses none did. Name every ZooKeeper server in `--zk-connect` and keep `T` at 5 s or more unless you
have measured your own network. RedCake logs a warning at startup when the value is below 5000.

---

## 7. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| `echo srvr \| nc ...` says `This ZooKeeper instance is not currently serving requests` | ZooKeeper was asked a moment after it started | wait a few seconds, ask again |
| ZooKeeper does not start, port 8080 in use | its admin web server | add `admin.enableServer=false` to `zoo.cfg` |
| `Refusing to listen on 0.0.0.0 without authentication` | non-loopback bind without an API key | set `REDCAKE_API_KEY` (or `--api-key-file`); `--allow-insecure` only on a trusted network |
| `Unable to bind RedCake to 127.0.0.1:7002. The port may already be in use.` (the process exits with code 2) | something already listens on that port (a standalone RedCake or Redis on 6379?) | use other ports, as the quick start does |
| Node stays `cluster_state:joining`; clients get `-LOADING this node is joining ...` | it cannot reach ZooKeeper | check `--zk-connect`, DNS, firewall; `echo srvr \| nc host 2181`; `--zk-connection-timeout-ms` |
| All nodes `electing`; writes get `-LOADING ...` (nodes without trusted data) or `-CLUSTERDOWN no primary is elected` | no eligible candidate, or ZooKeeper has no majority | read `cluster_election`. Typical: `candidates=none; excluded: s1 (its data (epoch -1) is older than the last leader's epoch 1)` - the nodes' data was deleted but ZooKeeper still remembers the cluster (re-running the demo without cleaning up ZooKeeper, or a restored empty disk). Fix: bring back the data, or `set /redcake/<cluster>/epoch 0` ([4.9](#49-day-2-operations)). Other reasons: `replica-priority is 0`; or ZooKeeper has no majority |
| `-CLUSTERDOWN ... fenced` on the primary | lost ZooKeeper, or its lease ran out | `INFO cluster`: `cluster_zk_state`, `cluster_lease_remaining_ms`. Look for GC pauses, a busy machine, network loss to ZooKeeper |
| Failovers happen although nothing crashed | the session is too short for your network or pauses; or a single address in `--zk-connect` | raise `--zk-session-timeout-ms` to 5000+; list all ZooKeeper servers; look at GC logs |
| Warning `session timeout [3000] is less than connection timeout [5000]` | `--zk-connection-timeout-ms` is above the session timeout | lower `--zk-connection-timeout-ms` |
| Warning `No ZooKeeper credentials ...` | `REDCAKE_ZK_AUTH` / `--zk-auth-file` not set | set them, identical on all nodes |
| Warning `ZooKeeper session timeout of N ms is short ...` | below 5000 ms | see [6.7](#67-timing-cheat-sheet) |
| Replica log `Replica connection lost: Failed to connect to primary at ...` | the primary just died; nobody is primary yet | expected for the length of the failover |
| Replica log `Primary rejected PSYNC: -NOTPRIMARY` | it asked a node that is not (yet) the elected primary | transient; retried after a fraction of a second |
| Data from a node vanished after it joined | it became a replica of a primary with different data | [section 4.4](#44-first-start---and-moving-existing-data-in); its append-only file is still on disk until the node is promoted |
| Clients keep getting `-READONLY` after a failover | they are still connected to the old primary, now a replica | reconnect to the address in the message, or use a load balancer |
| `NOAUTH authentication required` | API key missing in the client | `-a` / `REDISCLI_AUTH` |
| `cluster_members:0` on a healthy node | it joined a running cluster and has not run an election | harmless; see [6.2](#62-info-cluster-fields) |
| Two nodes keep taking each other's place | the same `--node-id` on two live nodes | give every node a unique id |
| `FAILOVER refused: ...` | see [6.4](#64-replies-and-what-to-do-about-them) | |

Where to look: each node logs every election and role change (`Election:`, `Won the election`, `Promoted to primary`,
`Following ... (epoch N)`, `Giving up the primary role: ...`, `Partial resync` / `Full resync`). `INFO cluster` is the quickest
status; `INFO replication` shows each replica's offset and lag.

---

## 8. FAQ

**Do I need ZooKeeper to run RedCake?** No. Without `--zk-connect` nothing changes; you can still use `--replicaof` by hand.
The two cannot be mixed in one node: `--replicaof` together with `--zk-connect` is rejected.

**What if ZooKeeper dies?** If it loses its majority, RedCake nodes cannot elect or confirm a primary: the primary refuses writes
after its lease (reads continue), and no failover happens until ZooKeeper is back. That is a deliberate choice - it prefers
refusing writes to risking two primaries. With a single ZooKeeper server, its failure stops failover and - once the lease runs out - writes (reads keep
working), so run three.

**Can I lose data on a failover?** After an **unplanned** failover, writes that the old primary had acknowledged but no replica had
received are lost, because replication is asynchronous. `WAIT n ms` after a write and `--min-replicas-to-write` bound this. Planned
hand-overs (`FAILOVER`, graceful stop) lose nothing.

**Can there be two primaries?** Not for writes. A primary stops writing before ZooKeeper can hand the role to anybody else, and
checks that on every write. One narrow window remains: a process frozen *between* its check and sending the reply may still
acknowledge one write after it wakes up; that write is lost, it does not corrupt the new primary. See the ADR for the argument.

**Do the clocks have to be synchronised?** The failover logic uses monotonic timers, so no. Clocks must tick at roughly the same
rate, which they do.

**Which replica becomes primary?** The best eligible one: lowest `--replica-priority` number first, then newest data lineage, then
highest replication offset, then most keys, then node id. Nodes with priority `0`, nodes that are still copying a dataset and nodes
whose data is older than the cluster's epoch are never chosen.

**Can several clusters share one ZooKeeper?** Yes, with different `--cluster-name`s. They live under `/redcake/<name>`.

**Can I use replicas for reads?** Yes, with the usual caveat that replication is asynchronous, so a replica can be slightly behind.
`GET /replica` on the metrics port identifies replicas that hold a trusted dataset.

**Is there a sharded mode?** No. This is one primary with replicas. Hash-slot clustering is not implemented.

---

## 9. How this guide was verified

Everything in sections 3, 4 and 6 that is shown with output was run, on Linux with a JDK 22 runtime, against the RedCake build in
this repository (`0.0.1-SNAPSHOT`) and **ZooKeeper 3.9.6**. ZooKeeper was started from a `zoo.cfg` through
`org.apache.zookeeper.server.quorum.QuorumPeerMain` - the class `zkServer.sh` launches - as a single server and as a three-member
ensemble on localhost. The log lines, `INFO cluster` output, znode contents, error texts and timings above are copied from those runs.

Run: three nodes electing a primary; writes, `WAIT`, replica reads and the `READONLY` hint; `kill -9` of the primary; restart of the
crashed node; `FAILOVER TO`; `SIGTERM`, `SIGINT` (Ctrl+C) and `SHUTDOWN` of the primary; freezing the primary with `SIGSTOP`;
stopping and restarting ZooKeeper (and wiping its data); losing one member of a three-member ensemble; a cluster started from a
config file with `REDCAKE_ZK_AUTH`; a secured cluster (`REDCAKE_API_KEY` on `0.0.0.0` plus ZooKeeper digest credentials); both
start orders for moving existing data in; the stuck election after deleting only the nodes' data, and the `epoch 0` fix; a taken
port; the HTTP endpoints and the `redcake_cluster_*` metrics.

The quick start (steps 1-5 and experiments 1, 2, 3, 4 and 6, and `find_primary` from 4.7) was then **replayed with the commands
copied from this file**, with `HOME` pointing at a scratch directory so that `~/redcake-demo` resolved there. That replay is how the
frozen-node warning in experiment 6 and the `[.]` in the `pgrep` pattern came about. The whole sequence is also automated in
`scripts/failover-test.sh`, which passed all 22 checks against both a `tickTime` of 1000 and of 2000. The repository's test suite
(252 tests, including 21 end-to-end failover tests against an embedded ZooKeeper) passes.

**Not run:** the Docker commands and `docker-compose.cluster.yml`; `zkServer.sh` from the tarball (the same server class was
started directly); the HAProxy template; the systemd unit; machines with separate hosts and real network failures (the tests cut a
TCP proxy and freeze a process instead); TLS between cluster nodes.
