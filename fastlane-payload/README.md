# fastlane-payload

Guest-side runtime for the protected Microdroid Fast Lane (P02). It is a C++17
shared library with the AOSP `AVmPayload_main()` entry point. It never assumes
guest TCP, chroot, mount, systemd, or writable-executable storage.

## Build

Host (portable code + tests):

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug
cmake --build build -j
ctest --test-dir build --output-on-failure
```

Android arm64-v8a (needs an NDK; not available in the zero-device run):

```sh
ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-android.sh
```

The Android target links a local `libvm_payload.so` **stub** (`src/vm_payload_stub.cpp`)
so the link resolves on the build host. The device resolves the real library from
the AVF APEX, so the stub must not be packaged into the APK. The payload links
`c++_static`; the Microdroid launcher's allowed library list does not include
`libc++_shared.so`.

## Runtime configuration

| variable | meaning |
| --- | --- |
| `FASTLANE_APK_ROOT` | toolbox root (default `AVmPayload_getApkContentsPath()`, `/mnt/apk`) |
| `FASTLANE_STORAGE_ROOT` | encrypted storage (default `AVmPayload_getEncryptedStoragePath()`) |
| `FASTLANE_CONTROL_PORT` / `_TERMINAL_PORT` / `_PROXY_PORT` / `_DISTCC_PORT` | vsock ports; defaults 5100/5101/5102/5103 and must match the launch spec |
| `FASTLANE_AUTH_TOKEN` | optional HELLO token; empty disables the check |
| `FASTLANE_TRANSPORT=unix` + `FASTLANE_SOCKET_DIR` | host-test transport only; production uses `AF_VSOCK` |
| `FASTLANE_DISTCCD` | explicit `distccd` path; empty resolves `<apk>/bin/distccd` |
| `FASTLANE_PROXY_TCP=0` | disable the optional guest loopback TCP proxy endpoint |
| `FASTLANE_INITIAL_WINDOW` / `_MAX_SESSIONS` / `_MAX_CONNECTIONS` | flow-control and limit overrides |

## Behaviour

- Four listeners: control/terminal are framed (`docs/protocol.md`), proxy/distcc
  are raw byte pipes. The host initiates every vsock connection; the payload never
  calls `connect()`.
- PTY children are started with an explicit environment (`PATH`, `HOME`, `TMPDIR`,
  `XDG_CACHE_HOME`, `TERM`, `LANG`) and `chdir` into `home`. Executables must
  resolve inside the APK toolbox; encrypted storage is never executable.
- Export writes a ustar/PAX tar stream of `home` and `workspace`; symlinks,
  sockets, devices, denied credential names, and `..` paths are rejected. Import
  verifies the declared size and SHA-256, extracts into `tmp/.import-*`, and swaps
  `home`/`workspace` into place with rollback.
- Proxy channels are pre-opened by the host and paired FIFO with local tool
  connections; the payload is byte-transparent. The local endpoint is a Unix
  socket in `tmp/`, plus a 127.0.0.1 TCP endpoint when the kernel/SELinux allows it.
  Tools that cannot use a Unix socket need `ALL_PROXY=socks5h://127.0.0.1:<port>`;
  if TCP is denied, the proxy capability is reported only through the Unix path
  and must be treated as unavailable by hosts that need TCP.
- `distccd` runs with `--inetd` by default; one child per vsock connection; stderr
  is discarded so it can never enter the distcc byte stream.
- `SIGPIPE` is ignored: a vanished peer is an EPIPE, never process death. Logs
  contain codes, counts, and timings only — never tokens, keys, source, or input.

## Verification status

Host tests (49 cases) cover framing, short reads, SHA-256 vectors, path safety,
tar export/import rollback, PTY lifecycle/resize/signal/credit/fd leaks, distcc
stdio bridging, proxy pairing/half-close/timeouts, and a full control-server
round trip over Unix sockets. **Not tested:** real vsock, Microdroid SELinux,
real `distccd`, `/mnt/apk` Termux binaries, and the NDK build. Those belong to
DV-01/DV-02/DV-03.

## Attribution

The `AVmPayload_*` names and semantics come from AOSP
`packages/modules/Virtualization/libs/libvm_payload` (Apache-2.0). This project
uses its own implementation and only declares the four functions it calls.
