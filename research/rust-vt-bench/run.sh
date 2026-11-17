#!/usr/bin/env bash
# One-shot harness for research/rust-vt-bench.
#
# Steps: fixtures -> cargo test -> rust bench -> (optional) Kotlin baseline ->
# compare.  Missing Rust workspace pieces are skipped so the script can run
# before the other lanes land; a present-but-broken workspace still fails.
#
# Usage:  ./run.sh [--kotlin]
#         RUST_VT_REGENERATE=1 ./run.sh   # force fixture regeneration
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
GEN_DIR="$SCRIPT_DIR/fixtures/generated"
OUT_DIR="$SCRIPT_DIR/out"
RUN_KOTLIN=0

for arg in "$@"; do
  case "$arg" in
    --kotlin) RUN_KOTLIN=1 ;;
    -h|--help)
      echo "usage: $0 [--kotlin]"
      echo "  --kotlin   also run the slow Gradle RustBaselineBenchTest"
      exit 0
      ;;
    *)
      echo "error: unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

step() {
  printf '\n==> %s\n' "$1"
}

mkdir -p "$OUT_DIR"

step "1/6 fixtures"
if [ -f "$GEN_DIR/manifest.json" ] && [ "${RUST_VT_REGENERATE:-0}" != "1" ]; then
  echo "existing fixtures found; verifying (set RUST_VT_REGENERATE=1 to rebuild)"
  ( cd "$SCRIPT_DIR" && python3 fixtures/gen.py --verify )
else
  ( cd "$SCRIPT_DIR" && python3 fixtures/gen.py )
fi
( cd "$GEN_DIR" && sha256sum -c SHA256SUMS )
( cd "$GEN_DIR" && sha256sum -c ../expected-sha256.txt )

step "2/6 cargo test"
if ! command -v cargo >/dev/null 2>&1; then
  echo "SKIP: cargo not installed"
elif [ -f "$SCRIPT_DIR/Cargo.toml" ]; then
  ( cd "$SCRIPT_DIR" && cargo test --release --workspace )
else
  echo "SKIP: workspace not ready ($SCRIPT_DIR/Cargo.toml missing)"
fi

step "3/6 rust bench"
if ! command -v cargo >/dev/null 2>&1; then
  echo "SKIP: cargo not installed"
elif [ -f "$SCRIPT_DIR/Cargo.toml" ] && [ -f "$SCRIPT_DIR/vt-bench/Cargo.toml" ]; then
  ( cd "$SCRIPT_DIR" && cargo run --release -p vt-bench -- bench all --out "$OUT_DIR/rust.json" )
else
  echo "SKIP: vt-bench package not ready"
fi

step "4/6 kotlin baseline"
if [ "$RUN_KOTLIN" -eq 1 ]; then
  export ANDROID_HOME=/opt/android-sdk
  export ANDROID_SDK_ROOT=/opt/android-sdk
  export JAVA_HOME=/usr/lib/jvm/temurin-17
  ( cd "$REPO_ROOT" && ./gradlew :app:testDebugUnitTest \
      -Porchestella.assets.allowMissingPayload=true \
      -Porchestella.repositories.useAliyunMirror=true \
      --tests '*RustBaselineBenchTest*' )
else
  echo "SKIP: kotlin baseline disabled (pass --kotlin to run; it is slow)"
fi

step "5/6 compare (gates median alloc/MB against out/alloc_baseline.json)"
if [ -f "$OUT_DIR/kotlin.json" ] && [ -f "$OUT_DIR/rust.json" ]; then
  ( cd "$SCRIPT_DIR" && python3 compare.py )
else
  echo "SKIP: need both out/kotlin.json and out/rust.json"
fi

step "6/6 done"
echo "OK"
for artifact in "$GEN_DIR" "$OUT_DIR/rust.json" "$OUT_DIR/kotlin.json" "$OUT_DIR/compare.md"; do
  if [ -e "$artifact" ]; then
    echo "  $artifact"
  else
    echo "  $artifact (missing)"
  fi
done
