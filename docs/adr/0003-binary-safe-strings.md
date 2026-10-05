# ADR 0003 — Binary-safe values with ISO-8859-1 "byte strings"

Status: accepted

## Problem
Decoding RESP bulk strings as UTF-8 silently replaces invalid sequences, corrupting images, protobuf,
compressed data — anything that is not text.

## Decision
Keep Java `String` as the value type but decode/encode **every** byte of the protocol with ISO-8859-1.
That charset maps byte 0–255 to char 0–255 one-to-one, so a `String` is a lossless byte string.

## Consequences
* No refactor of the command/store code; `String.length()` equals the byte length (`STRLEN`, `APPEND`).
* Java compact strings store such strings with one byte per char → memory use ≈ real byte length.
* Text that came from a Unicode source (the operator's API key) must be converted to its UTF-8 byte form
  before comparison; `RedCakeAuthConfig` does this.
* The AOF and the replication stream use the same encoding (`RespCommandCodec`).
* Case folding (`toUpperCase`) is applied to command names and options only, never to data.

## Alternatives
`byte[]` end to end (cleanest, but needs wrapper keys with hash/equals everywhere and a rewrite of every
command) — revisit if profiling shows String overhead matters.
