#!/usr/bin/env bash
# Builds libfastlane_payload.so for arm64-v8a and verifies the AVmPayload_main
# entry point. Requires an Android NDK with CMake support; this repository's
# zero-device environment does not ship one, so this script is not exercised by
# the host test run.
set -euo pipefail

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK root}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${ROOT}/build-android"
API_LEVEL="${FASTLANE_API_LEVEL:-26}"

cmake -S "${ROOT}" -B "${BUILD}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DFL_BUILD_TESTS=OFF

cmake --build "${BUILD}" --target fastlane_payload -j"$(nproc 2>/dev/null || echo 4)"

SO="${BUILD}/libfastlane_payload.so"
test -f "${SO}"

NM="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
if [ -x "${NM}" ]; then
    "${NM}" -D --defined-only "${SO}" | grep -E " T AVmPayload_main$"
else
    echo "llvm-nm not found; run: nm -D --defined-only ${SO} | grep AVmPayload_main" >&2
fi

echo "built ${SO}"
echo "NOTE: do not package libvm_payload.so from the build tree; the AVF APEX provides it."
