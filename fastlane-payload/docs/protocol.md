# Fast Lane payload protocol v1

Status: v1 zero-device contract, unchanged bytes as of 2026-10-21. The C payload is
the normative implementation. P04's host `TerminalMux` now speaks this `FLN1`
contract through `Fln1TerminalCodec`; `OSFL` is retired by ACR-04 (see §8).

## 1. Seam

The host owns one `FastLaneSession` (P01) with four vsock ports. The payload listens
on all four; the host always initiates because `microdroid_payload` may not
`connect()` vsock (AOSP `neverallow microdroid_payload self:vsock_socket connect`).

| port | transport | content |
| --- | --- | --- |
| control | framed | control requests, PTY lifecycle, export/import, metrics |
| terminal | framed | PTY data mux for the terminal UI (same frame format) |
| proxy | raw bytes | one SOCKS5/HTTP-CONNECT tunnel per connection; payload is byte-transparent |
| distcc | raw bytes | one `distccd --inetd` child per connection; payload is byte-transparent |

Framed ports carry exactly one `HELLO` as the first frame. A second `HELLO`, a frame
before `HELLO`, or an unknown magic/version is answered with `ERROR` and the
connection is closed.

The payload never opens a TCP listener for control/terminal. A guest TCP listener is
not assumed to be allowed by SELinux; the transport is selected by the host adapter
(`AF_VSOCK` in production, a Unix socket in host tests).

## 2. Frame

All integers are big-endian.

```
+--------+---------+------+------------+--------+---------+
| magic  | version | type | session_id | length | payload |
| 4 B    | 1 B     | 1 B  | 4 B        | 4 B    | length  |
+--------+---------+------+------------+--------+---------+
```

- magic: `46 4C 4E 31` (`FLN1`)
- version: `1`
- session_id: 0 for connection-scoped commands; otherwise the PTY session id
- length: `0..65536`; larger frames are `PROTOCOL_MALFORMED`
- header size: 14 bytes

Strings are UTF-8, length-prefixed with `u16`; they never contain NUL. `argv` is
`u8 count` followed by count strings; count is `1..16`, total payload <= 4096.

## 3. Types

Requests use `0x0n`; successful replies use `0x8n`; `0xFF` is an error reply.

| type | name | direction | payload |
| --- | --- | --- | --- |
| 0x01 | HELLO | host -> guest | `u16 min_ver, u16 max_ver, u8 token_len, token` |
| 0x81 | HELLO_OK | guest -> host | `u16 ver, u32 caps, u32 max_frame, u32 initial_window` |
| 0x02 | PING | both | opaque `0..32` bytes |
| 0x82 | PONG | both | echo of PING |
| 0x03 | OPEN_PTY | host -> guest | `u16 cols, u16 rows, argv` |
| 0x83 | PTY_OPENED | guest -> host | `u32 pid, u32 initial_window` |
| 0x04 | PTY_DATA | both | raw bytes (<= 32768) |
| 0x05 | RESIZE | host -> guest | `u16 cols, u16 rows` |
| 0x85 | RESIZED | guest -> host | empty |
| 0x06 | SIGNAL | host -> guest | `u8 signum` |
| 0x07 | CLOSE | host -> guest | `u8 force` (0 = HUP then KILL after grace, 1 = KILL) |
| 0x87 | CLOSED | guest -> host | `u8 reason` (0 exited, 1 signalled, 2 forced) |
| 0x08 | EOF | guest -> host | empty: PTY master reached EOF |
| 0x09 | EXIT | guest -> host | `i32 exit_code, u8 term_signal` |
| 0x0A | WINDOW | host -> guest | `u32 credit_bytes` |
| 0x0B | EXPORT | host -> guest | `u8 format (0=tar), u16 root_count, roots` |
| 0x8B | EXPORT_BEGIN | guest -> host | `u8 format, u16 entry_count_hint (0 unknown)` |
| 0x0C | EXPORT_DATA | guest -> host | tar bytes (<= 65536) |
| 0x0D | EXPORT_END | guest -> host | `u64 total_bytes` |
| 0x0E | IMPORT_BEGIN | host -> guest | `u8 format (0=tar), u64 total_bytes (0 = unknown)` |
| 0x0F | IMPORT_DATA | host -> guest | tar bytes (<= 65536) |
| 0x10 | IMPORT_END | host -> guest | `u64 total_bytes, 32-byte SHA-256` |
| 0x90 | IMPORT_DONE | guest -> host | `u64 files, u64 bytes` |
| 0x11 | METRICS | host -> guest | empty |
| 0x91 | METRICS_OK | guest -> host | `u32 pty_sessions, u64 pty_in, u64 pty_out, u64 proxy_bytes, u32 distcc_jobs, u32 uptime_s` |
| 0xFF | ERROR | guest -> host | `u16 code, message` |

`OPEN_PTY` `argv[0]` is resolved by the payload: a name without `/` is looked up in
`/mnt/apk/bin`; an absolute path must resolve under `/mnt/apk`. Encrypted storage is
never executable.

Platform note (DV-01A, 2026-10-21): `/mnt/apk` is a zipfuse mount. On Android 16 the
`microdroid_app` SELinux domain is allowed `execute` but not `execute_no_trans` on
`system_file`, so `access(X_OK)` succeeds while `execve` returns EACCES. This is a
platform policy blocker, not a wire or toolbox-resolution rule; do not substitute
`/system/bin/sh`, encrypted-storage execution or an in-process shell. See
`.scratch/orchestella-fastlane/issues/DV-01A-toolbox-exec-selinux.md`.

`EXPORT` roots are relative to the storage root and must be one of the policy-allowed
exports (`home`, `workspace`). `IMPORT_BEGIN` writes only to a staging directory; the
staging tree is swapped in on a verified `IMPORT_END`.

Storage limits are enforced, never truncated: one tar entry may be at most
`8 GiB - 1` bytes (11 octal digits, the ustar field limit), an export or import may
carry at most 16 GiB and 100000 entries, and ustar metadata is capped at 1 MiB per
PAX/LongName record. Import swaps only the top-level roots present in the archive
(`home` and/or `workspace`); a root absent from the tar is left untouched.

## 4. Flow control

Each PTY starts with `initial_window` bytes (default 262144) of output credit. The
payload decrements credit for every `PTY_DATA` byte it sends and stops reading the
PTY master when credit is below one frame; the host grants credit with `WINDOW`.
Credit is per session and never exceeds `2 * initial_window`. A slow host therefore
backpressures the PTY instead of growing payload memory.

## 5. Error codes

| code | name | spec class |
| --- | --- | --- |
| 1 | PROTOCOL_MALFORMED | TERMINAL_PROTOCOL_FAILED |
| 2 | UNSUPPORTED_VERSION | TERMINAL_PROTOCOL_FAILED |
| 3 | BAD_TOKEN | TERMINAL_PROTOCOL_FAILED |
| 4 | UNKNOWN_COMMAND | TERMINAL_PROTOCOL_FAILED |
| 5 | SESSION_NOT_FOUND | TERMINAL_PROTOCOL_FAILED |
| 6 | SESSION_LIMIT | TERMINAL_PROTOCOL_FAILED |
| 7 | EXEC_FAILED | TOOLBOX_EXEC_FAILED |
| 8 | STORAGE_FAILED | STORAGE_FAILED |
| 9 | PATH_REJECTED | STORAGE_FAILED |
| 10 | EXPORT_BUSY | STORAGE_FAILED |
| 11 | EXPORT_IO | STORAGE_FAILED |
| 12 | PAYLOAD_BUSY | TERMINAL_PROTOCOL_FAILED |
| 13 | UNSUPPORTED | TERMINAL_PROTOCOL_FAILED |
| 14 | INTERNAL | TERMINAL_PROTOCOL_FAILED |

Error messages are short, fixed, and never contain file contents, tokens, keys, or
terminal input.

## 6. Capabilities

`HELLO_OK.caps` bits: `1` terminal, `2` proxy, `4` export, `8` import, `16` distcc,
`32` metrics. A missing bit means the feature is unavailable on this device; the host
must not infer support from the protocol version alone.

## 7. Framing rules

- A reader must tolerate short reads: read exactly 14 bytes, then exactly `length`.
- Zero-length frames are valid for control types that define no payload.
- A frame type that is not in §3 is rejected with `UNKNOWN_COMMAND` before dispatch,
  whether it looks like a request or a reply. There is no forward-compatible reply
  namespace in v1; adding a type requires a protocol revision.
- The payload closes a connection after a malformed frame; it does not resynchronise
  on the next magic, so a desynchronised stream cannot inject commands.
- A transport must not return more bytes than requested. A short read (including a
  one-byte read) is normal; a read of zero before the header is clean EOF, and a
  zero-length read in the middle of a frame is `PROTOCOL_MALFORMED`.
- The first frame on a framed port must be `HELLO`; the payload applies a 10-second
  receive timeout until `HELLO` succeeds, then removes it. A client that connects and
  sends nothing is closed without consuming a connection slot indefinitely.

## 8. Version and cross-lane compatibility (2026-10-21)

`FLN1` v1 is frozen: magic, header, frame types, limits, and error codes in this
document do not change in this revision. The 2026-10-21 hardening pass changed only
implementation-side validation and resource lifetime; it did not add or renumber a
frame.

P04 (`runtime/fastlane-terminal`) now speaks this `FLN1` contract through
`Fln1TerminalCodec`; `OSFL` is retired by ACR-04. The host and C++ tests share
`protocol/fln1-golden.json`, and a host process E2E over a Unix socket passes.
This payload still implements only one wire protocol. A real protected-VM
vsock/PTY connection is **not** verified by that E2E; DV-02 owns the device
result.
