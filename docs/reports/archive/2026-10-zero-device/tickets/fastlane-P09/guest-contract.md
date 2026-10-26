# P09 guest contract: `distccd --inetd` over vsock

Status: zero-device contract for P02. No payload code is changed by P09; this
document is the hand-off. Everything below is host-tested only where stated;
the protected-payload path is `not-tested` until DV-03. The field-by-field
comparison with P02's `distcc_bridge.cpp` is in
[`guest-contract-alignment.zh-CN.md`](guest-contract-alignment.zh-CN.md).

## 1. Data path

```
QEMU guest: distcc clang -c foo.c
  local clang -E, distcc protocol over TCP to the forwarded host port
        |
        v
Host app: DistccTcpVsockBridge, listener on 127.0.0.1:<ephemeral>
  one TCP connection -> one FastLaneChannel (vsock stream)
        |
        v
Payload: accept one vsock connection
  fork/exec /mnt/apk/bin/distccd --inetd
  child stdin/stdout = the vsock stream (dup2 or equivalent byte pumps)
        |
        v
distccd runs clang from /mnt/apk/bin, returns DONE/STAT/SERR/SOUT/DOTO
```

The host bridge and the payload are byte bridges. Neither parses, rewrites or
logs the distcc protocol. The distcc client and `distccd` own the protocol.

## 2. Host-side invariants (implemented in `DistccTcpVsockBridge`)

- The listener binds a loopback address only; non-loopback addresses are
  rejected in the constructor.
- Each accepted TCP connection opens exactly one `FastLaneChannel`. There is no
  multiplexing across jobs and no shared buffer between connections.
- At most `maxConnections` jobs are active. Excess connections are closed
  before a channel is opened and reported as `LIMIT_REJECTED`.
- `idleTimeoutMs > 0` closes a connection with no bytes in either direction as
  `IDLE_TIMEOUT`; `0` disables the watchdog. **Production callers pass 0**: the
  payload owns the compiler idle limit (P02 uses 120 s on the child stdout
  poll). A host watchdog shorter than that can kill a legitimate compile; if a
  host timeout is required for diagnostics, set it above 180 s.
- A client half-close closes the channel output so `distccd` sees stdin EOF,
  but the response is still drained back to the client.
- `close()` finishes active connections as `BRIDGE_CLOSED` and stops the
  listener and watchdog.
- Counters only: outcome, bytes from client, bytes to client, duration. No
  source, object, argv or token content is logged.

The QEMU guest cannot reach a host `127.0.0.1` socket directly. The host
listener stays on loopback; the guest reaches it through the QEMU user-network
forward (passt/gvproxy or `hostfwd`). The guest-side `DISTCC_HOSTS` value is the
forwarded address, for example `10.0.2.2:<hostPort>`; do not bind the bridge to
`0.0.0.0` to avoid this mapping.

## 3. Payload-side contract (P02)

For each accepted vsock connection on the distcc port:

1. Enforce the payload job cap (recommended: `min(vCPU count, 4)`); if full,
   close the connection immediately so the client can fall back locally.
2. `fork`/`exec` (or `posix_spawn`) exactly:

   ```
   /mnt/apk/bin/distccd --inetd
   ```

   Resolve the path from `/mnt/apk/bin/manifest.json`; do not rely on `PATH`
   alone. If the binary is absent, fail the job as `DISTCC_COMPILER_MISMATCH`
   with reason `toolbox_missing_distccd`; do not run a host compiler.
3. Connect the child's stdin and stdout to the vsock stream. `dup2` of the
   accepted vsock fd onto fd 0 and fd 1 is preferred; an explicit two-way pump
   is equivalent. The child must see EOF on stdin when the host half-closes.
4. Keep stderr out of the protocol: `dup2` it to `/dev/null` (or a payload log
   that is never fd 1). `distccd` protocol bytes are binary. The host bridge
   never receives child stderr; the P02 implementation already does this.
5. `waitpid` the child after its stdout reaches EOF with a bounded grace
   (P02 uses 5 s), then terminate and reap. The overall compile deadline belongs
   to the distcc client, not to an unbounded payload wait. On payload shutdown,
   terminate children and reap them; no orphans.
6. Do not parse the distcc protocol, do not rewrite argv, and do not cache or
   log source/object bytes.

Child environment (aligned with P02 `child_environment`; no `DISTCCD_PATH`):

```
PATH=/mnt/apk/bin:/mnt/apk/bin/usr/bin
HOME=/mnt/encryptedstore/home
TMPDIR=/mnt/encryptedstore/tmp
XDG_CACHE_HOME=/mnt/encryptedstore/cache
SHELL=/mnt/apk/bin/sh
TERM=xterm-256color
LANG=C.UTF-8
```

`TMPDIR` and the child working directory must be writable and must not be the
read-only APK mount. P02 uses `storage_root/tmp` as the working directory. Do
not set `DISTCC_CMDLIST` unless a compiler allowlist is added with a separate
security review; the v1 threat model trusts the host bridge endpoint. The
bridge has no authentication of its own, so the host must bind it only for an
active session, use an ephemeral port, and tear it down with the VM.

## 4. Guest-side configuration

On the QEMU guest, using the forwarded host address:

```sh
export CC="distcc clang"
export DISTCC_HOSTS="10.0.2.2:<hostPort>"
export DISTCC_FALLBACK=1      # default; a missing Fast Lane must not fail the build
make -j"$(nproc)"
```

For a test that must prove remote execution, set `DISTCC_FALLBACK=0` and use
`distcc --show-hosts` plus the host bridge counters. A fallback success is not
evidence that the Fast Lane compiled anything.

The guest keeps linking locally. distcc returns `.o` bytes; the guest linker
and its libraries are unchanged.

## 5. Compiler consistency (v1)

- v1 supports C only, with clang on both sides.
- The guest's local clang performs preprocessing (`-E`); `distccd` compiles the
  preprocessed input with the server clang.
- Client and server must use the same clang version, the same `-target` (the
  guest's actual target) and the same language/ABI flags. distcc transmits the
  argv, but it does not reconcile compiler versions.
- If the versions or targets differ, do not silently fall back to a different
  compiler; report `DISTCC_COMPILER_MISMATCH` and let the client fall back
  locally.
- C++ is `not-tested`: libstdc++/libc++ ABI and header/version matching need a
  separate validation. GCC is out of scope for v1.

The minimal toolbox profile does not ship clang or distccd yet. The
`distcc-clang` profile in `fastlane-toolbox/manifest.json` is `not-tested` and
must not be enabled until DV-03 confirms Bionic execution, compiler versions
and APK size.

## 6. Failure mapping

| Condition | Host/payload signal | Client behaviour |
| --- | --- | --- |
| vsock/channel open fails | `CHANNEL_FAILED` | fallback (if enabled) |
| no bytes before idle deadline | `IDLE_TIMEOUT` | fallback |
| job cap reached | `LIMIT_REJECTED` | fallback |
| bridge/VM stopped | `BRIDGE_CLOSED` | fallback |
| distccd missing/spawn fails | connection closes; payload log records the spawn error | fallback; `DISTCC_COMPILER_MISMATCH` is the product-level target, not a P02-current guarantee |
| compiler/version mismatch | distccd non-zero/`STAT` | local fallback; no silent alternate compiler |
| distcc protocol error | client/server sees EOF or bad token | local fallback; do not retry forever |

`DISTCC_BRIDGE_FAILED`, `DISTCC_PROTOCOL_FAILED` and
`DISTCC_COMPILER_MISMATCH` remain the product-level categories. The host
bridge reports the transport outcome; the payload/control channel owns the
compiler-specific category.

## 7. DV-03 acceptance checklist

- Execute `/mnt/apk/bin/distccd --inetd` from the protected payload; no TCP in
  the payload.
- Compile a C file with guest `distcc clang`; verify a non-empty `.o`, link it
  in the guest and run it.
- Repeat with `DISTCC_FALLBACK=0`; confirm the object came from Fast Lane via
  host counters.
- Measure n >= 10 end-to-end builds against QEMU-local compilation; report
  median/IQR and failures. Stop productization below 1.5x.
- Confirm concurrent jobs do not interleave streams and that stopping the VM
  terminates children without orphans.
- Confirm no source/object bytes appear in logs.
