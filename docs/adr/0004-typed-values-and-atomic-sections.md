# ADR 0004 — Typed values and per-key atomic sections

Status: accepted

## Decision
* A key holds a `ValueEntry(value, expiresAt, version, bytes)`; `value` is a `String` or one of
  `LinkedHashMap` (hash), `ArrayList` (list), `LinkedHashSet` (set), `ZSet` (sorted set).
* Every mutation runs inside `ConcurrentHashMap.compute*`, i.e. under that key's bin lock. Collections are
  mutated **in place** inside that section; the entry wrapper is replaced (new `version`) so `WATCH`, expiry
  events and snapshots can detect the change.
* Reads of collections also run inside the section (`store.read`) so they never observe a half-applied change.
* Empty collections are deleted. Wrong-type access throws `WrongTypeException` → `-WRONGTYPE`.
* Snapshots copy collections under the same lock, so they can be serialized after the write lock is released.
* Memory is estimated per entry (strings by length, collections by element count) for `maxmemory`.

## Consequences
* Command code stays small (`store.mutate(key, TYPE, factory, fn)`); atomicity is a property of the store.
* Collection commands are O(n) in the worst case (`LRANGE`, `SMEMBERS`, `ZRANK`); that is documented.
* Non-deterministic commands (`SPOP`, `SRANDMEMBER`) are not offered: replicas would diverge unless the
  command were rewritten into explicit deletes.
