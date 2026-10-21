# Fast Lane Terminal Multiplexer Protocol v1

Status: host-side v1 frozen for P04 tests on 2026-10-21. Owner: P04.
This document describes the host `runtime/fastlane-terminal` codec and state machine.
It is **not** wire-compatible with the payload contract in
`fastlane-payload/docs/protocol.md` (FLN1); the field-by-field difference and the
recommended resolution are in
`docs/reports/tickets/fastlane-P04/protocol-alignment.zh-CN.md`. The shared byte
fixture is `src/test/resources/terminal-frames-v1.txt` and is P04-only until a
cross-lane codec is chosen.

## 1. Transport and framing

One host-initiated vsock connection carries every PTY session. The payload listens;
the host connects with `VirtualMachine.connectVsock(port)`. The payload never
connects out.

```
offset  size  field
0       4     magic = 0x4F 0x53 0x46 0x4C ("OSFL")
4       1     version = 1
5       1     type
6       4     sessionId (signed int, big-endian)
10      4     length (unsigned int, big-endian, 0 .. 1048576)
14      N     payload
```

- All integers are big-endian.
- `length > 1048576` is `FRAME_TOO_LARGE`; a negative length is `PROTOCOL_LENGTH`.
- A frame is rejected on bad magic, version != 1, unknown type, truncated header
  or payload, trailing bytes in a single-frame decode, or a session id that
  contradicts the frame type.
- Session id 0 is reserved for connection-level frames. Session ids are positive
  integers; the host allocates monotonically increasing ids and never reuses one.
  The guest rejects a reused retired id with `SESSION_RETIRED`.

## 2. Frame types

| type | value | direction | session | purpose |
| --- | --- | --- | --- | --- |
| HELLO | 1 | both | 0 | capabilities, max frame, initial credit |
| OPEN_PTY | 2 | host→guest request / guest→host ack | >0 | create a PTY / confirm with input credit |
| DATA | 3 | both | >0 | raw terminal bytes |
| RESIZE | 4 | both | >0 | columns/rows |
| SIGNAL | 5 | both | >0 | deliver a Unix signal number |
| CLOSE | 6 | both | >0 | half-close the sender's side |
| EOF | 7 | both | >0 | half-close with an exit code |
| PING | 8 | both | 0 | liveness nonce |
| PONG | 9 | both | 0 | echo the nonce |
| METRICS | 10 | both | 0 | empty request / 64-byte response |
| ERROR | 11 | both | 0 or >0 | typed error; fatal for a session |
| CREDIT | 12 | both | >0 | grant the peer more DATA credit |

DATA, PONG, ERROR and CREDIT are required companions to the command list in the
ticket; they are part of v1, not extensions.

## 3. Payload layouts

### HELLO (12 bytes)
```
maxFrameBytes  int32  1 .. 1048576
initialCredit  int32  1 .. 4194304
features       int32  bitmask
```
Feature bits: `RESIZE=1`, `SIGNAL=2`, `METRICS=4`, `EXIT_CODE=8`. Unknown bits are
ignored. HELLO is recommended as the first frame but is not a hard gate: every
frame carries its own version, so a missing HELLO cannot change decoding.

### OPEN_PTY request (14 + name bytes)
```
columns       int32  1 .. 10000
rows          int32  1 .. 10000
outputCredit  int32  1 .. 4194304   credit the host grants for guest→host DATA
nameLength    uint16 0 .. 1024
name          UTF-8  advisory session title; never a path
```
The guest creates the PTY and replies with an OPEN_PTY **ack**:
```
inputCredit   int32  1 .. 4194304   credit the guest grants for host→guest DATA
```
The host's session is `OPENING` with `sendCredit=0` until this ack arrives.
The guest's session is `OPENING` until it accepts; accepting sets its
`receiveCredit=inputCredit` and its `sendCredit=request.outputCredit`.

### DATA
Raw bytes, length 1 .. 1048576. Zero-length DATA is `INVALID_PAYLOAD`.
The sender must have at least `length` send credit. The receiver must have at
least `length` receive credit; otherwise it sends `CREDIT_EXCEEDED` and resets
the session.

### RESIZE (8 bytes)
```
columns int32 1 .. 10000
rows    int32 1 .. 10000
```
The guest applies `TIOCSWINSZ` to the PTY; the host applies it to its emulator.

### SIGNAL (1 byte)
```
signal uint8 1 .. 64
```
The guest maps it to `kill(pid, signal)` for the PTY child. The host uses it for
local UI actions only.

### CLOSE (0 bytes)
The sender half-closes its own side. The receiver marks the remote half closed,
notifies the PTY owner, and should terminate the child and send EOF.

### EOF (4 bytes)
```
exitCode int32  (-1 means unobservable)
```
The sender half-closes its own side and reports the child exit code. The receiver
marks the remote half closed.

### PING / PONG (8 bytes)
```
nonce int64
```
PONG echoes the nonce. No terminal content is involved.

### METRICS
An empty payload is a request. A response is 64 bytes, eight int64 counters in
this order:
```
sessionsOpened, sessionsClosed, framesIn, framesOut,
bytesIn, bytesOut, creditStalls, protocolErrors
```
Counters are non-negative. They never include terminal bytes or paths.

### ERROR (2 + message bytes)
```
code    uint16  TerminalErrorCode
message UTF-8   <= 512 bytes, protocol text only
```
An ERROR with a positive session id is fatal for that session: the receiver
resets it after notifying the application. An ERROR with session 0 is
connection-level and does not close PTY sessions by itself.

## 4. Credit and backpressure

- Each direction has independent credit. `CREDIT` sent by endpoint A grants A's
  peer permission to send DATA **to A**.
- Initial credit: the host grants `outputCredit` in OPEN_PTY; the guest grants
  `inputCredit` in the ack. Both default to 262144 bytes in the host implementation.
- A sender decrements `sendCredit` by the DATA length. A receiver decrements
  `receiveCredit` by the DATA length before delivering it.
- The receiver returns credit only when its consumer has accepted the bytes:
  `grantCredit(sessionId, n)` adds `n` to its receive window and sends `CREDIT`.
  A slow terminal therefore stops the peer instead of growing a queue.
- `sendData` accepts at most the current credit, the peer's announced
  `maxFrameBytes`, and at most 1048576 bytes, and returns the accepted count.
  The caller must retry the remainder later. A zero or partial return is a
  visible backpressure event (`creditStalls` increments).
- The receive window is capped at 4194304 bytes. A CREDIT that would overflow it
  is `CREDIT_OVERFLOW` and resets the session.

## 5. Session state machine

Two independent half-close flags are tracked per session:

- `localHalfClosed`: this endpoint sent CLOSE or EOF.
- `remoteHalfClosed`: this endpoint received CLOSE or EOF.

```
OPENING --accept--> OPEN
OPEN --local CLOSE/EOF--> HALF_CLOSED_LOCAL
OPEN --remote CLOSE/EOF--> HALF_CLOSED_REMOTE
HALF_CLOSED_LOCAL --remote CLOSE/EOF--> CLOSED
HALF_CLOSED_REMOTE --local CLOSE/EOF--> CLOSED
```

- DATA is allowed only while `!localHalfClosed` (send) and `!remoteHalfClosed`
  (receive).
- CLOSE/EOF are idempotent on the sending side.
- A duplicate OPEN_PTY for an active id is `SESSION_EXISTS`; for a retired id it
  is `SESSION_RETIRED`.
- The host permits at most 128 live sessions by default; exceeding it is
  `SESSION_LIMIT`.
- `vmStopped()` moves every session to CLOSED with reason `VM_STOPPED`, zeroes
  both credits, and writes no frames. Every later send fails with `VM_STOPPED`.

## 6. Error codes

| code | value | meaning |
| --- | --- | --- |
| PROTOCOL_MAGIC | 1 | bad magic |
| PROTOCOL_VERSION | 2 | version != 1 |
| FRAME_TOO_LARGE | 3 | length > 1048576 |
| UNKNOWN_TYPE | 4 | unknown opcode |
| UNKNOWN_SESSION | 5 | frame for an id that does not exist |
| SESSION_EXISTS | 6 | duplicate active id |
| SESSION_LIMIT | 7 | live session limit reached |
| SESSION_RETIRED | 8 | id was already closed |
| SESSION_CLOSED | 9 | operation on a closed/not-open session |
| CREDIT_EXCEEDED | 10 | DATA exceeds the receive window |
| CREDIT_OVERFLOW | 11 | CREDIT would exceed the cap |
| INVALID_PAYLOAD | 12 | malformed payload / session id |
| WRONG_ROLE | 13 | guest called a host-only operation or vice versa |
| VM_STOPPED | 14 | VM is gone |
| TRUNCATED_FRAME | 15 | EOF inside a header or payload |
| PROTOCOL_LENGTH | 16 | negative length or trailing bytes |
| INTERNAL | 17 | unknown catch-all |

## 7. Fixture

`src/test/resources/terminal-frames-v1.txt` contains one line per frame:
`name type sessionId full-frame-hex`. It covers HELLO, OPEN_PTY, DATA, RESIZE,
SIGNAL, CLOSE, EOF, CREDIT, PING, PONG, ERROR and METRICS. P02 should decode and
re-encode every line byte-for-byte before integrating a PTY.

## 8. P02 obligations and open questions

- Create a PTY per OPEN_PTY, apply initial rows/columns, and run the shell with
  `PATH`/`HOME`/`TMPDIR` from the sealed toolbox and encrypted storage.
- Bridge PTY master bytes to DATA; apply RESIZE via `TIOCSWINSZ`; map SIGNAL to
  the child; send EOF with the child exit code; kill the child on CLOSE or VM stop.
- Do not assume TCP or a writable executable directory.
- Not tested here: real vsock, real PTY, Android terminal UI wiring, and
  per-ROM `connectVsock` behavior. Those belong to DV-02.
