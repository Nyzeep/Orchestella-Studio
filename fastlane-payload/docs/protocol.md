# Fast Lane payload protocol v1

Status: zero-device contract. The C implementation and the host-side terminal mux
(P04) must use these bytes verbatim. Where P04 has not landed yet, this document is
the fixture.

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

`EXPORT` roots are relative to the storage root and must be one of the policy-allowed
exports (`home`, `workspace`). `IMPORT_BEGIN` writes only to a staging directory; the
staging tree is swapped in on a verified `IMPORT_END`.

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
- Unknown request types are rejected with `UNKNOWN_COMMAND`; unknown *reply* types are
  ignored (forward compatibility).
- Frame boundaries are never implied by TCP/vsock packet boundaries.
- The payload closes a connection after a malformed frame; it does not resynchronise
  on the next magic, so a desynchronised stream cannot inject commands.
