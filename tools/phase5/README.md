# Quality / Phase 5 runtime production and validation

The integration uses one Linux AArch64 musl guest agent for HELLO, POWER, FEATURES1 and DISPLAY. Large public kernel/QEMU/gvproxy inputs, the base initramfs, the generated runtime bundle and APK remain outside Git. `runtime-build-inputs.lock.json` records their exact sizes, SHA-256 digests, owning targets, frozen input references and producers. It distinguishes the gvproxy source ELF from its AGP-stripped APK bytes. The source worktrees and prior artifacts are preserved.

## Public input preparation

Use the named public inputs in `Orchestella/artifacts/quality-phase5-merge-handoff/quality-main/`, restored to the owning targets in `runtime-build-inputs.lock.json`. Verify each input's size and SHA before use. The kernel/QEMU provenance and existing corresponding-source build records are in `tools/qemu-android/qemu-jni.lock`, `tools/qemu-android/ci/guest-assets-inputs.lock`, `tools/qemu-android/gpl/` and the included QEMU build scripts. This integration does not publish a binary release or create a new public source bundle.

The musl/UAPI packages have fixed official URLs and archive/member hashes in `guest-agent-inputs.lock.json`. Reuse the verified extracted sysroot, or reconstruct it from those exact packages without substituting Android Bionic. The NDK revision is 27.0.12077973. Its LLVM root and the musl sysroot are explicit producer arguments. The producer verifies all 39 actually consumed headers, musl CRT/libc and compiler/linker/builtins inputs before and after compilation. A missing consumed header lock entry fails before binary output.

## Generate the runtime

Commands below use illustrative ASCII paths; supply your checked project and external output roots. Temporary output must stay in the approved external resource root.

```text
python guest-assets/scripts/sync-guest-init.py
python guest-assets/scripts/sync-guest-init.py --check
python tools/phase5/build-guest-agent.py --ndk-llvm <NDK-LLVM-root> --musl-sysroot <musl-sysroot> --input-lock tools/phase5/guest-agent-inputs.lock.json --output <external>/guest-agent-aarch64-musl --evidence <external>/agent-evidence --temporary <external>/temp
python guest-assets/scripts/repack-guest-agent.py --bundle app/src/main/resources/initramfs.cpio.gz --manifest app/src/main/assets/runtime-artifacts-v1.sha256 --agent <external>/guest-agent-aarch64-musl --agent-sha256 <verified-build-agent-sha> --source guest-assets/agent/guest-agent.c --source-sha256 <verified-source-sha> --evidence <external>/repack-evidence
```

The base bundle must match its manifest before repack. Repack replaces exactly one agent and preserves the other 428 entries and modes. Use the actual build record SHA values; do not bypass the producer pins. The generated `guest_init` resource and seven optional payloads must match their final canonical sources. Keep `NOTICE.GUEST-RUNTIME`, `NOTICE.PHASE5`, QEMU corresponding-source records and the two desugar license assets alongside the runtime manifest.

## Android build

Use JDK17, SDK36/build-tools36.1.0, NDK27 and Gradle8.13. Windows project, project-cache, build/output and temporary paths require verified ASCII aliases. Reuse the existing dependency cache read-only; keep this invocation's writable Gradle/cache/output separate. Set `sdk.dir` with forward slashes. Run offline after resolving only missing fixed dependencies at the repositories already declared by the project.

The normal debug application ID is `io.github.nyzeep.orchestella`, versionCode300 and versionName `0.3.0-preview.1`. The `.phase5` suffix is enabled only by the explicit `orchestella.phase5.debugKeystore` test property. Do not read or copy release signing material to build a candidate. A Debug-signed main application cannot replace a release-signed installed application while retaining its data; an actual matching signer or a separately authorized test variant is required for that device test.

Run the complete `:app:testDebugUnitTest`, all applicable runtime `:check` tasks, `:app:lintDebug`, native ARM64 build and `:app:assembleDebug`. The `runPhase5JvmAppTests` task is only a supplement, not the complete App union. Extract and check the final APK:

```text
python tools/phase5/verify-apk.py --apk <candidate.apk> --agent <external>/guest-agent-aarch64-musl --source-root <project> --output <external>/apk-content-verification.json
```

## Host process validation

`run-posix-control-tests.py` rebuilds host-test agents from the current C source using explicit NDK/musl roots. Its test helper/TCP/POWER macros are never passed to the production AArch64 producer. Use its `--help` for the explicit source, toolchain, output-root and WSL arguments; `--quality-tests` includes the HELLO and compile-time POWER mock regression families.

Run the entire Linux `guest-assets/scripts/test-phase5-features.py --output <external>` suite, Linux display relay tests and standard read-only 9p wire/process mains. The guest baseline scripts use `GUEST_RUNTIME_TEST_ROOT` and synthetic fixtures; they do not resume the cancelled seven-distribution device queue. Never equate JVM/WSL fixtures, package acceptance, host GLES or APK content checks with Android/ARM64 guest readiness, GPU or zero-copy validation.

The canonical implementation, verification and independent review reports are under `docs/reports/orchestration/quality-phase5-integration*.zh-CN.md` and `docs/reports/reviews/quality-phase5-integration/`.
