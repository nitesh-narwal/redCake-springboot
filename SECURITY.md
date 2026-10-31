# Security policy

## Reporting a vulnerability
Please do **not** open a public issue. Report privately to the maintainer (see the repository profile)
with a description, affected version and, if possible, a reproduction. You will get an answer within a
few days. There is no bug bounty.

## Threat model
RedCake is an in-memory data store meant to run **inside a trusted network**. It is hardened against
accidental exposure and untrusted *protocol input*, not against a hostile authenticated administrator.

| Threat | Mitigation |
|---|---|
| Accidentally exposing an open database | Binds to loopback by default; **protected mode** refuses a non-loopback bind unless an API key/ACL is set or `--allow-insecure` is given |
| Credential guessing | Constant-time compare on SHA-256 digests (no length leak); growing delay per failure; per-IP lockout after 10 failures for 60 s; unauthenticated connections time out after 15 s |
| Over-privileged clients | ACL users with `admin` / `readwrite` / `readonly` roles (`--acl-file`); admin-only: `FLUSHALL`, `FLUSHDB`, `CONFIG`, `SLOWLOG`, `SHUTDOWN`, `REPLICAOF`, `FAILOVER`, `PSYNC`, `REPLCONF`, `MONITOR`, `CLIENT KILL`, `ACL LIST` / `ACL USERS`, `BGREWRITEAOF`, `VRESTORE` |
| Eavesdropping / tampering | TLS for clients and replication (`--tls-keystore`), optional mutual TLS (`--tls-client-auth`), host-name verification on the replica → primary link |
| Malformed or hostile protocol input | Bounded parser (1024 args, 1 MiB per bulk string, 8 MiB per command, 64-byte header lines); protocol errors close the connection; randomised fuzz test in CI |
| Memory exhaustion | `--maxmemory` + eviction policies, `--max-clients`, bounded replica queues (64 MiB), idle timeouts |
| Resource abuse through vector commands | Every input is bounded: dimension 1-16,384, `EF` and `COUNT` at most 10,000, `M` 4-128, element name 64 KB, attribute 256 KB, `VALUES` counted against the 1,024-argument limit; a vector set's memory is counted by `--maxmemory` and freed whole on eviction (ADR 0006) |
| Crafted `VRESTORE` dump (a snapshot chunk that lies) | `VRESTORE` is admin-only (it is what replication and the AOF use); every length, link count, link target and level and the entry point are validated before the set can be searched, a failed restore deletes the key, and a test flips random bits in dumps |
| Hostile JSON text | A strict parser: nesting limited to 128 levels (no stack overflow), UTF-8 and `\u` escapes validated, numbers out of range refused, no recursion beyond the nesting; the text is bounded by the parser's 1 MiB per argument / 8 MiB per command (ADR 0007) |
| Expensive JSONPath expressions | A path is limited to 8,192 bytes and 32 nested filter levels, and one evaluation - nested filters included - may visit at most 10 million nodes; `$..*..*..*..*` and nested filters are abandoned with an error instead of holding a thread and the key's lock |
| A JSON document grown beyond what a command can carry | It grows only through commands that each fit the parser's limits; a snapshot (rewrite, full sync) rebuilds it from pieces that do too, so a large document can never make replication or the AOF unreadable. A string may not exceed 256 MiB; the total is bounded by `--maxmemory` |
| Slow-client (slowloris) | `--timeout`, 15 s authentication deadline |
| Secret leakage | API key via `REDCAKE_API_KEY` / `--api-key-file`; TLS passwords via files/env; ACL file stores only SHA-256 digests; `CONFIG GET` never returns secrets; `AUTH` is never written to the slow log or `MONITOR` |
| Log injection | Error text is stripped of CR/LF; client data is never logged |
| Two writers after a partition (split brain, cluster mode) | A primary writes only while it owns ZooKeeper's `/leader` znode **and** its lease (2/3 of the session timeout) is fresh, checked inside the write lock for every write; it stops *before* a successor can be elected, refuses `PSYNC`, then steps down (ADR 0005) |
| Stale data resurrected after a total outage (cluster mode) | `<aof>.epoch` records which leader epoch an AOF belongs to; the election refuses a persistent node whose data is older than the cluster's epoch |
| Rogue node pretending to be the primary (cluster mode) | Only a node that holds the ephemeral `/leader` znode is followed; protect the znodes with `--zk-auth-file` (digest ACLs), and replication links with `--api-key` / `--tls-replication` |

## Known gaps — read before exposing RedCake
* The ACL has three roles, not per-key or per-command rules. Passwords are single SHA-256 digests (fast to
  brute-force offline if the ACL file leaks) — use long random passwords.
* `/metrics` and `/health` (when `--metrics-port` is set) are **unauthenticated**; they expose only counters
  and sizes, never keys or values. Bind to loopback or firewall the port.
* Pub/Sub delivery runs on the publisher's thread: a subscriber that stops reading can slow publishers.
* The cost of reading a JSON document is chosen by the client: `JSON.GET key` of a huge document, or a path such as `$..*`,
  serializes or walks all of it (the path budget bounds the work, there is no quota), and a change by path runs under the
  key's lock. Like `VSIM`, it is open to the `readonly` role.
* The cost of a similarity search is chosen by the client: `VSIM` with `EF` / `COUNT` up to 10,000, or `TRUTH` (an exact scan of the
  whole set), is allowed to the `readonly` role, and there is no per-command CPU quota. Keep untrusted clients out with network
  controls and `--max-clients`.
* No audit log of administrative commands (use `MONITOR` / `SLOWLOG` for ad-hoc inspection).
* Without `--zk-connect` there is no automatic failover: a partitioned primary keeps accepting writes until an operator intervenes.
  With it, ZooKeeper becomes part of the trust base: whoever can write its znodes can choose the primary and read node
  addresses. Keep ZooKeeper on a trusted network, enable digest ACLs (`--zk-auth-file` / `REDCAKE_ZK_AUTH`), and note that RedCake does
  not encrypt its ZooKeeper connection itself.
* Not hardened against a malicious *replica operator* — a replica with the API key can read everything.

## Hardening checklist
1. Run as an unprivileged user (see `deploy/redcake.service` and the Dockerfile).
2. Set a long random API key *or* an ACL file; keep secrets out of `ps` and out of config files in git.
3. Enable TLS if traffic leaves one host; enable `--tls-client-auth` between nodes you control.
4. Set `--maxmemory` and `--max-clients`; keep `--timeout` > 0 for untrusted networks.
5. Firewall the data port and the metrics port; scrape metrics from a trusted host only.
6. Watch `failed_authentications` (INFO stats / `redcake_auth_failures_total`).
