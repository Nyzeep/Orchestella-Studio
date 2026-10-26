#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Native-lane fixture generator: one preprocessed unit, four columns, one hash.

Every column of the native-lane kit (``qemu``/``pvm``/``proot``/``bare``) must
compile *the same bytes*. This script owns those bytes:

* ``fixtures/fixture.c`` -- the canonical source. The generator is a byte-for-byte
  copy of ``tools/device-validation/dv_pvm_compiler.py:build_fixture`` so the
  numbers stay comparable with the distcc crossfire line (DV-PVM-02b / session 9).
  ``test_native_lane.py`` asserts that equality; if the two ever diverge the test
  fails rather than the measurement silently comparing apples to oranges.
* ``fixtures/fixture.i`` -- ``clang -E`` output. The *device* run normally
  regenerates this with the device clang (see ``measure.py --regen-dot-i``);
  the committed copy is the reference that a host-only run starts from.
* ``fixtures/MANIFEST.json`` -- sizes, SHA-256 digests and the exact argv that
  produced the ``.i``, so "same fixture" is a checkable claim instead of a promise.

Host-only: no adb, no device, no network. Run ``--verify`` to prove the checked-in
bytes still match the generator.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = 1
DEFAULT_FUNCTIONS = 2000
DEFAULT_TRIPLE = "aarch64-linux-android28"
HERE = Path(__file__).resolve().parent
DEFAULT_OUT = HERE / "fixtures"
MANIFEST_NAME = "MANIFEST.json"

# A clang that cannot preprocess is not a clang we want to trust.
PREPROCESS_TIMEOUT_S = 120


def build_fixture(functions: int = DEFAULT_FUNCTIONS) -> str:
    """2000 independent one-line functions; kept identical to DV-PVM-02's shape.

    No includes, no macros, no conditionals: preprocessing this file is pure
    line-marker plumbing, which is exactly why the four columns can share it.
    """
    lines = [
        "/* DV-PVM-02 device fixture: %d functions, preprocessed form. */" % functions,
        "int puts(const char*);",
    ]
    for index in range(functions):
        lines.append("int probe_fn_%d(int x) { return x + %d; }" % (index, index))
    touched = sorted({0, 1, 2, 3, functions // 2, functions - 1})
    terms = " + ".join("probe_fn_%d(%d)" % (i, i) for i in touched)
    lines.append("int fixture_entry(void) {")
    lines.append("    int total = %s;" % terms)
    lines.append("    return total % 251;")
    lines.append("}")
    return "\n".join(lines) + "\n"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def find_clang(explicit: str | None) -> str | None:
    """Locate a host clang without hard-coding any machine-specific path.

    Order: ``--clang``, ``$NATIVE_LANE_CLANG``, ``$ANDROID_NDK_HOME``,
    ``$ANDROID_NDK_ROOT``, ``$ANDROID_SDK_ROOT/ndk/<newest>``, ``PATH``.
    Anything found is recorded in the manifest; nothing found is not an error
    when ``fixtures/fixture.i`` already exists.
    """
    if explicit:
        candidate = Path(explicit)
        if not candidate.is_file():
            raise SystemExit(f"--clang not found: {explicit}")
        return str(candidate)
    for variable in ("NATIVE_LANE_CLANG",):
        value = os.environ.get(variable)
        if value and Path(value).is_file():
            return value
    for variable in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        root = os.environ.get(variable)
        if not root:
            continue
        for relative in ("toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe",
                         "toolchains/llvm/prebuilt/linux-x86_64/bin/clang",
                         "toolchains/llvm/prebuilt/darwin-x86_64/bin/clang"):
            candidate = Path(root) / relative
            if candidate.is_file():
                return str(candidate)
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk:
        ndk_root = Path(sdk) / "ndk"
        if ndk_root.is_dir():
            for ndk in sorted(ndk_root.iterdir(), reverse=True):
                for relative in ("toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe",
                                 "toolchains/llvm/prebuilt/linux-x86_64/bin/clang",
                                 "toolchains/llvm/prebuilt/darwin-x86_64/bin/clang"):
                    candidate = ndk / relative
                    if candidate.is_file():
                        return str(candidate)
    return shutil.which("clang")


def clang_version(clang: str) -> str:
    """First line of ``clang --version``, e.g. ``... clang version 18.0.1``."""
    completed = subprocess.run([clang, "--version"], capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=60)
    if completed.returncode != 0:
        raise SystemExit(f"clang --version failed: {completed.stderr.strip()}")
    return completed.stdout.splitlines()[0].strip() if completed.stdout else "unknown"


def preprocess(clang: str, triple: str, source_text: str, workdir: Path) -> bytes:
    """``clang -E`` the fixture and return the exact bytes we will ship.

    ``-x c`` keeps the driver from guessing; ``--target`` pins the predefined
    macro set so a Linux host produces the same translation unit a guest would.

    The compile runs with ``cwd=workdir`` and *relative* file names on purpose.
    clang echoes the name it was given into the first line marker, so passing an
    absolute path would bake this machine's directory into the committed ``.i``
    (non-reproducible, and a leak the sensitive-data gate exists to catch).
    """
    workdir.mkdir(parents=True, exist_ok=True)
    source = workdir / "fixture.c"
    source.write_text(source_text, encoding="utf-8", newline="\n")
    output = workdir / "fixture.i"
    argv = [clang, "-E", "--target=" + triple, "-x", "c", "fixture.c", "-o", "fixture.i"]
    completed = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8",
                               errors="replace", timeout=PREPROCESS_TIMEOUT_S,
                               cwd=str(workdir))
    if completed.returncode != 0:
        raise SystemExit("clang -E failed:\n" + (completed.stderr or completed.stdout))
    return output.read_bytes()


def read_manifest(out: Path) -> dict:
    path = out / MANIFEST_NAME
    if not path.is_file():
        raise SystemExit(f"manifest missing: {path}; run the generator first")
    return json.loads(path.read_text(encoding="utf-8"))


def generate(out: Path, functions: int, clang: str | None, triple: str,
             keep_dot_i: bool) -> int:
    out.mkdir(parents=True, exist_ok=True)
    source_text = build_fixture(functions)
    source_bytes = source_text.encode("utf-8")
    (out / "fixture.c").write_bytes(source_bytes)
    print(f"fixture.c   {len(source_bytes)} B  {sha256_bytes(source_bytes)}")

    files = {"fixture.c": {"bytes": len(source_bytes), "sha256": sha256_bytes(source_bytes)}}
    producer = None
    if not keep_dot_i:
        if clang:
            version = clang_version(clang)
            workdir = out / ".pp"
            dot_i = preprocess(clang, triple, source_text, workdir)
            shutil.rmtree(workdir, ignore_errors=True)
            (out / "fixture.i").write_bytes(dot_i)
            print(f"fixture.i   {len(dot_i)} B  {sha256_bytes(dot_i)}")
            files["fixture.i"] = {"bytes": len(dot_i), "sha256": sha256_bytes(dot_i)}
            producer = {"tool": "clang", "version": version, "triple": triple,
                        "argv": ["clang", "-E", "--target=" + triple, "-x", "c",
                                 "fixture.c", "-o", "fixture.i"]}
        else:
            existing = out / "fixture.i"
            print("clang not found: keeping the committed fixtures/fixture.i "
                  "(set --clang or $NATIVE_LANE_CLANG to regenerate)", file=sys.stderr)
            if not existing.is_file():
                raise SystemExit("no clang and no fixtures/fixture.i; cannot continue")
            dot_i = existing.read_bytes()
            files["fixture.i"] = {"bytes": len(dot_i), "sha256": sha256_bytes(dot_i)}
    else:
        existing = out / "fixture.i"
        if existing.is_file():
            dot_i = existing.read_bytes()
            files["fixture.i"] = {"bytes": len(dot_i), "sha256": sha256_bytes(dot_i)}

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "functions": functions,
        "files": files,
        "dot_i_producer": producer,
        "note": ("fixture.c is generated; fixture.i is clang -E output. A device run may "
                 "regenerate fixture.i with the device clang (measure.py --regen-dot-i) "
                 "and must record the new sha256 in the run JSON."),
    }
    (out / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n",
                                     encoding="utf-8", newline="\n")
    print(f"manifest    {MANIFEST_NAME}")
    return 0


def verify(out: Path, functions: int, clang: str | None, triple: str) -> int:
    """Re-derive every checked-in byte and compare against the manifest."""
    manifest = read_manifest(out)
    problems: list[str] = []

    expected = manifest.get("files", {})
    source_bytes = build_fixture(manifest.get("functions", functions)).encode("utf-8")
    actual_c = (out / "fixture.c").read_bytes() if (out / "fixture.c").is_file() else b""
    if actual_c != source_bytes:
        problems.append(f"fixture.c mismatch: on disk {sha256_bytes(actual_c)} "
                        f"({len(actual_c)} B) != generated {sha256_bytes(source_bytes)} "
                        f"({len(source_bytes)} B)")
    else:
        print(f"fixture.c   OK  {len(actual_c)} B  {sha256_bytes(actual_c)}")
    if expected.get("fixture.c", {}).get("sha256") != sha256_bytes(source_bytes):
        problems.append("fixture.c digest in MANIFEST.json is stale; regenerate")

    dot_i = out / "fixture.i"
    if not dot_i.is_file():
        problems.append("fixture.i missing")
    else:
        actual_i = dot_i.read_bytes()
        recorded = expected.get("fixture.i", {}).get("sha256")
        if recorded != sha256_bytes(actual_i):
            problems.append("fixture.i digest in MANIFEST.json is stale; regenerate")
        producer = manifest.get("dot_i_producer") or {}
        can_reproduce = bool(clang) and producer.get("triple") == triple
        if can_reproduce:
            host_version = clang_version(clang)
            if host_version != producer.get("version"):
                print(f"fixture.i   SKIP (produced by {producer.get('version')!r}, "
                      f"host clang is {host_version!r}); digest recorded only")
            else:
                workdir = out / ".verify"
                regenerated = preprocess(clang, triple, source_bytes.decode("utf-8"), workdir)
                shutil.rmtree(workdir, ignore_errors=True)
                if regenerated != actual_i:
                    problems.append(f"fixture.i not reproducible: {sha256_bytes(regenerated)} "
                                    f"!= {sha256_bytes(actual_i)}")
                else:
                    print(f"fixture.i   OK  {len(actual_i)} B  {sha256_bytes(actual_i)}")
        else:
            print(f"fixture.i   digest recorded only  {len(actual_i)} B  "
                  f"{sha256_bytes(actual_i)}")

    if problems:
        for problem in problems:
            print("FAIL " + problem, file=sys.stderr)
        return 1
    print("FIXTURE_VERIFY_OK")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="fixtures directory")
    parser.add_argument("--functions", type=int, default=DEFAULT_FUNCTIONS)
    parser.add_argument("--triple", default=DEFAULT_TRIPLE,
                        help="target triple for clang -E (default matches the guest)")
    parser.add_argument("--clang", default=None,
                        help="host clang; falls back to $NATIVE_LANE_CLANG / NDK / PATH")
    parser.add_argument("--verify", action="store_true",
                        help="recompute and compare against MANIFEST.json")
    parser.add_argument("--keep-dot-i", action="store_true",
                        help="do not regenerate fixture.i, only digest the existing one")
    args = parser.parse_args(argv)

    out = Path(args.out)
    clang = find_clang(args.clang)
    if args.verify:
        return verify(out, args.functions, clang, args.triple)
    return generate(out, args.functions, clang, args.triple, args.keep_dot_i)


if __name__ == "__main__":
    sys.exit(main())
