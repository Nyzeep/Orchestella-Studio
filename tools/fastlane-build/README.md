# Fast Lane zero-device build tools (P11)

Mostly stdlib-only Python 3 helpers for the sealed toolbox, the APK `bin/` gate
and the license/SBOM gate. The workflow policy gate uses PyYAML, a CI-only
validation dependency; it is not shipped in the APK. These tools never call
adb, never start a device and never sign or publish an APK.

## Commands

```bash
# Host tests (no network, fixture toolbox; PyYAML adds the workflow tests)
python3 -m unittest discover -s tools/fastlane-build -p 'test_*.py' -v

# Real pinned toolbox (downloads the Alpine package once)
python3 tools/fastlane-build/fastlane_build.py build \
  --manifest fastlane-toolbox/manifest.json \
  --out build/fastlane-toolbox --cache build/fastlane-cache
python3 tools/fastlane-build/fastlane_build.py verify-toolbox --toolbox build/fastlane-toolbox

# Build twice and compare the canonical tree digest
python3 tools/fastlane-build/reproduce.py \
  --manifest fastlane-toolbox/manifest.json --cache build/fastlane-cache --runs 2

# Synthetic APK helpers (tests/fallback only)
python3 tools/fastlane-build/fastlane_build.py inject-apk \
  --apk-in unsigned.apk --toolbox build/fastlane-toolbox --apk-out injected.apk
python3 tools/fastlane-build/fastlane_build.py verify-apk \
  --apk injected.apk --runtime-manifest build/fastlane-toolbox/bin/manifest.json \
  --zipalign "$ANDROID_HOME/build-tools/36.1.0/zipalign"

# Deliberate-failure self-test and license gate
python3 tools/fastlane-build/prove_gate_blocks.py
python3 tools/fastlane-build/fastlane_build.py check-licenses

# Workflow policy gate (CI installs PyYAML 6.0.2)
python3 tools/fastlane-build/check_zero_device_workflow.py

# Payload build (requires P02's fastlane-payload/CMakeLists.txt)
python3 tools/fastlane-build/prove_payload_fixture.py
bash tools/fastlane-build/build-payload.sh
```

`SOURCE_DATE_EPOCH` defaults to `1792540800` (2026-10-21T00:00:00Z). The
runtime manifest records it; file mtimes and injected ZIP timestamps use it, so
the same manifest and upstream package produce the same bytes.

## Production APK path

`app/build.gradle.kts` adds `-Porchestella.fastlane.toolboxDir=<dir>` as a Java
resource source set when the property is present. AGP packages `bin/` at the
APK root, then zipaligns and signs. This is deliberately before signing: the
synthetic `inject-apk` command refuses an APK that already has a v1/v2
signature, because rewriting a signed APK invalidates its signature.

`verify-apk` checks:

- every manifest entry is present, byte-exact and SHA256-exact;
- the APK has no duplicate names, absolute paths, `..` segments, directory
  entries or symlink entries under `bin/`;
- exec bits are set when the zip records a mode; a zero mode is reported as
  `zipfuseDefaultMode` because zipfuse grants `bin/` executability;
- every ELF under `bin/` is AArch64, statically linked and has all PT_LOAD
  `p_align >= 16384`;
- total `bin/` size, including `bin/manifest.json`, is within the 80 MiB
  starting budget;
- the embedded manifest equals the expected runtime manifest;
- `zipalign -c -P 16 -v 4` passes when a build-tools `zipalign` is supplied.

## Failure semantics

All gates exit non-zero on failure. `prove_gate_blocks.py` tampers with a
synthetic APK, attempts injection into a signed APK, exceeds the size budget,
removes an entry, injects a traversal path, corrupts a toolbox SHA, and removes
a license field; it asserts that each attempt is rejected. CI runs it on every
change.

`reproduce.py` builds the toolbox twice and compares a canonical digest over
path, mode, size and content; a mismatch lists the differing files and exits
non-zero.

The payload script exits `2` with `BLOCKED` when `fastlane-payload/` is absent.
`prove_payload_fixture.py` proves the CMake/symbol/BLOCKED flow with a host
fixture; it is not an Android artifact and does not replace the NDK build.
