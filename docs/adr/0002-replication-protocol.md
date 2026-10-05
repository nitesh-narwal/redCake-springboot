# ADR 0002 — Replication protocol (RedCake-native)

Status: accepted

## Wire format
Everything is RESP arrays. Replica → primary handshake:

```
AUTH <key>                          (only if an API key is configured)
REPLCONF listening-port <port>      (shown in INFO)
REPLCONF capa psync2
PSYNC <replid> <offset>             ("? -1" on the very first connection)
```

Primary → replica:

```
REPLICAHELLO FULLRESYNC <replid> <offset>   then one command set per key (SET/HSET/RPUSH/SADD/ZADD + PEXPIREAT)
REPLICAHELLO CONTINUE   <replid> <offset>   then only the bytes the replica missed (from the backlog)
REPLICAHELLO SYNCED                         end of the sync; live stream follows (queued writes first)
<write commands>, PING heartbeat every 10 s, REPLCONF GETACK * on WAIT
```

Replica → primary during streaming: `REPLCONF ACK <offset>` every second and on `GETACK`.

## Offsets and history
* `replid` identifies a replication history; a primary creates it at boot (and again on promotion), a replica
  adopts its primary's.
* `offset` counts bytes of the canonical RESP encoding of the stream. Snapshot commands do **not** advance it;
  only the live stream does, so primary and replica offsets are equal when caught up.
* A reconnecting replica sends its `replid`+`offset`. If the id matches and `offset` is inside the primary's
  ring buffer (`--repl-backlog-size`, default 1 MiB) the answer is `CONTINUE`; otherwise `FULLRESYNC`.
* A replica clears its store on `FULLRESYNC` and answers `-LOADING` until `SYNCED`.

## Why not the real Redis protocol
The real one sends an RDB file. Implementing RDB just for compatibility with stock Redis replicas was not
worth it; RedCake↔RedCake replication is the goal. The commands themselves are Redis-compatible.

## Failure behaviour
* Slow replica: its queue is capped at 64 MiB, then it is dropped (and will full-sync) instead of growing
  the primary's heap.
* Silent link: replicas time out after 60 s without bytes (primary pings every 10 s) and reconnect with
  exponential backoff.
* No automatic promotion: a replica must never promote itself without quorum and fencing. `REPLICAOF NO ONE`
  is an explicit operator action and starts a new history.
