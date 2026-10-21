# Fast Lane terminal: host implementation over FLN1 v1

Status: 2026-10-21 integration. The host terminal module implements the canonical
`FLN1` v1 wire contract from `fastlane-payload/docs/protocol.md`. That document
and the C payload are normative; this file only records how the host maps its
state machine onto it. `OSFL` is retired by ACR-04 and no longer exists in
production code.

## 1. Codec seam

- `TerminalCodec` — wire seam: encode/decode/read one logical frame, plus
  `releaseSession` when the mux finishes a session.
- `Fln1TerminalCodec` — the only production codec: 14-byte header
  (`FLN1 | 1 | type | session_id | length`), big-endian, payload <= 65536.
- `TerminalMux` — unchanged responsibility: multi-PTY state machine, half-close
  bookkeeping, credit accounting, VM stop. It knows logical frame kinds only.
- `TerminalMuxChannel` — one framed stream; no threads; malformed frame is
  connection-fatal (FLN1 §7).

## 2. Frame mapping

| logical | host -> guest | guest -> host | notes |
| --- | --- | --- | --- |
| HELLO | 0x01 | 0x81 | host sends `min=1,max=1,token` first; HELLO_OK carries caps/max_frame/initial_window |
| OPEN_PTY | 0x03 | 0x83 | request always uses wire session 0; the payload allocates the PTY id |
| DATA | 0x04 | 0x04 | 1..32768 bytes both directions |
| RESIZE | 0x05 | 0x85 | `u16 cols,u16 rows`; RESIZED is an ack the mux ignores |
| SIGNAL | 0x06 | — | one byte, 1..64 |
| CLOSE | 0x07 | 0x87 | `u8 force`; CLOSED carries reason 0/1/2 |
| EOF | — | 0x08 | empty: PTY master reached EOF |
| EXIT | — | 0x09 | `i32 exit_code, u8 term_signal` |
| WINDOW | 0x0A | — | output credit the host returns to the payload |
| PING/PONG | 0x02 | 0x82 | opaque 0..32 bytes; the host echoes 8-byte nonces |
| ERROR | — | 0xFF | `u16 code, u16 len, message`; the payload never accepts host ERROR |

`METRICS`, `EXPORT`, `IMPORT` are not part of the terminal adapter. FLN1
`METRICS_OK` has different fields from the retired OSFL metrics frame; payload
telemetry is P10's surface.

## 3. Session ownership

The payload allocates PTY ids in `PTY_OPENED`. The codec keeps a FIFO of host
opens (OPEN_PTY is written with wire session 0) and binds the returned id to the
host session. Consequences:

- the host must not predict or reuse a wire id;
- at most the already-written OPEN_PTY frames are matched in order (FLN1 v1 has
  no request correlation);
- `releaseSession` drops the mapping when the mux reaches CLOSED.

## 4. Credit

Only guest->host output is windowed. `PTY_OPENED.initial_window` seeds the host
receive window; `WINDOW` returns credit after the consumer accepts bytes, capped
at `2 * initial_window`. There is no host->guest window in FLN1, so the host may
write DATA up to one 32 KiB frame without waiting. The host clamps its WINDOW
deltas to the remaining headroom; the payload also clamps, and never errors on
overflow.

## 5. EOF and EXIT

The payload sends `EOF` when the master is unusable, then `EXIT` after reaping
the child. `Fln1TerminalCodec.read` holds `EOF` and returns `EXIT` so the mux
sees one real exit status; if the transport ends between them, the held `EOF` is
released with status -1. A bare EOF makes the session unwritable; EXIT closes it.

## 6. Errors

The payload's ERROR is connection-fatal and only travels guest->host. The host
therefore never writes 0xFF; a host-side violation raises
`TerminalProtocolException` and `TerminalMuxChannel` closes the stream. Inbound
codes 1..14 are mapped to the host classification in
`Fln1TerminalCodec.hostCode`; the original code stays in the message as
`fln1=<code> <NAME>`.

## 7. Fixture and tests

`protocol/fln1-golden.json` is the single cross-language fixture (HELLO,
HELLO_OK, OPEN_PTY, PTY_OPENED, PTY_DATA, RESIZE, RESIZED, SIGNAL, CLOSE,
CLOSED, EOF, EXIT, WINDOW, ERROR, PING, PONG). Java and C++ decode each
`frame_hex`, re-encode, and compare bytes.

`Fln1PayloadE2ETest` runs the real `fastlane_payload_host` binary over its Unix
socket test transport when `-Dfln1.payload=<path>` is set; it is skipped
otherwise. Not tested here: AF_VSOCK, Android SELinux, devpts, Android terminal
UI wiring. Those belong to DV-02.
