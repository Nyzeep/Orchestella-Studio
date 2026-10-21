# Fast Lane sealed toolbox (P03)

The Fast Lane payload executes tools from the APK's read-only `/mnt/apk/bin`.
`zipfuse` grants execute permission to entries under APK-root `bin/` (AOSP
`guest/zipfuse/src/inode.rs`, `EXECUTABLE_FILE_MODE`), so the toolbox is
packaged there, not under `lib/` and not installed at runtime.

## Decision: candidate B, static BusyBox

v1 uses a single static BusyBox binary plus generated applet wrappers. It does
not use a Termux bootstrap.

Evidence for rejecting candidate A in v1:

- The pinned Termux bootstrap `bootstrap-2026.10.18-r1+apt.android-7`
  (`bootstrap-aarch64.zip`, 32 MiB compressed) restores symlinks from
  `SYMLINKS.txt`; some targets are absolute
  `/data/data/com.termux/files/usr/bin/...`, not relative to `$PREFIX`.
- Its `bin/bash` contains baked `/data/data/com.termux/files/usr/...` paths.
  Relocating the prefix to `/mnt/apk/bin` therefore needs `termux-exec` and
  prefix rewriting, and that cannot be proven without DV-02 on a device.
- The bootstrap's dynamic binaries also need the matching `lib/` closure and
  `LD_LIBRARY_PATH`, which increases the APK surface and the failure modes.

Evidence for candidate B:

- Alpine Linux 3.20 `busybox-static` 1.36.1-r31 is an aarch64 static binary,
  extracted `bin/busybox.static` = 1,115,944 bytes.
- Its PT_LOAD segments are aligned `0x10000` (64 KiB), so it passes the 16 KB
  ELF rule.
- It needs no `$PREFIX`, no dynamic loader closure and no zip symlink.
- The assembled v1 toolbox is 2,234,863 bytes across 50 entries: BusyBox, a
  copy at `bin/sh` (so the kernel can exec it directly), and 48 applet
  wrappers. The generated `bin/manifest.json` is 10,639 bytes, so the whole
  `bin/` tree is 2,245,502 bytes. It is far below the 80 MiB starting budget.

A Termux/clang profile remains a separate, not-tested decision because a
compiler would dominate APK size and needs its own ABI/version validation.

## Layout

```
/mnt/apk/bin/
  manifest.json      generated manifest: tools, SHA256, license, source, target, deps
  busybox            pinned static BusyBox
  sh                 byte-identical copy of busybox (no symlink in the APK)
  ls, cat, grep ...  generated wrappers
```

Each wrapper is:

```sh
#!/mnt/apk/bin/busybox sh
exec /mnt/apk/bin/busybox ls "$@"
```

The shebang and exec both use the fixed AVF mount path `/mnt/apk/bin`. No
symlinks are stored in the APK.

The generated manifest is also the P02 contract: `/mnt/apk/bin/manifest.json`.
P02 should read `install.guestPath`, `install.path`, `tools[]` and `entries[]`;
it must not assume a writable toolbox.

## Build

```bash
python3 tools/fastlane-build/fastlane_build.py build \
  --manifest fastlane-toolbox/manifest.json \
  --out build/fastlane-toolbox \
  --cache build/fastlane-cache

python3 tools/fastlane-build/fastlane_build.py verify-toolbox \
  --toolbox build/fastlane-toolbox

python3 tools/fastlane-build/reproduce.py \
  --manifest fastlane-toolbox/manifest.json \
  --cache build/fastlane-cache \
  --runs 2
```

The build downloads the pinned Alpine package, verifies its SHA256 and the
extracted binary SHA256, writes the wrappers, and fails if an ELF is not
AArch64, is dynamically linked, or has a PT_LOAD below 16 KB. A `--fixture`
mode exists for host tests only and is rejected by the production APK gate.

`SOURCE_DATE_EPOCH` defaults to `1792540800` (2026-10-21T00:00:00Z) and is
recorded in the runtime manifest; file mtimes and injected ZIP timestamps use
it, so the same inputs produce the same tree. `verify-toolbox` prints a
canonical `treeDigest`, and `reproduce.py` builds twice and fails if the
digests differ.

## Verification gates

- `verify-toolbox` rejects unsafe paths, non-0755 modes, symlinks, unlisted
  files, extra top-level files, size/SHA mismatches, non-AArch64 or dynamic
  ELF, and 4 KB-aligned PT_LOAD segments.
- `verify-apk` rejects duplicate, traversal, directory and symlink entries;
  it accepts mode 0 as the zipfuse default but rejects a non-executable
  explicit mode. It compares the embedded manifest byte-for-byte.
- `prove_gate_blocks.py` deliberately tampers with SHA, signatures, sizes,
  entries, paths and licenses and requires every gate to fail closed.

## Licenses

BusyBox is GPL-2.0-only. The Alpine package pin, binary SHA256, upstream
source SHA256 and Alpine packaging tree are in `manifest.json`. Any release
that contains the binary must publish the corresponding source archive (or a
written offer) alongside it; the exact delivery steps are in
`NOTICE-fastlane.md`. The v1 toolbox does not contain distcc, clang or Termux
code. The license gate cross-checks `manifest.json`, `SBOM.spdx.json` and
`NOTICE-fastlane.md`; a stale checksum, version or package entry fails the
build.

## Not verified

- No execution on an Android device or Microdroid payload (DV-02).
- The Gradle/AGP resource packaging path was not built here (no Android SDK);
  the host verifier and synthetic APK gate are tested instead.
- `zipalign -P 16` was not available locally; the APK task runs it when the
  Android build-tools are present. The CI APK gate fails if zipalign is absent
  rather than silently skipping.
- The real `distccd --inetd` path is host-tested only; protected-payload
  execution and compiler/ABI matching remain DV-03.
