# QEMU GPL Sources — DroidLayer Ticket 26 (2026-09-25)

This record describes the GPL object code shipped inside the DroidLayer APK
(`lib/arm64-v8a/libdroidlayer_qemu.so`, the QEMU 11.1.1 `qemu-system-aarch64`
main ELF packaged with a `.so` name) and every statically linked bundled
dependency, together with the complete corresponding-source path. It satisfies
the engineering delivery floor of `docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md`
§6 and anticipates the public-release obligations of
`docs/agent-handoff/GPL_RELEASE_DELIVERY_POLICY.md` (no public release is
authorized yet; the public tag + source-bundle delivery happens at release time
by the named Release Maintainer).

## 1. QEMU (GPLv2)

- Source URL: https://download.qemu.org/qemu-11.1.1.tar.xz
- Version: 11.1.1, only target `aarch64-softmmu`
- Git identity: tag `v11.1.1`, commit `c3d48b7d1e89604920e5b81b91140c2ad39a1943`
  (release/source-correspondence record; the release archive is the build source)
- Archive SHA-256: `079ffbff8a7111bbc89022107cbabf3bbfd614d5fc9d7cc675991196aca12482`
- Detached signature: https://download.qemu.org/qemu-11.1.1.tar.xz.sig,
  verified (GOODSIG/VALIDSIG) against signer fingerprint
  `CEACC9E15534EBABB82D3FA03353C9CEF108B584` (evidence
  `R/evidence/qemu-signature-verify-20260923.log`)
- Patches: **none** (`patches=none` in the lock; the only archive-level
  adjustment is the extraction-time exclusion of the single absolute symlink
  `qemu-11.1.1/roms/edk2/EmulatorPkg/Unix/Host/X11IncludeHack -> /opt/X11/include`,
  which is not used by `aarch64-softmmu`; staging sorted-set hash
  `d88022838dff92af042fca48432f88b54c93ca355c664f2cc4e3e7aa09ebd289`)
- Configure arguments: `--target-list=aarch64-softmmu --disable-docs --disable-download --disable-vhost-user --disable-plugins --disable-virtfs --cross-prefix=aarch64-linux-android26- --cpu=aarch64 --prefix=/usr`
  (recorded config fixes: vhost-user/plugins/virtfs disabled for bionic API 26
  compatibility and ticket scope; see QEMU-BUILD-INPUTS)
- Build environment: SOURCE_DATE_EPOCH=0, PYTHONHASHSEED=0,
  build_network_policy=fetch-inputs-only, no build-phase network fetches
- Builder identity: `mcr.microsoft.com/devcontainers/base@sha256:3612b800eaae8513e7dca54f21d2e4eda48eda57426f3746a78677f342ac7ee9`
  (linux/amd64), Ubuntu snapshot `https://snapshot.ubuntu.com/ubuntu/20251017T000000Z/`
- NDK identity: r28c, revision `28.2.13676358`,
  metadata SHA-1 `a7b54a5de87fecd125a17d54f73c446199e72a64`,
  archive SHA-256 `dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f`
- Build entrypoint: `R/evidence/c-android-incontainer-20260925.sh`
  (SHA-256 `f4b27d2289c642de6805fede1814dd2063d1e07e33fdbb74d0f6cf7b9337c338`),
  run under the immutable builder via `R/evidence/run-c-builder-android-20260925.sh`
  with the Q1–Q5 builder discipline (source read-only mount, separated io/cache,
  waiver assertions before and after every container run)
- Reproducibility: two clean builds produced byte-identical products
  (`two-clean-build-diff-053629-20260925.txt` is empty)
- Shipped binary hashes:
  - `libdroidlayer_qemu.so` (qemu-system-aarch64): `525469bdd64a565e7e52536915321f6ca307a976599f1e6ba72d2a6433573e3a` (132,889,376 bytes)
  - qemu-img: `bc3b2b866f58fb30d8dc0249c5f6880de9284456d4ecc969a735033451857296` (build evidence; not shipped in the APK)
  - qemu-io: `5762730040a5a366d7ff724e1dacdcf23a450a2107829e767ac0842af9cc725b` (build evidence; not shipped in the APK)

## 2. Statically linked bundled dependencies (shipped inside the ELF)

| Dependency | Version | License | Source (frozen, hash-pinned in `tools/qemu-android/qemu-jni.lock`) |
| --- | --- | --- | --- |
| glib | 2.80.5 | LGPL-2.1-or-later (GPL-2+ tools parts) | `https://download.gnome.org/sources/glib/2.80/glib-2.80.5.tar.xz`, SHA-256 `9f23a9de…e6771` |
| pixman | 0.42.2 | MIT | `https://cairographics.org/releases/pixman-0.42.2.tar.gz`, SHA-512 sidecar verified, local SHA-256 `ea1480ef…4a625e` |
| zlib | 1.3.2 | Zlib | `https://zlib.net/zlib-1.3.2.tar.gz`, SHA-256 `bb329a0a…19d16` |
| pcre2 | 10.48 | BSD-3-Clause | `https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.48/pcre2-10.48.tar.gz`, SHA-256 `ebcc25aa…d46888` |
| libffi | 3.8.0 | MIT | `https://github.com/libffi/libffi/releases/download/v3.8.0/libffi-3.8.0.tar.gz`, SHA-256 `7da3e2d9…c0db4` |
| libfdt (dtc) | rev `b6910bec11614980a21e46fbccc35934b671bd81` | GPL-2+ OR BSD-2-Clause (dual) | `https://gitlab.com/qemu-project/dtc.git` frozen archive SHA-256 `1df504e7…1a29a`, cross-built static PIC into the build prefix |
| libiconv | 1.19 | LGPL-2.1 (library) + GPL-3 (tools) | `https://ftp.gnu.org/gnu/libiconv/libiconv-1.19.tar.gz`, SHA-256 `88dd96a8…19aa6` (no official checksum published; `official_checksum=absent` exception recorded, `.sig` archived unverified) |
| proxy-libintl | tag `0.4` = commit `c03e1a74b17fa7ec467e110130775409e4828a4c` | LGPL-2.1-or-later (licensee election exercised from LGPL-2.0-or-later; election record `R/evidence/proxy-libintl-license-election-20260924.md`) | `https://github.com/frida/proxy-libintl.git`, git archive SHA-256 `55b5d8f9…264a` |
| DroidLayer shm stub | self-owned | Apache-2.0 (project LICENSE) | `tools/qemu-android/android-shim/shm_stub.c` SHA-256 `5db52032…50d75` + `shm_stub.h` `37854061…de15f`; ENOSYS link shim per the 2026-09-25 user ruling (option 1) |

All frozen upstream source archives above are retained under
`R/downloads/…` with per-file SHA-256 fetch logs; license ledgers and
per-source determinations live under `R/manifests/` and `R/evidence/`.

## 3. Build-side tools (not shipped, recorded for provenance)

Ubuntu snapshot packages: make 4.3-4.1build2, gcc 4:13.2.0-7ubuntu1,
libc6-dev 2.39-0ubuntu8.6, ninja-build 1.11.1-2, python3 3.12.3-0ubuntu2,
python3-venv/pip/setuptools/wheel/pkgconf/python3-packaging (pinned versions in
the lock); vendored meson wheel `qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl`
(SHA-256 `9b3a0236…e0d80`). These run in the builder container and are not
distributed in the APK.

## 4. Guest assets shipped in the APK (Ticket 25 provenance)

- `Image` (Linux kernel, GPL-2.0): SHA-256 `d56677f4a2801ab9a9faa68be448d8ca92de5b1058c68b1e132377d810435dc5`
- `initramfs.cpio.gz`: SHA-256 `c96fe6e6d95b097b7b7509a669b8aaf19acf73de4541d4b4e069795213540361`
  (quality / Phase 5 integration, 2026-10-14). The input bundle was
  `0caf5f9a6e2334ce3608fb745b69983b2328b15fcee0b31b9a2eca5685636fba`.
  `guest-assets/scripts/repack-guest-agent.py` replaced only the unique
  `opt/droidlayer/guest-agent`, preserving the other 428 entries and modes.
  The integrated agent SHA-256 is
  `6c6470872f2bc45c56729bb4d9848f0f0b5e559f79dc8b3e64e3ba4c643893e1`
  (477400 bytes), built from `guest-assets/agent/guest-agent.c`, source SHA-256
  `f1250967916612da9680526a51fa99973c711b21ed6b4044cbd2468dff1c5fb5`.
  The producer is `tools/phase5/build-guest-agent.py`; its complete consumed
  header/link/tool inputs are pinned in `tools/phase5/guest-agent-inputs.lock.json`.
  This ELF targets Linux AArch64 with musl and compiler-rt, has no interpreter
  or dynamic segment, and declares a non-executable stack. Matching source
  and licenses are recorded in `NOTICE.GUEST-RUNTIME`. The separate
  `guest_init` resource is regenerated from the canonical runtime, provision,
  shell, profile and display sources by `sync-guest-init.py`; it is validated
  independently from the unchanged non-agent bundle entries.
- Provenance, licenses, and corresponding-source records: the Ticket 25 asset
  root `SHA256SUMS` (12/12 verified before every copy), its `GPL-SOURCES.md`,
  `SOURCE-OFFER.txt`, and `BUILD-INPUTS` records. These are inputs to this APK;
  they are not relabeled as QEMU evidence.

## 5. Corresponding source offer

The complete corresponding source for every GPL work listed above consists of
the pinned upstream archives (§1–§2), the DroidLayer build entrypoint and
cross-build scripts (§1), the lock `tools/qemu-android/qemu-jni.lock`, and this
record; all are retained and verifiable by SHA-256. At the time of a public
binary release, the public corresponding-source channel (immutable release tag
plus matching source bundle, per `GPL_RELEASE_DELIVERY_POLICY.md` §2–§4) is
created by the named Release Maintainer before the release gate opens; this
record does not substitute for that public delivery.
