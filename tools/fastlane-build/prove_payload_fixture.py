#!/usr/bin/env python3
"""Prove the P11 payload build flow without an Android NDK.

The real payload is owned by P02 and is absent from this worktree. This script
builds a minimal host shared library with the same CMake shape, checks that it
exports ``AVmPayload_main``, and proves that ``build-payload.sh`` reports
BLOCKED (exit 2) when the payload source directory is missing. It is a flow
test, not an Android artifact: 16 KB ELF alignment and the Bionic ABI remain
not-tested until P02 lands and the NDK is available.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PAYLOAD_BUILD = HERE / "build-payload.sh"
SYMBOL = "AVmPayload_main"

CMAKE = """cmake_minimum_required(VERSION 3.20)
project(fastlane_payload_fixture C)
add_library(fastlane_payload_fixture SHARED fastlane_payload.c)
set_target_properties(fastlane_payload_fixture PROPERTIES
    PREFIX ""
    OUTPUT_NAME "libfastlane_payload_fixture")
"""

SOURCE = """__attribute__((visibility("default")))
int AVmPayload_main(void) {
    return 0;
}
"""


def run(command: list[str], cwd: Path, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        cwd=cwd,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )


def build_fixture(root: Path) -> Path:
    src = root / "src"
    build = root / "build"
    src.mkdir()
    (src / "CMakeLists.txt").write_text(CMAKE, encoding="utf-8")
    (src / "fastlane_payload.c").write_text(SOURCE, encoding="utf-8")
    configure = ["cmake", "-S", str(src), "-B", str(build), "-DCMAKE_BUILD_TYPE=Release"]
    if shutil.which("ninja"):
        configure += ["-G", "Ninja"]
    result = run(configure, REPO)
    if result.returncode != 0:
        raise SystemExit(f"FAIL cmake configure: {result.stdout}{result.stderr}")
    result = run(["cmake", "--build", str(build), "--parallel"], REPO)
    if result.returncode != 0:
        raise SystemExit(f"FAIL cmake build: {result.stdout}{result.stderr}")
    libraries = sorted(build.rglob("*.so"))
    if not libraries:
        raise SystemExit(f"FAIL fixture produced no .so under {build}")
    return libraries[0]


def check_symbol(library: Path) -> None:
    nm = shutil.which("nm")
    if nm is None:
        raise SystemExit("FAIL nm is required to check the exported payload symbol")
    result = run([nm, "-D", "--defined-only", str(library)], REPO)
    if result.returncode != 0:
        raise SystemExit(f"FAIL nm failed: {result.stderr}")
    if SYMBOL not in result.stdout:
        raise SystemExit(f"FAIL {SYMBOL} is not exported by {library}")
    print(f"PAYLOAD_FIXTURE_SYMBOL_OK library={library.name} symbol={SYMBOL}")


def check_missing_payload_is_blocked() -> None:
    with tempfile.TemporaryDirectory(prefix="fastlane-payload-empty-") as empty:
        environment = dict(os.environ)
        environment["FASTLANE_PAYLOAD_DIR"] = empty
        result = run(["bash", str(PAYLOAD_BUILD)], REPO, environment)
    output = result.stdout + result.stderr
    if result.returncode != 2:
        raise SystemExit(f"FAIL missing payload must exit 2, got {result.returncode}: {output}")
    if "BLOCKED" not in output:
        raise SystemExit(f"FAIL missing payload did not report BLOCKED: {output}")
    print("PAYLOAD_FIXTURE_BLOCKED_OK")


def main() -> int:
    if shutil.which("cmake") is None:
        raise SystemExit("FAIL cmake is required for the payload fixture")
    with tempfile.TemporaryDirectory(prefix="fastlane-payload-fixture-") as temporary:
        library = build_fixture(Path(temporary))
        check_symbol(library)
    check_missing_payload_is_blocked()
    print("PAYLOAD_FIXTURE_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
