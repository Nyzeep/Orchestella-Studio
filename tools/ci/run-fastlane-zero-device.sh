#!/usr/bin/env bash
# Zero-device gate for the Fast Lane lanes. One command set for local and CI.
#
#   tools/ci/run-fastlane-zero-device.sh jvm     # all runtime/fastlane-* JVM modules
#   tools/ci/run-fastlane-zero-device.sh cpp     # payload build + CTest (ASan/UBSan)
#   tools/ci/run-fastlane-zero-device.sh python  # toolbox and QEMU tuning gates
#   tools/ci/run-fastlane-zero-device.sh e2e     # host mux <-> real payload (Unix socket)
#   tools/ci/run-fastlane-zero-device.sh all     # jvm + cpp + python (+ e2e when built)
#
# JVM: uses Gradle when ANDROID_HOME is set (CI); otherwise a JDK 17+ javac
# fallback with JUnit 4.13.2, because the root settings include :app and cannot
# configure without an Android SDK.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"

MODULES=(
  engine-fastlane
  fastlane-terminal
  fastlane-capability
  fastlane-proxy
  fastlane-storage
  fastlane-telemetry
  fastlane-distcc
)

OUT="${FASTLANE_GATE_OUT:-$ROOT/build/fastlane-gate}"
JUNIT_JAR="${JUNIT_JAR:-}"
HAMCREST_JAR="${HAMCREST_JAR:-}"
PAYLOAD_BIN="${FASTLANE_PAYLOAD_BIN:-$ROOT/build/fastlane-payload/fastlane_payload_host}"

log() { printf '\n== %s ==\n' "$*"; }

find_junit() {
  if [ -n "$JUNIT_JAR" ] && [ -f "$JUNIT_JAR" ]; then
    return 0
  fi
  for candidate in \
    "$ROOT"/../junit-4.13.2.jar \
    /tmp/fl-junit/junit-4.13.2.jar \
    "$HOME"/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/*/junit-4.13.2.jar; do
    if [ -f "$candidate" ]; then
      JUNIT_JAR="$candidate"
      break
    fi
  done
  for candidate in /tmp/fl-junit/hamcrest-core-1.3.jar \
    "$HOME"/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar; do
    if [ -f "$candidate" ]; then
      HAMCREST_JAR="$candidate"
      break
    fi
  done
  if [ ! -f "${JUNIT_JAR:-/nonexistent}" ]; then
    echo "JUNIT_JAR is required (JUnit 4.13.2); set JUNIT_JAR=/path/to/junit.jar" >&2
    return 1
  fi
}

run_jvm_gradle() {
  log "JVM modules via Gradle"
  local tasks=()
  for module in "${MODULES[@]}"; do
    tasks+=(":runtime:$module:test")
  done
  bash tools/ci/run-gradle-gate.sh "${tasks[@]}"
}

run_jvm_javac() {
  log "JVM modules via javac (no Android SDK)"
  find_junit || return 1
  rm -rf "$OUT/jvm"
  mkdir -p "$OUT/jvm"
  local classpath=""
  for module in "${MODULES[@]}"; do
    local main="$OUT/jvm/$module/main"
    local test="$OUT/jvm/$module/test"
    mkdir -p "$main" "$test"
    find "runtime/$module/src/main/java" -name '*.java' > "$OUT/jvm/$module/main.list"
    if ! javac --release 17 -Xlint:all -Werror -cp "$classpath" \
        -d "$main" @"$OUT/jvm/$module/main.list"; then
      echo "FAIL javac main $module" >&2
      return 1
    fi
    find "runtime/$module/src/test/java" -name '*.java' > "$OUT/jvm/$module/test.list" 2>/dev/null || true
    if [ ! -s "$OUT/jvm/$module/test.list" ]; then
      continue
    fi
    if ! javac --release 17 -Xlint:all -Werror \
        -cp "$main:$classpath:$JUNIT_JAR:${HAMCREST_JAR:-}" \
        -d "$test" @"$OUT/jvm/$module/test.list"; then
      echo "FAIL javac test $module" >&2
      return 1
    fi
    if [ -d "runtime/$module/src/test/resources" ]; then
      cp -R "runtime/$module/src/test/resources/." "$test/" 2>/dev/null || true
    fi
    local classes
    classes=$(find "$test" -name '*Test.class' | sed "s#$test/##; s#/#.#g; s#\.class\$##")
    if [ -n "$classes" ]; then
      echo "--- $module ---"
      # shellcheck disable=SC2086
      java -Dfln1.golden="$ROOT/protocol/fln1-golden.json" \
        -cp "$test:$main:$classpath:$JUNIT_JAR:${HAMCREST_JAR:-}" \
        org.junit.runner.JUnitCore $classes || return 1
    fi
    classpath="$main:$classpath"
  done
}

run_cpp() {
  log "Payload C++ (CMake + CTest, sanitizers)"
  local build="${FL_BUILD_DIR:-$ROOT/build/fastlane-payload}"
  cmake -S fastlane-payload -B "$build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Debug -DFL_SANITIZE=ON || return 1
  cmake --build "$build" -j || return 1
  ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=print_stacktrace=1 \
    ctest --test-dir "$build" --output-on-failure || return 1
}

run_python() {
  log "Python gates"
  local python=python3
  if ! "$python" -c 'import yaml' >/dev/null 2>&1; then
    local venv="${FL_VENV:-$ROOT/build/fastlane-venv}"
    if [ ! -x "$venv/bin/python3" ]; then
      "$python" -m venv "$venv" || return 1
      "$venv/bin/python3" -m pip install --quiet pyyaml==6.0.2 || return 1
    fi
    python="$venv/bin/python3"
  fi
  "$python" -m unittest discover -s tools/fastlane-build -p 'test_*.py' || return 1
  "$python" -m unittest discover -s tools/qemu-tuning -p 'test_*.py' || return 1
  "$python" -m unittest discover -s tools/compiler-equivalence -p 'test_*.py' || return 1
  # Native-lane kit: fixture contract, dry run and provenance gating. No device.
  "$python" -m unittest discover -s tools/native-lane -p 'test_*.py' || return 1
}

run_e2e() {
  log "Host TerminalMux <-> payload process (FLN1 over Unix socket)"
  if [ ! -x "$PAYLOAD_BIN" ]; then
    echo "not-tested: $PAYLOAD_BIN is missing; run the cpp step first" >&2
    return 0
  fi
  find_junit || return 1
  local out="$OUT/e2e"
  rm -rf "$out"
  mkdir -p "$out/main" "$out/test"
  find runtime/engine-fastlane/src/main/java runtime/fastlane-terminal/src/main/java \
    -name '*.java' > "$out/main.list"
  javac --release 17 -Xlint:all -Werror -d "$out/main" @"$out/main.list" || return 1
  javac --release 17 -Xlint:all -Werror \
    -cp "$out/main:$JUNIT_JAR:${HAMCREST_JAR:-}" -d "$out/test" \
    runtime/fastlane-terminal/src/test/java/io/github/nyzeep/orchestella/runtime/fastlane/terminal/Fln1PayloadE2ETest.java \
    || return 1
  java -Dfln1.payload="$PAYLOAD_BIN" -Dfln1.golden="$ROOT/protocol/fln1-golden.json" \
    -cp "$out/test:$out/main:$JUNIT_JAR:${HAMCREST_JAR:-}" \
    org.junit.runner.JUnitCore \
    io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1PayloadE2ETest
}

case "${1:-all}" in
  jvm)
    if [ -n "${ANDROID_HOME:-}" ] && [ -x ./gradlew ]; then
      run_jvm_gradle
    else
      run_jvm_javac
    fi
    ;;
  cpp) run_cpp ;;
  python) run_python ;;
  e2e) run_e2e ;;
  all)
    status=0
    run_jvm_javac || status=1
    run_cpp || status=1
    run_python || status=1
    run_e2e || status=1
    exit "$status"
    ;;
  *)
    echo "usage: $0 {jvm|cpp|python|e2e|all}" >&2
    exit 2
    ;;
esac
