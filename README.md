# RedCake

RedCake is a Redis-compatible in-memory key-value server built with Java and
Spring Boot. It implements a RESP/TCP server, a primary/replica replication
model, command execution, key expiration, and a deliberately conservative
configuration and failure-handling design.

RedCake is currently an experimental in-memory database. It is suitable for
learning, local development, protocol experiments, and performance testing.
It is **not yet a replacement for production Redis** because it does not
provide durable storage, TLS, or coordinator-backed automatic failover.

## Current capabilities

- RESP client protocol over TCP.
- Multiple concurrent clients connected to one node.
- Primary writes and replica reads.
- Java virtual-thread client handling.
- Pipelined commands on a single TCP connection.
- Commands:
  - `PING`
  - `ECHO`
  - `SET`
  - `GET`
  - `DEL`
  - `EXISTS`
  - `EXPIRE`
  - `TTL`
  - `PTTL`
  - `INCR`
  - `INCRBY`
  - `DECR`
  - `DECRBY`
- In-memory expiration with lazy checks and a background expiration worker.
- Live primary-to-replica command replication.
- Initial snapshot synchronization when a replica connects.
- Streaming snapshot transfer without holding the primary write lock during
  network I/O.
- Bounded per-replica replication backlog.
- Optional shared API-key authentication using Redis-compatible `AUTH`.
- Secure loopback binding by default.

## System design

```text
                         write clients
                              |
                              v
                    +---------------------+
                    |       PRIMARY       |
                    |  TCP/RESP listener  |
                    |  command execution  |
                    |  in-memory store    |
                    +----------+----------+
                               |
                 ordered write command stream
                               |
              +----------------+----------------+
              |                                 |
              v                                 v
      +---------------+                  +---------------+
      |   REPLICA 1   |                  |   REPLICA 2   |
      | TCP/RESP read |                  | TCP/RESP read |
      | local store   |                  | local store   |
      +---------------+                  +---------------+
              ^                                 ^
              |                                 |
                         read clients
```

Each RedCake process has one local TCP listener. A primary accepts normal
clients and replica connections. A replica connects to the configured primary,
receives an initial snapshot, and then applies subsequent write commands.

The current implementation uses the same TCP listener for clients and replica
handshakes. A replica identifies itself with the internal `REPLICAHELLO`
command. The primary then changes that connection into a replication output
connection.

## Startup configuration

The command-line arguments are the authoritative configuration source.
Configuration is parsed before the application-owned configuration beans are
created. The old duplicate Spring `@ConfigurationProperties` path was removed
so that the server and replication components use one consistent configuration
instance.

### Primary

```bash
java -jar target/redCake-0.0.1-SNAPSHOT.jar \
  --port 6379
```

The default bind address is `127.0.0.1`. This prevents accidental exposure to
the network during local development.

### Replica

```bash
java -jar target/redCake-0.0.1-SNAPSHOT.jar \
  --port 6381 \
  --replicaof 127.0.0.1 6379
```

The local replica port and primary port must be different. `--port` selects
the local listener; `--replicaof` selects the upstream primary.

### Explicit network binding

```bash
java -jar target/redCake-0.0.1-SNAPSHOT.jar \
  --bind 0.0.0.0 \
  --port 6379
```

Use a non-loopback bind address only behind a firewall or trusted network.
When exposing a listener beyond loopback, configure an API key and place the
server behind TLS or a trusted private network. RedCake does not terminate TLS.

### API-key authentication

Authentication is disabled when no API key is configured, preserving local
development behavior. Configure the same key on every client-facing primary
and replica process. Prefer the environment variable because command-line
arguments can be visible through process inspection:

```bash
export REDCAKE_API_KEY='change-this-secret'
java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6379
```

`--api-key <value>` is also supported for compatibility. Authenticated
clients use the standard Redis form:

```bash
redis-cli -p 6379 -a 'change-this-secret' PING
```

Until authentication succeeds, every command other than `AUTH <api-key>`
returns `NOAUTH authentication required`. Authentication state is isolated per
TCP connection. Replica synchronization authenticates before sending the
internal `REPLICAHELLO` handshake; a mismatched or missing replica key causes
the connection to fail closed and retry.

### CLI validation

Startup rejects:

- Unknown options.
- Missing option values.
- Invalid ports outside `1..65535`.
- Duplicate `--port`.
- Duplicate `--replicaof`.
- Empty primary hosts.

## Request processing

1. `RedCakeServer` binds the configured address and port.
2. The accept loop accepts client sockets.
3. Each connection is assigned to a virtual thread.
4. `RespParser` reads complete RESP arrays from the TCP byte stream.
5. `CommandHandler` normalizes the command name and dispatches it.
6. The command operates on `InMemoryKeyValueStore`.
7. `RespWriter` writes the response and the connection remains available for
   additional pipelined commands.

TCP does not preserve message boundaries, so the parser continuously reads
frames instead of assuming that one socket read contains one command.

## Performance design

### Virtual threads

Client handlers use Java virtual threads. Blocking socket reads do not require
one permanently occupied platform thread per client, allowing many concurrent
connections while retaining straightforward blocking I/O code.

### Primary write ordering

Mutating commands are serialized through the primary replication write lock.
This gives all replicas one deterministic write order even when multiple
clients write concurrently.

Read commands do not use that lock and can execute concurrently.

### Per-key increment locking

Increment operations use per-key locks rather than synchronizing the entire
store. Concurrent increments of the same key remain atomic, while increments
on unrelated keys do not block each other unnecessarily.

### Connection admission

The server maintains a concurrent set of client sockets and limits accepted
connections to a configured maximum of 10,000. New sockets are closed when
the limit is reached instead of creating unbounded virtual-thread and memory
pressure.

### Replication output

Each replica has a serialized output path. Multiple primary writers cannot
interleave bytes on one replica socket. Failed replica writes remove that
replica from the primary's active replica set.

## Replication design

### Initial snapshot

When a replica connects:

1. The primary registers it in `snapshotting` state.
2. The primary captures the current store view while briefly holding the write
   ordering lock.
3. The lock is released before network transfer.
4. Snapshot entries are streamed one command at a time.
5. Snapshot data is sent using `SET`, including remaining TTL as `PX`.
6. Writes received during snapshot transfer are queued for that replica.
7. After the snapshot completes, queued writes are flushed in order.
8. The replica becomes a normal live replication target.

The primary does not build the complete dataset or complete encoded snapshot in
memory. Snapshot entries are iterated incrementally and sent through a
buffered output stream.

### Live replication

Successful mutating commands are encoded as RESP commands and broadcast to
active replicas. Replica-side synchronization applies those commands to the
local command handler.

Replica client writes are rejected with a read-only error. Replicas serve
reads from their local in-memory store.

### Backlog protection

Writes generated while a replica is receiving its snapshot are queued per
replica. The queued command bytes are capped at 64 MiB. A replica that cannot
keep up is disconnected instead of allowing unlimited memory growth.

This is a deliberate fail-safe: losing a slow replica is preferable to
exhausting primary memory and destabilizing every client.

## Error and failure handling

### Port binding failures

The server configures `ServerSocket` before binding and binds synchronously
during startup. If the address is already occupied, startup fails with a clear
error instead of Spring reporting a healthy application while a background
thread has already failed.

`SO_REUSEADDR` is configured before binding, but it is not treated as a way to
share an active port. Two processes still cannot listen on the same address and
port.

Check listeners with:

```bash
ss -ltnp | grep -E ':6379|:6381'
```

### Client disconnects

Client sockets are closed with try-with-resources. The server removes closed
clients from its tracking set. Normal socket disconnects are not logged as
application failures.

### Malformed RESP

Malformed protocol frames cause the connection handler to close the affected
connection. The parser enforces:

- At least one command element.
- At most 128 command elements.
- Bulk strings no larger than 1 MiB.
- RESP integer lines no longer than 64 bytes.
- Strict CRLF framing.

These limits prevent uncontrolled allocation from malformed or hostile input.

### Output and protocol errors

I/O failures are propagated consistently through `RespWriter`. Error messages
replace CR/LF characters before being written, preventing a command error from
injecting additional RESP lines.

### Replica failures

If a replica socket is closed or a replication write fails:

1. The failed replica is removed from the active replica map.
2. Its socket is closed.
3. Other replicas continue receiving replication.

Replica connections use connect timeouts, TCP keepalive, and `TCP_NODELAY`.
The replica synchronization loop reconnects after connection failure with a
bounded delay.

### Shutdown

Shutdown:

1. Stops accepting new clients.
2. Closes the listening socket.
3. Closes tracked client sockets.
4. Stops the virtual-thread executor.
5. Stops replication connections.
6. Stops the expiration worker.

The server lifecycle is designed to be idempotent and to avoid leaving
acceptor or client resources running after application shutdown.

### Expiration safety

Expiration entries include a version. A stale expiration event cannot delete a
newer value for the same key. TTL arithmetic uses checked arithmetic so
overflow is returned as an invalid expiration error instead of silently
creating an incorrect timestamp.

### Numeric safety

Increment and decrement operations check `long` overflow before updating the
store. Invalid numeric values and overflow return command errors rather than
corrupting stored data.

## Failover status

RedCake does **not** currently perform automatic primary promotion.

A replica must not independently promote itself after a timeout. Without
quorum, a lease, and a fencing token, the old primary may still be reachable
from some clients. Both nodes could then accept writes and create split-brain
divergence.

Production-grade automatic failover requires additional infrastructure:

- A coordinator quorum, such as Redis Sentinel, Redis Cluster, etcd, Consul,
  or another consensus system.
- Authenticated node identity.
- Monotonic terms/epochs and fencing tokens.
- Replication offsets and acknowledgements.
- A durable write-ahead log if accepted writes must survive process loss.
- Fail-closed write admission after lease loss.
- Explicit separation and ownership of replication sockets.

Until those components exist, promotion must be a deliberate operational
procedure that first fences and stops the old primary.

## Durability limitations

The store is memory-only. Data is lost when the process exits. Replication is
also memory-to-memory; it is not a substitute for a durable log or backup.

The current snapshot preserves remaining TTL values, but expiration behavior
still depends on the receiving node's wall clock. Large deployments should
eventually use a monotonic replication position and a durable log.

## Security posture

Current protections:

- Loopback-only binding by default.
- Explicit opt-in for non-loopback binding.
- Bounded RESP command and bulk-string sizes.
- Bounded client admission.
- Bounded per-replica backlog.
- Strict CLI validation.
- No sensitive command payload logging.
- Error-line sanitization.

Not yet implemented:

- Fine-grained authentication or authorization (only one shared API key is
  currently supported).
- TLS.
- ACLs.
- Encrypted replication.
- Network-level rate limiting.
- Durable audit logging.

Do not expose RedCake directly to an untrusted network.

## Build and test

Build and run the complete test suite:

```bash
./mvnw clean test package
```

Run snapshot barrier tests:

```bash
./mvnw -Dtest=ReplicaConnectionTest,ReplicationManagerSnapshotTest test
```

Run protocol and CLI tests:

```bash
./mvnw -Dtest=RespParserTest,CommandLineConfigTest test
```

Validate changed-file whitespace:

```bash
git diff --check
```

## Manual smoke test

Start a primary:

```bash
java -jar target/redCake-0.0.1-SNAPSHOT.jar --port 6379
```

Start a replica:

```bash
java -jar target/redCake-0.0.1-SNAPSHOT.jar \
  --port 6381 \
  --replicaof 127.0.0.1 6379
```

Write to the primary:

```bash
redis-cli -p 6379 SET user:1 Nitesh
redis-cli -p 6379 SET counter 10
```

Read from the replica:

```bash
redis-cli -p 6381 GET user:1
redis-cli -p 6381 GET counter
```

Verify replica writes are rejected:

```bash
redis-cli -p 6381 SET invalid value
```

Verify initial snapshot behavior by writing data before starting a new replica,
then starting that replica on another port and reading the existing keys from
it.

## Project structure

```text
src/main/java/me/niteshh/redcake/
├── cli/             Command-line parsing and validation
├── command/         Command dispatch and command implementations
├── config/          Application-owned runtime configuration
├── replication/     Primary/replica protocol and lifecycle
├── resp/            RESP parsing and response writing
├── server/          TCP listener and client handling
└── store/           In-memory values, expiration, and snapshots
```

## Development direction

The next production-oriented milestones are:

1. Add a durable write-ahead log.
2. Add replication offsets, terms, checksums, and acknowledgements.
3. Separate replication traffic from client traffic.
4. Add authentication and TLS.
5. Integrate a coordinator-backed quorum and fencing-based failover.
6. Add load, fault-injection, and network-partition testing.
