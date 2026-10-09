#!/usr/bin/env bash
set -euo pipefail

TOOLS_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
REPOSITORY_DIRECTORY="$(cd -- "$TOOLS_DIRECTORY/../.." && pwd -P)"
MANIFEST="$REPOSITORY_DIRECTORY/app/src/main/AndroidManifest.xml"
ACTIVITY="$REPOSITORY_DIRECTORY/app/src/main/java/io/github/nyzeep/orchestella/MainActivity.kt"
APP_BUILD="$REPOSITORY_DIRECTORY/app/build.gradle.kts"
LOCK="$TOOLS_DIRECTORY/qemu-jni.lock"

grep --fixed-strings 'apk_packaging_mode=extractNativeLibs:true' "$LOCK" >/dev/null || {
  printf 'QEMU lock does not require explicit native-library extraction\n' >&2
  exit 1
}
grep --fixed-strings 'android:extractNativeLibs="true"' "$MANIFEST" >/dev/null || {
  printf 'Android manifest does not explicitly require extracted native libraries\n' >&2
  exit 1
}
grep --extended-regexp '^[[:space:]]*useLegacyPackaging[[:space:]]*=[[:space:]]*true[[:space:]]*$' "$APP_BUILD" >/dev/null || {
  printf 'AGP must explicitly use installer-extracted native-library packaging\n' >&2
  exit 1
}
grep --fixed-strings 'applicationInfo.nativeLibraryDir' "$ACTIVITY" >/dev/null || {
  printf 'runtime does not read the installed nativeLibraryDir\n' >&2
  exit 1
}
NATIVE_MANIFEST="$REPOSITORY_DIRECTORY/runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/RuntimeArtifactManifest.java"
[[ -f "$NATIVE_MANIFEST" ]] || {
  printf 'QEMU installed-path verifier is unavailable\n' >&2
  exit 1
}
if grep --extended-regexp '(filesDir|cacheDir|getExternalFilesDir|externalCacheDir|ProcessBuilder|Runtime\.getRuntime\(\)\.exec\()' \
  "$NATIVE_MANIFEST" >/dev/null; then
  printf 'QEMU installed-path verifier contains a writable execution source or exec call\n' >&2
  exit 1
fi
# The two approved native-execution sites: the engine launcher seam (direct
# exec of the exact spec array, never a shell) and the app launcher (builds the
# spec via QemuLaunchSpec and executes from nativeLibraryDir). Any other exec
# call in production sources remains fail-closed.
LAUNCHER="$REPOSITORY_DIRECTORY/app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java"
SEAM="$REPOSITORY_DIRECTORY/runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuProcessLauncher.java"
[[ -f "$LAUNCHER" && -f "$SEAM" ]] || {
  printf 'QEMU launcher or engine seam is unavailable\n' >&2
  exit 1
}
grep --fixed-strings 'QemuLaunchSpec.build' "$LAUNCHER" >/dev/null || {
  printf 'QEMU instance launcher must build its command via QemuLaunchSpec\n' >&2
  exit 1
}
grep --fixed-strings 'nativeLibraryDir' "$LAUNCHER" >/dev/null || {
  printf 'QEMU instance launcher must execute from nativeLibraryDir\n' >&2
  exit 1
}
grep --fixed-strings 'new ProcessBuilder(spec).start()' "$SEAM" >/dev/null || {
  printf 'engine seam must exec the exact spec array directly\n' >&2
  exit 1
}
if grep --extended-regexp '(filesDir|cacheDir|getExternalFilesDir|externalCacheDir|Runtime\.getRuntime\(\)\.exec\(|"/bin/sh"|/system/bin/sh)' \
  "$LAUNCHER" "$SEAM" >/dev/null; then
  printf 'approved launcher sites reference a writable execution directory or shell\n' >&2
  exit 1
fi
if grep --recursive --include='*.java' --include='*.kt' \
  --extended-regexp '(ProcessBuilder|Runtime\.getRuntime\(\)\.exec\()' \
  "$REPOSITORY_DIRECTORY/app/src/main/java" \
  "$REPOSITORY_DIRECTORY/runtime/engine-qemu/src/main/java" \
  | grep --fixed-strings -v -e "$LAUNCHER" -e "$SEAM" >/dev/null; then
  printf 'native execution outside the approved launcher sites must be separately reviewed\n' >&2
  exit 1
fi

printf 'DROIDLAYER_QEMU_APK_PACKAGING_POLICY_OK extracted_native_libs=1 native_library_dir=1 writable_exec_paths=0 launcher_reviewed=1\n'
