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
| Over-privileged clients | ACL users with `admin` / `readwrite` / `readonly` roles (`--acl-file`); admin-only: `FLUSHALL`, `FLUSHDB`, `CONFIG`, `SLOWLOG`, `SHUTDOWN`, `REPLICAOF`, `PSYNC`, `MONITOR`, `CLIENT KILL`, `BGREWRITEAOF` |
| Eavesdropping / tampering | TLS for clients and replication (`--tls-keystore`), optional mutual TLS (`--tls-client-auth`), host-name verification on the replica → primary link |
| Malformed or hostile protocol input | Bounded parser (1024 args, 1 MiB per bulk string, 8 MiB per command, 64-byte header lines); protocol errors close the connection; randomised fuzz test in CI |
| Memory exhaustion | `--maxmemory` + eviction policies, `--max-clients`, bounded replica queues (64 MiB), idle timeouts |
| Slow-client (slowloris) | `--timeout`, 15 s authentication deadline |
| Secret leakage | API key via `REDCAKE_API_KEY` / `--api-key-file`; TLS passwords via files/env; ACL file stores only SHA-256 digests; `CONFIG GET` never returns secrets; `AUTH` is never written to the slow log or `MONITOR` |
| Log injection | Error text is stripped of CR/LF; client data is never logged |

## Known gaps — read before exposing RedCake
* The ACL has three roles, not per-key or per-command rules. Passwords are single SHA-256 digests (fast to
  brute-force offline if the ACL file leaks) — use long random passwords.
* `/metrics` and `/health` (when `--metrics-port` is set) are **unauthenticated**; they expose only counters
  and sizes, never keys or values. Bind to loopback or firewall the port.
* Pub/Sub delivery runs on the publisher's thread: a subscriber that stops reading can slow publishers.
* No audit log of administrative commands (use `MONITOR` / `SLOWLOG` for ad-hoc inspection).
* No automatic failover: a partitioned primary keeps accepting writes until an operator intervenes.
* Not hardened against a malicious *replica operator* — a replica with the API key can read everything.

## Hardening checklist
1. Run as an unprivileged user (see `deploy/redcake.service` and the Dockerfile).
2. Set a long random API key *or* an ACL file; keep secrets out of `ps` and out of config files in git.
3. Enable TLS if traffic leaves one host; enable `--tls-client-auth` between nodes you control.
4. Set `--maxmemory` and `--max-clients`; keep `--timeout` > 0 for untrusted networks.
5. Firewall the data port and the metrics port; scrape metrics from a trusted host only.
6. Watch `failed_authentications` (INFO stats / `redcake_auth_failures_total`).
