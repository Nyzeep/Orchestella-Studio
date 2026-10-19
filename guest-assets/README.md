# DroidLayer guest assets

Ticket 25 establishes the reproducible ARM64 guest-kernel and initramfs build.
It does not contain the Android host runtime, a host bridge transport, or any
Guest Bridge command implementation.

## One-command build

From the repository root, with Docker or Podman available:

```bash
./guest-assets/run-in-builder.sh build
```

The command runs an immutable `linux/amd64` builder image from Microsoft
Container Registry. The image itself is fixed by digest. A separately fixed
Ubuntu snapshot in `inputs/inputs.lock` freezes only the additional packages
installed from the `noble`, `noble-updates`, and `noble-security` pockets; it
does not retroactively date the base-image filesystem. The build writes
ordinary files only to `guest-assets/out/`. Linux-only staging (symlinks, Unix
modes and device nodes) stays on the container-local `/tmp` filesystem rather
than the bind-mounted checkout.

Expected delivery files are:

- `Image` — Linux `7.2.3` AArch64 kernel;
- `initramfs.cpio.gz` — deterministic Provisioning initramfs;
- `SHA256SUMS` — stable, lexically ordered GNU-compatible checksums;
- `kernel.config` and `busybox.config` — final build configurations;
- `BUILD-INPUTS`, `GPL-SOURCES.md`, `SOURCE-OFFER.txt`, GPLv2 text,
  e2fsprogs notices/attributions, and `LICENSE.musl` — provenance and
  corresponding-source records.

The full acceptance command is:

```bash
./guest-assets/run-in-builder.sh verify
```

It runs JVM/process tests, shell static checks, two clean builds with byte-for-
byte comparison, one final build, and the QEMU AArch64 Provisioning smoke.
`build`, `test`, `negative-config`, `reproducibility`, and `smoke` may also be
selected separately.

`run-in-builder.sh` deliberately supports only Docker-compatible Docker and
Podman CLIs. It does not treat `vctl` as Docker-compatible. On an already
prepared Ubuntu 24.04 Linux builder, the equivalent direct entry is
`guest-assets/ci/run.sh`; dependencies are installed by
`guest-assets/ci/install-builder-dependencies.sh`.

## Pinned and verified inputs

`inputs/inputs.lock` is the machine-readable source of truth. Network archives
are downloaded to a partial path, SHA256-verified, and only then renamed and
unpacked. The Gradle distribution has its checksum in the root wrapper, Maven
artifacts are covered by Gradle dependency verification, APT verifies signed
Release metadata plus index/package hashes, and the container runtime verifies
the builder's immutable image digest.

The exact kernel, BusyBox, musl, e2fsprogs and official Alpine minirootfs URLs and
checksums are recorded in the lock. Changing any one is an explicit source
update, not an automatic upstream follow.

The final kernel configuration is produced by `defconfig`, merged with
`kernel/droidlayer.fragment`, resolved by `olddefconfig`, then checked against
every entry of `kernel/required.config`. A mismatch lists every bad symbol and
terminates before `Image` is built. The controlled negative-build harness adds
a temporary final fragment that really sets `CONFIG_EXT4_FS=n` and verifies
that the complete build stops at this gate:

```bash
./guest-assets/run-in-builder.sh negative-config
```

The harness succeeds only after observing the inner build exit with status `1`,
the exact diagnostic `CONFIG_EXT4_FS: expected y but was n`, and no generated
`Image`.

## Provisioning and token boundary

The official Alpine tarball is not embedded in the reproducible initramfs. For
the smoke seam, the host exposes the already SHA256-verified tarball as a
read-only 9p input. The initramfs carries only its pinned file name and expected
digest.

At each smoke run, the host generates a fresh 32-byte token from `/dev/urandom`
and exposes its `0600` file through the same read-only input. The initramfs
accepts exactly 64 lower-case hexadecimal characters, formats the empty root
volume as ext4, extracts the official tarball, and writes the token to
`/etc/droidlayer/bridge.token` with mode `0600`. No token is generated at build
time or stored in `Image`, initramfs, manifests, logs, or tracked production
files.

Provisioning installs the static guest agent and an Alpine OpenRC service
template but leaves the service disabled. Ticket 26 owns the actual transport
adapter and byte stream. The QEMU smoke passes `droidlayer.poweroff=1`, so init
powers down immediately after Provisioning instead of continuing to boot. It
then waits for the console marker `DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine phase=rootfs runtime=pending`
and uses `debugfs` to verify Alpine, ext4 features, token equality and mode,
the installed agent, and the completion marker. The `distro` field is the `ID`
value from the guest `/etc/os-release` (`alpine`), not the versioned archive
name; host-side completion detection only requires the
`DROIDLAYER_PROVISIONING_COMPLETE version=1` prefix. The secret-bearing temporary
tree and root volume are removed by a trap on success or failure.

The pinned Alpine minirootfs was checksum-verified and inspected: it has no
`/sbin/openrc-run`, and `/sbin/init` points to BusyBox. Ticket 25 therefore
delivers only a disabled OpenRC-compatible service scaffold plus the agent
binary. It does not claim that this minirootfs is an OpenRC-bootable second-stage
rootfs. Pinning/installing the required init packages, selecting transport, and
lifecycle belong to Ticket 26.

## Guest-side handshake

`agent/guest-agent.c` is a transport-independent process-I/O baseline. It reads
only the file passed through `--token-file`; there is no default token. Its v1
request is one ASCII line:

```text
DROIDLAYER/HELLO <minimum-version> <maximum-version> <64-hex-token>
```

If v1 is within the range and the constant-work token comparison succeeds, the
agent writes `DROIDLAYER/READY 1`. It otherwise returns stable malformed,
unauthorized, unsupported-version, or token-file diagnostics. It implements no
host transport, terminal path, `ping`, or other bridge command.

## Local environment note

The repository may be checked out on Windows, but the real kernel/initramfs and
QEMU acceptance requires the pinned Linux builder. JVM and host-process tests
can use the root Gradle wrapper; for a non-ASCII checkout, use the ASCII `subst`
workaround documented in `docs/development/android-bootstrap.md`. A local test
or syntax check is not evidence that the ARM64 build or QEMU smoke passed.
