# Fast Lane zero-device build tools (P11)

Stdlib-only Python 3 helpers for the sealed toolbox, the APK `bin/` gate and
the license/SBOM gate. They never call adb, never start a device and never
sign or publish an APK.

## Commands

```bash
# Host tests (no network, fixture toolbox)
python3 -m unittest discover -s tools/fastlane-build -p 'test_*.py' -v

# Real pinned toolbox (downloads the Alpine package once)
python3 tools/fastlane-build/fastlane_build.py build \
  --manifest fastlane-toolbox/manifest.json \
  --out build/fastlane-toolbox --cache build/fastlane-cache
python3 tools/fastlane-build/fastlane_build.py verify-toolbox --toolbox build/fastlane-toolbox

# Synthetic APK helpers (tests/fallback only)
python3 tools/fastlane-build/fastlane_build.py inject-apk \
  --apk-in unsigned.apk --toolbox build/fastlane-toolbox --apk-out injected.apk
python3 tools/fastlane-build/fastlane_build.py verify-apk \
  --apk injected.apk --runtime-manifest build/fastlane-toolbox/bin/manifest.json \
  --zipalign "$ANDROID_HOME/build-tools/36.1.0/zipalign"

# Deliberate-failure self-test and license gate
python3 tools/fastlane-build/prove_gate_blocks.py
python3 tools/fastlane-build/fastlane_build.py check-licenses

# Payload build (requires P02's fastlane-payload/CMakeLists.txt)
bash tools/fastlane-build/build-payload.sh
```

## Production APK path

`app/build.gradle.kts` adds `-Porchestella.fastlane.toolboxDir=<dir>` as a Java
resource source set when the property is present. AGP packages `bin/` at the
APK root, then zipaligns and signs. This is deliberately before signing: the
synthetic `inject-apk` command refuses an APK that already has a v1/v2
signature, because rewriting a signed APK invalidates its signature.

`verify-apk` checks:

- every manifest entry is present, byte-exact and SHA256-exact;
- exec bits are set when the zip records a mode; a zero mode is reported as
  `zipfuseDefaultMode` because zipfuse grants `bin/` executability;
- every ELF under `bin/` has all PT_LOAD `p_align >= 16384`;
- total `bin/` size is within the 80 MiB starting budget;
- the embedded manifest equals the expected runtime manifest;
- `zipalign -c -P 16 -v 4` passes when a build-tools `zipalign` is supplied.

## Failure semantics

All gates exit non-zero on failure. `prove_gate_blocks.py` tampers with a
synthetic APK, attempts injection into a signed APK and exceeds the size
budget, then asserts that each attempt is rejected. CI runs it on every change.

The payload script exits `2` with `BLOCKED` when `fastlane-payload/` is absent.
That is a dependency on P02, not a passing payload build.
