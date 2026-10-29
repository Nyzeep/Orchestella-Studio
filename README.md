# Orchestella Studio

<div align="center">

<img src="docs/assets/branding/app-icon-512.png" width="128" alt="Orchestella Studio app icon" />

**A Linux VM and mobile workstation for Android — no root required**<br />
*Native Linux VM runtime & mobile workstation, sandboxed inside an ordinary Android app*

[![Release](https://img.shields.io/badge/Release-v0.6.0%20stable--prep-blueviolet.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg?style=flat-square)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg?style=flat-square)](#)
[![CI](https://img.shields.io/github/actions/workflow/status/Nyzeep/Orchestella-Studio/ci.yml?branch=main&label=CI&style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

[简体中文](README.zh-CN.md) · **English**

</div>

---

## What this is

Orchestella Studio runs Linux on 64-bit Android devices without root, without touching system partitions, and without unlocking the bootloader. Behind one terminal, network stack, and instance manager sit four execution lanes with genuinely different boundaries: only the QEMU lane delivers the full "kernel + systemd + seven distributions" contract. The other three have narrow, explicit ranges.

Every performance figure, status label, and license statement below points at a file in this repository. Where the repository has no measurement, the text says **not measured** or **unverified** instead of estimating.

## Development preview notice

> **0.6.0 is a "stable-release preparation" milestone, not a claim that the product is fully stable.** This round settles the two-package release shape and turns the four-lane positioning and measurement basis into something checkable. It still carries unverified scope, and some lanes have no product entry point yet.

What remains true after this round:

- **Real AVF guest success samples are still 0.** The current test device declares `feature:android.software.virtualization_framework`, but no `virtualizationservice` is registered in the service list; the accelerator lane is rejected at the decision layer and falls back to QEMU.
- **pVM has not been re-measured on-device in the targetSdk 36 domain**; **UML has only reached "boots and compiles a single file"**; **16 KB page execution on a real device is unverified**.
- **Production signing and instance-data migration are still release gates.** No signing key is stored in this repository.

Public artifacts are what the [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) page lists. Merging into `main` is not a release, and it does not mean every ticket is closed.

## The four lanes

| Lane | Mechanism, in one line | Typical role | Measured per-file compile time | Status |
| :--- | :--- | :--- | :--- | :--- |
| **proot / bare** | Guest binaries run as **native** host ARM64 instructions; `ptrace` intercepts and rewrites syscalls. There is no guest kernel. | Lightweight shell, package management, and compatibility work on low-memory devices | bare **313 ms** (p50 of 7 files)<br />proot **337 ms** (p50) | **Conditional** — verified in the 28 domain; unavailable in the 36 domain |
| **pVM** (protected Microdroid payload) | A Microdroid GKI kernel accepted by the platform chain of trust runs on an EL2 **hardware vCPU** | Offloading distcc compiles into an isolated VM | **2,809 ms** (single end-to-end job, n=1, reused run) | **Conditional** — the 28-domain chain is verified; in the 36 domain it is blocked by a missing AVF service on this device (not a packaging or permission issue); no on-device re-measurement |
| **QEMU** (TCG) | Guest instructions are **translated** in software before execution | Full kernel, systemd, SSH, seven upstream distributions | **32,750 ms** (single full compile including preprocessing, n=1, reused run) | **Conditional** — the targetSdk 36 exec block is empirically cleared and guest networking is verified; package installation is blocked by the guest kernel lacking Landlock (fixable); compiling inside the guest is unverified |
| **UML** (`ARCH=um`) | A real Linux kernel runs as an ordinary app process; guest instructions execute **natively** and binaries are loaded by the guest kernel's ELF loader | Research range: long-running user space / containers / Docker | **940 ms** (median on a 2,000-function fixture; 460 ms native in the same round, i.e. **2.04×**) | **Unverified** — boot and single-file `-c` compiles are verified; long-running, linking, and Docker are not |

### How to read those numbers

Every figure traces to §1.1 and §1.4 of [`docs/research/ACR-draft-four-lanes-20261029.md`](docs/research/ACR-draft-four-lanes-20261029.md). Nothing here is rounded or rephrased.

- **313 / 337 / 2,809 / 32,750 ms come from the same round (session 16), with a 92 KB `.i` fixture.** The QEMU column is a single full compile *including preprocessing*; the pVM column is a single *end-to-end distcc job*. Neither is equivalent to the per-file basis used for bare and proot.
- **940 / 460 ms come from a different round (session 22), with a 90,045 B `.c` fixture and `clang -c -O1`, on a different toolchain** (Alpine clang 22.1.3 musl static vs payload clang 22.1.8).
- **Figures from different rounds are not comparable.** The 2.04× ratio only pairs with the 460 ms measured in the same round; it must not be divided by 313 ms or 2,809 ms. Any claim that "UML is N times faster or slower than lane X" currently lacks same-round, same-fixture data — **not measured**.
- Four lanes measured in one round on one fixture, a `dockerd` result for the QEMU lane, and any long-running, linking, or Docker number for UML are all **not measured**.

### Terminology discipline

Product copy describes execution strategy in exactly three terms, never as a vague "speed-up":

| Term | Definition | Lanes |
| :--- | :--- | :--- |
| **Native instruction execution** | The guest binary *is* host CPU code; there is no translation layer, but also no EL2 boundary, so syscalls may be intercepted by `ptrace` or land in a guest kernel | proot / bare, UML |
| **Hardware vCPU** | A virtual CPU provided by an EL2 hypervisor; the guest kernel executes on it | pVM |
| **Translated execution (TCG)** | Instructions are translated in software one at a time before they run | QEMU |

The distinction is not "who executes the instruction" but "whose CPU the guest kernel runs on, and whether an EL2 boundary exists". Multiplier figures for pVM that circulated in earlier versions were invalidated in later sessions (see the note under §1.2 of the ACR draft); this project no longer quotes any ratio assembled across rounds.

## Installation

### Two packages

Since 0.6.0 the app ships as two packages:

- **Main package**, `io.github.nyzeep.orchestella` — the app itself plus the full QEMU kernel path.
- **pVM accelerator extension**, `io.github.nyzeep.orchestella.accel` — the pVM payload, the compiler engine, and `PVM-COMPILER-MANIFEST`. The main package carries no pVM assets.

Two hard rules:

1. **The extension's `versionCode` must equal the main package's.** On a mismatch the runtime refuses the extension outright and says so; it does not attempt a best-effort load, because a mismatched extension describes a payload this build was never verified against (`app/src/main/java/io/github/nyzeep/orchestella/AccelExtensionRules.kt:13`, `:71`).
2. **The main package alone means the QEMU lane only.** Full kernel, systemd, SSH, and the distribution catalog all work; pVM entry points report that the accelerator extension is not installed.

When upgrading, move both packages to the same version. Do not upgrade just one.

### HyperOS / Xiaomi install quirks

- **New installs require on-screen confirmation.** Over ADB with the screen locked the install fails with `INSTALL_FAILED_USER_RESTRICTED`. Unlock the device and keep the screen on before installing.
- **Updates to an already installed package are not restricted.** So do not uninstall first to get a "clean install" — uninstalling takes the instances stored in the app's private directory with it.
- `settings put global adb_install_need_confirm 0` does not work on HyperOS; the real gate is the USB-install authorization plus a lit screen.

### Granting the pVM permission

```bash
adb shell pm grant io.github.nyzeep.orchestella android.permission.MANAGE_VIRTUAL_MACHINE
```

A successful grant (`granted=true`) **does not** make the pVM lane usable: the device must also register `virtualizationservice`. The current test device does not, so it still falls back to QEMU — what is missing is the service, not the permission.

### Shizuku

The app uses a regular Shizuku client so it can distinguish connected, denied, process-died, reconnected, and timed-out states. **Client authorization does not mean an AVF guest is running**, and it does not mean the device has a usable AVF service.

## Building from source

Shortest working path (JDK 17, Android SDK Platform 36, NDK 27.0.12077973, CMake 3.22+):

```bash
git clone https://github.com/Nyzeep/Orchestella-Studio.git
cd Orchestella-Studio
./gradlew :app:assembleDebug
```

The build channel is a Gradle property, defaulting to `release`:

```bash
# Internal development channel: .dev applicationId suffix, -dev.<shortSha> versionName
./gradlew :app:assembleDebug -Porchestella.buildChannel=dev

# Isolated QA channel: additionally requires buildPurpose (lowercase letters and digits only)
./gradlew :app:assembleDebug -Porchestella.buildChannel=qa -Porchestella.buildPurpose=input
```

- Internal channels use a different `applicationId`, so they coexist with a release install instead of overwriting user data, and they are always debug-signed.
- A release build with any signing property missing fails in `validateReleaseSigning`; it cannot silently produce something that looks like a production package.
- **Payload and extension properties are internal build inputs.** `:accel-extension` has no payload-less variant; building it requires pre-staged input directories (`-Porchestella.fastlane.payloadLibDir`, `-Porchestella.pvmCompiler.libDir`). These are not installation steps, and the repository ships none of those binaries.
- CI's Gradle gate runs every `:runtime:*:check` plus `:app:testDebugUnitTest`; separate jobs cover lint + assemble + the 16 KB packaging check, a dual-package size comparison that builds `:accel-extension` against a labelled fixture (placeholder `.so` files), and the Python harness checks.

## Roadmap

1. **0.6.0 stable (this round)** — two-package release shape, four-lane matrix, and measurement basis finalised; close production signing, instance-data migration, and the release manifest.
2. **targetSdk 36 pVM end to end (next device round)** — the platform-side exec block is empirically cleared in the 36 domain; the extension's package-visibility declaration is fixed in 0.6.0 sources (`<queries>` now injects `<applicationId>.accel` per channel). What remains is the extension install strategy, confirming that `Resolver` flips to "installed" once the APK is on the device, and a `COMPLETED` distcc run in the 36 domain.
3. **UML research line (long-running / containers / Docker)** — the compile role **has been empirically rejected**: on the 2,000-function fixture UML measures 940 ms median against 460 ms native, i.e. **2.04×**, which lands in the 1.5–3× band and misses the `≤1.5×` criterion for compiling. The lane's value range moves to long-running user space and container workloads, but 30-minute stability, the link stage, and Docker are all still undone.
4. **AVF custom kernel (evaluate, do not implement)** — only meaningful on non-protected devices: pvmfw starts Google/OEM-signed images only (`Available OS list: ["microdroid"]`), so protected devices cannot run a custom kernel. This round is a feasibility assessment with no implementation commitment.

## Known limitations

- **The proot lane requires the 28-domain package.** It exists only in the `-Porchestella.qaTargetSdk=28` QA/sideload channel. In the 36 domain only `.so` files under `nativeLibraryDir` can be executed, so distribution binaries cannot be exec'd from a writable directory.
- **Real AVF guest success samples are still 0** (see [AGENTS.md](AGENTS.md) and the [documentation index](docs/README.md)).
- **The extension package-visibility fix is not yet verified on a device.** 0.6.0 declares `<applicationId>.accel` (QA variants: `…qa.<purpose>.accel`) in the `<queries>` block, injected per channel; without it, package-visibility filtering at targetSdk ≥ 30 makes `AccelExtensionResolver` report an installed extension as missing. The fix ships with a source-contract unit test; the on-device verdict is deferred to the next device round.
- **UML is research only.** Everything beyond `-c` (invoking `ld`/`lld`), plus systemd, Docker, SMP, and 16K-host-with-4K-guest, is unverified.
- **16 KB pages.** ELF and APK alignment are verified; execution on a 16 KB device is untested.
- **Signing.** No key is committed. v0.4.5 and the 0.5.3 verification builds were both Android Debug certificates (`docs/standards/VERSIONING.md` §4). Treat 0.6.0's certificate as unsigned-off until the release page and release manifest record it.

## License and acknowledgements

The project as a whole is **GNU General Public License v3.0 or later (GPL-3.0-or-later)**; the full text is in [LICENSE](LICENSE).

| Component | License | Form and notes |
| :--- | :--- | :--- |
| QEMU 11.1.1 and statically linked libraries | GPL-2.0 (QEMU); glib LGPL-2.1+, pixman MIT, zlib, pcre2 BSD-3-Clause, libffi MIT, libfdt GPL-2.0+/BSD-2-Clause dual, libiconv LGPL-2.1, proxy-libintl LGPL-2.1+ | Shipped in the APK; corresponding-source record in `app/src/main/assets/QEMU-GPL-SOURCES.md` and `tools/qemu-android/gpl/` ([NOTICE.QEMU](app/src/main/assets/NOTICE.QEMU)) |
| Guest control agent | GPL-3.0-or-later (project code), statically linked against musl 1.2.6-r2 | Shipped; see [NOTICE.GUEST-RUNTIME](app/src/main/assets/NOTICE.GUEST-RUNTIME) |
| BusyBox 1.36.1-r31 (Alpine 3.20 `busybox-static`) | GPL-2.0-only | Shipped inside the Fast Lane minimal toolbox; requires corresponding source or a three-year written offer at the same distribution point ([NOTICE-fastlane.md](NOTICE-fastlane.md)) |
| distcc 3.4 (`distccd`) | GPL-2.0-or-later | **Not shipped in the APK**; runs as a separate process, so the GPL boundary sits at the process boundary ([NOTICE-fastlane.md](NOTICE-fastlane.md)) |
| gvproxy / gvisor-tap-vsock | Apache-2.0 | User-space network layer |
| clang / LLVM | Apache-2.0 WITH LLVM-exception | Not shipped, no pinned binary |
| PRoot | GPL-2.0 | Implementation reference for the compatibility class; not shipped in current packages |
| UML kernel (downstream `zalexdev/linux-um-arm64`, base 7.2-rc4) | GPL-2.0 | The binary is excluded by `.gitignore`, is not in the repository, and is not distributed with 0.6.0. Any distribution must ship corresponding source separately under GPL-2.0; the delivery boundary is still undecided |

Guest root filesystems for each distribution follow that distribution's upstream license. License consistency is checked by `tools/release/check_licenses.py`, and a release bundle must contain every file listed in `tools/release/release_bundle_manifest.json`.

## Previous versions

v0.4.5 is the last publicly released development preview (Android Debug certificate). Its notes and packages remain archived on the Releases page. It is no longer the primary download path: 0.6.0's defining changes — the two-package shape and the four-lane matrix — do not exist in v0.4.5, and mixing installation instructions across the two generations produces wrong conclusions.

## Documentation and feedback

- [Documentation index (Chinese)](docs/README.md): current status, research basis, tickets, and archives.
- [Four-lane decision material (ACR draft)](docs/research/ACR-draft-four-lanes-20261029.md): the authoritative source for every performance figure and four-state label in this README.
- [Versioning and build-channel standard](docs/standards/VERSIONING.md): hard constraints on version names, `versionCode`, and the three channels.
- Feedback: [open an issue](https://github.com/Nyzeep/Orchestella-Studio/issues/new) or use [Discussions](https://github.com/Nyzeep/Orchestella-Studio/discussions). Strip passwords, tokens, SSH private keys, and personal file contents first, and include app version, device model, Android version, distribution, and reproduction steps.
