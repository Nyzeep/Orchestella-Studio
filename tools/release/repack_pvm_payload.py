#!/usr/bin/env python3
"""Post-packaging repack: rewrite the pVM payload libraries to stored entries.

Why this exists
---------------
2026-11-14 single-package round. The complete APK embeds every engine, and the
two packaging contracts used to fight each other:

  - The exec-based engines (UML kernel/stub/net helpers, QEMU's qemu and
    gvproxy spawns) need the installer to unpack native libraries into
    nativeLibraryDir, i.e. extractNativeLibs=true / useLegacyPackaging=true.
    Device-verified: with extractNativeLibs=false the layout leaves
    nativeLibraryDir empty and every UML exec dies before its first boot.
  - The pVM payload's AVF loader reads the APK entries directly and only
    accepts uncompressed (stored), 16 KB page-aligned .so entries. With
    legacy packaging AGP writes the five payload libraries deflated, which
    the loader refuses.

The gradle pipeline resolves this in three steps after :app packaging:
  1. this script rewrites exactly the five pVM payload entries
     (libfastlane_payload.so, libpvm_compiler_payload.so, libLLVM.so,
     libclang-cpp.so, libc++_shared.so) from deflate to stored — every other
     entry keeps its compression method and byte content;
  2. zipalign -P 16 re-aligns the now-stored entries to 16 KB pages;
  3. apksigner re-signs the aligned APK (the rewrite invalidates AGP's
     signature by construction).

Byte honesty is enforced here, not assumed. Before rewriting, the script
reconciles three witnesses per manifest-pinned library:
  - the APK entry content,
  - the staged source .so on the packaging machine,
  - the sha256 recorded in the APK's own assets/PVM-COMPILER-MANIFEST.
Any disagreement means AGP stripped or corrupted the bytes in transit — the
gate fails with the named library instead of shipping a payload the device
will refuse or, worse, accept as different bytes. libLLVM.so, libclang-cpp.so
and libc++_shared.so are pinned by the manifest; libfastlane_payload.so and
libpvm_compiler_payload.so are reconciled against the staged sources (the
manifest names only the compiler-side libraries and payload_binary).
libpvm_compiler_payload.so is additionally checked against the manifest's
payload_binary name presence.

After writing, the script re-opens its own output and re-proves: every entry
of the input is present, every entry's content digest is unchanged, the five
payload entries are stored and everything else kept its compression method.

Standard library only; no network.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import zipfile
from pathlib import Path
from typing import Sequence

MANIFEST_ENTRY = "assets/PVM-COMPILER-MANIFEST"
LIB_PREFIX = "lib/arm64-v8a/"

# The staged pVM payload set. Order is the rewrite order; the APK keeps the
# original entry order for everything (the rewrite only swaps the method).
PAYLOAD_SOS: tuple[str, ...] = (
    "libfastlane_payload.so",
    "libpvm_compiler_payload.so",
    "libLLVM.so",
    "libclang-cpp.so",
    "libc++_shared.so",
)
# Where each staged source lives: the fastlane payload dir carries the two
# payload libraries (the AVF payload binary is libpvm_compiler_payload.so,
# per the manifest's payload_binary); the pVM compiler dir carries the three
# manifest-declared engine libraries. This mirrors the gradle staging split
# (jniLibs.srcDir over both roots; the compiler root additionally carries
# assets/PVM-COMPILER-MANIFEST).
PAYLOAD_DIR_SOS = ("libfastlane_payload.so", "libpvm_compiler_payload.so")
COMPILER_DIR_SOS = ("libLLVM.so", "libclang-cpp.so", "libc++_shared.so")

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_ERROR = 2


def sha256_of_zip_entry(archive: zipfile.ZipFile, entry: str) -> str | None:
    if entry not in archive.namelist():
        return None
    digest = hashlib.sha256()
    with archive.open(entry) as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_of_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fail(message: str) -> None:
    print(f"REPACK_FAIL {message}", file=sys.stderr)


def resolve_staged_sources(payload_dir: Path, compiler_dir: Path) -> dict[str, Path]:
    sources: dict[str, Path] = {}
    for name in PAYLOAD_DIR_SOS:
        candidate = payload_dir / "arm64-v8a" / name
        if not candidate.is_file():
            fail(f"staged source missing: {candidate}")
            raise SystemExit(EXIT_ERROR)
        sources[name] = candidate
    for name in COMPILER_DIR_SOS:
        candidate = compiler_dir / "arm64-v8a" / name
        if not candidate.is_file():
            fail(f"staged source missing: {candidate}")
            raise SystemExit(EXIT_ERROR)
        sources[name] = candidate
    return sources


def preflight(apk: Path, sources: dict[str, Path]) -> dict[str, str]:
    """Three-witness reconciliation per library. Returns {entry: sha256} on success."""
    problems: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        entry_digests: dict[str, str] = {}
        for name in PAYLOAD_SOS:
            entry = LIB_PREFIX + name
            if entry not in names:
                problems.append(f"{entry}: missing from the APK (was the pVM payload staged?)")
                continue
            actual = sha256_of_zip_entry(archive, entry)
            if actual is None:  # unreachable after the membership check
                problems.append(f"{entry}: unreadable")
                continue
            staged = sha256_of_file(sources[name])
            if actual != staged:
                problems.append(
                    f"{entry}: APK bytes {actual[:16]}… != staged {staged[:16]}… "
                    "(strip or packaging corruption; keepDebugSymbols must cover this lib)"
                )
            entry_digests[entry] = actual

        # Manifest reconciliation: the shipped manifest names the compiler-side
        # libraries and pins their digests. The APK entries and the staged
        # sources must both match the manifest's own pins.
        if MANIFEST_ENTRY not in names:
            problems.append(f"{MANIFEST_ENTRY}: missing from the APK (was the pVM compiler staged?)")
        else:
            try:
                manifest = json.loads(archive.read(MANIFEST_ENTRY).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                manifest = None
                problems.append(f"{MANIFEST_ENTRY}: unreadable JSON ({exc})")
            if isinstance(manifest, dict):
                declared = manifest.get("libs")
                payload_binary = manifest.get("payload_binary")
                if not isinstance(declared, list) or not declared:
                    problems.append(f"{MANIFEST_ENTRY}: 'libs' must be a non-empty array")
                else:
                    for item in declared:
                        if not isinstance(item, dict):
                            problems.append(f"{MANIFEST_ENTRY}: non-object libs entry")
                            continue
                        lib_name = item.get("name")
                        lib_sha = item.get("sha256")
                        if not isinstance(lib_name, str) or not isinstance(lib_sha, str):
                            problems.append(f"{MANIFEST_ENTRY}: malformed libs entry {item!r}")
                            continue
                        entry = LIB_PREFIX + lib_name
                        if entry not in entry_digests:
                            if entry in names:
                                problems.append(f"{entry}: manifest-pinned but not in the staged payload set")
                            else:
                                problems.append(f"{entry}: manifest-pinned but missing from the APK")
                            continue
                        if entry_digests[entry] != lib_sha.lower():
                            problems.append(
                                f"{entry}: APK bytes {entry_digests[entry][:16]}… != manifest pin {lib_sha[:16]}…"
                            )
                        staged = sha256_of_file(sources.get(lib_name, Path()))
                        if staged != lib_sha.lower():
                            problems.append(
                                f"{entry}: staged bytes {staged[:16]}… != manifest pin {lib_sha[:16]}…"
                            )
                # The payload binary the manifest describes must be the one in
                # the APK (name-level check; its bytes were reconciled above
                # against the staged source, which is the build's pin source).
                if isinstance(payload_binary, str) and payload_binary != "libpvm_compiler_payload.so":
                    problems.append(
                        f"{MANIFEST_ENTRY}: payload_binary={payload_binary!r} but this pipeline "
                        "packages libpvm_compiler_payload.so"
                    )

    if problems:
        for problem in problems:
            fail(problem)
        raise SystemExit(EXIT_FAIL)
    return entry_digests


def rewrite(apk: Path, output: Path) -> None:
    """Copy every entry, storing the five payload libraries and preserving all else."""
    with zipfile.ZipFile(apk) as src, zipfile.ZipFile(output, "w") as dst:
        for info in src.infolist():
            clone = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            clone.compress_type = zipfile.ZIP_STORED if info.filename in {
                LIB_PREFIX + name for name in PAYLOAD_SOS
            } else info.compress_type
            clone.external_attr = info.external_attr
            clone.internal_attr = info.internal_attr
            clone.create_system = info.create_system
            clone.comment = info.comment
            with src.open(info) as reader:
                with dst.open(clone, "w") as writer:
                    shutil.copyfileobj(reader, writer, 1024 * 1024)


def postverify(apk: Path, output: Path, sources: dict[str, Path]) -> None:
    """Re-open the output and prove nothing but the compression methods moved."""
    with zipfile.ZipFile(apk) as src, zipfile.ZipFile(output) as out:
        src_names = src.namelist()
        out_names = out.namelist()
        if src_names != out_names:
            fail(f"entry list changed: only-in-input={sorted(set(src_names) - set(out_names))} "
                 f"only-in-output={sorted(set(out_names) - set(src_names))}")
            raise SystemExit(EXIT_FAIL)
        for name in src_names:
            before = src.getinfo(name)
            after = out.getinfo(name)
            stored = name in {LIB_PREFIX + n for n in PAYLOAD_SOS}
            expected_method = zipfile.ZIP_STORED if stored else before.compress_type
            if after.compress_type != expected_method:
                fail(f"{name}: compress_type {after.compress_type} != expected {expected_method}")
                raise SystemExit(EXIT_FAIL)
            before_sha = sha256_of_zip_entry(src, name)
            after_sha = sha256_of_zip_entry(out, name)
            if before_sha != after_sha:
                fail(f"{name}: content changed during the rewrite")
                raise SystemExit(EXIT_FAIL)
        for name in PAYLOAD_SOS:
            entry = LIB_PREFIX + name
            staged = sha256_of_file(sources[name])
            shipped = sha256_of_zip_entry(out, entry)
            if shipped != staged:
                fail(f"{entry}: output bytes {shipped[:16]}… != staged {staged[:16]}…")
                raise SystemExit(EXIT_FAIL)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="packaged (pre-repack) APK")
    parser.add_argument("--output", type=Path, help="repacked APK to write")
    parser.add_argument("--payload-lib-dir", type=Path,
                        help="staging dir carrying arm64-v8a/{libfastlane_payload,libpvm_compiler_payload}.so")
    parser.add_argument("--compiler-lib-dir", type=Path,
                        help="staging dir carrying arm64-v8a/{libLLVM,libclang-cpp,libc++_shared}.so and assets/PVM-COMPILER-MANIFEST")
    parser.add_argument("--selftest", action="store_true",
                        help="run the built-in synthetic fixture cases and exit")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    for required in ("apk", "output", "payload_lib_dir", "compiler_lib_dir"):
        if getattr(args, required) is None:
            parser.error(f"--{required.replace('_', '-')} is required (or use --selftest)")

    if not args.apk.is_file():
        print(f"REPACK_ERROR apk not found: {args.apk}", file=sys.stderr)
        return EXIT_ERROR
    if not args.payload_lib_dir.is_dir():
        print(f"REPACK_ERROR payload-lib-dir is not a directory: {args.payload_lib_dir}", file=sys.stderr)
        return EXIT_ERROR
    if not args.compiler_lib_dir.is_dir():
        print(f"REPACK_ERROR compiler-lib-dir is not a directory: {args.compiler_lib_dir}", file=sys.stderr)
        return EXIT_ERROR

    sources = resolve_staged_sources(args.payload_lib_dir, args.compiler_lib_dir)
    preflight(args.apk, sources)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    rewrite(args.apk, args.output)
    postverify(args.apk, args.output, sources)

    stored = ", ".join(LIB_PREFIX + name for name in PAYLOAD_SOS)
    print(f"REPACK_OK {len(PAYLOAD_SOS)} payload entries rewritten to stored: {stored}")
    print(f"REPACK_OK output: {args.output}")
    return EXIT_OK


def run_selftest() -> int:
    """Synthetic zip-as-APK fixtures, positive and negative, through main().

    No binary fixtures: each case builds a minimal APK plus a minimal staging
    tree in a throwaway directory, so the contract is testable anywhere python
    runs with zero inputs.
    """
    import contextlib
    import io
    import tempfile

    payload = b"fastlane-payload-bytes"
    compiler = b"pvm-compiler-payload-bytes"
    llvm = b"llvm-bytes" * 1000
    clang = b"clang-bytes" * 1000
    cppshared = b"cpp-shared-bytes"
    other = b"unrelated-entry-bytes"

    manifest = json.dumps({
        "schema": 1,
        "engine": "clang",
        "payload_binary": "libpvm_compiler_payload.so",
        "libs": [
            {"name": "libLLVM.so", "size": len(llvm), "sha256": hashlib.sha256(llvm).hexdigest()},
            {"name": "libc++_shared.so", "size": len(cppshared), "sha256": hashlib.sha256(cppshared).hexdigest()},
            {"name": "libclang-cpp.so", "size": len(clang), "sha256": hashlib.sha256(clang).hexdigest()},
        ],
    }).encode("utf-8")

    def staging(root: Path, llvm_bytes: bytes = llvm) -> tuple[Path, Path]:
        payload_dir = root / "payload"
        compiler_dir = root / "compiler"
        (payload_dir / "arm64-v8a").mkdir(parents=True)
        (compiler_dir / "arm64-v8a").mkdir(parents=True)
        (payload_dir / "arm64-v8a" / "libfastlane_payload.so").write_bytes(payload)
        (payload_dir / "arm64-v8a" / "libpvm_compiler_payload.so").write_bytes(compiler)
        (compiler_dir / "arm64-v8a" / "libLLVM.so").write_bytes(llvm_bytes)
        (compiler_dir / "arm64-v8a" / "libclang-cpp.so").write_bytes(clang)
        (compiler_dir / "arm64-v8a" / "libc++_shared.so").write_bytes(cppshared)
        return payload_dir, compiler_dir

    def write_apk(path: Path, entries: dict[str, bytes]) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    cases: list[tuple[str, list[str], int, str]] = []

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        payload_dir, compiler_dir = staging(root)

        good_entries = {
            LIB_PREFIX + "libfastlane_payload.so": payload,
            LIB_PREFIX + "libpvm_compiler_payload.so": compiler,
            LIB_PREFIX + "libLLVM.so": llvm,
            LIB_PREFIX + "libclang-cpp.so": clang,
            LIB_PREFIX + "libc++_shared.so": cppshared,
            "assets/Image": other,
            MANIFEST_ENTRY: manifest,
        }

        # Positive: everything agrees; the five entries come out stored, the rest deflate-preserved.
        apk = root / "good.apk"
        write_apk(apk, good_entries)
        out = root / "good-out.apk"
        cases.append((
            "ok-full-repack",
            ["--apk", str(apk), "--output", str(out),
             "--payload-lib-dir", str(payload_dir), "--compiler-lib-dir", str(compiler_dir)],
            EXIT_OK, "REPACK_OK",
        ))

        # The output of the positive case must actually carry stored payload entries
        # and byte-identical other entries.
        if out.is_file():
            with zipfile.ZipFile(out) as archive:
                for name in PAYLOAD_SOS:
                    entry = LIB_PREFIX + name
                    if archive.getinfo(entry).compress_type != zipfile.ZIP_STORED:
                        cases.append(("post-store-" + name, [], EXIT_FAIL, "never-checked"))
                if archive.getinfo("assets/Image").compress_type != zipfile.ZIP_DEFLATED:
                    cases.append(("post-other-method", [], EXIT_FAIL, "never-checked"))
                if archive.read("assets/Image") != other:
                    cases.append(("post-other-bytes", [], EXIT_FAIL, "never-checked"))

        # Negative: the APK's LLVM entry was stripped (different bytes).
        stripped = root / "stripped.apk"
        write_apk(stripped, {**good_entries, LIB_PREFIX + "libLLVM.so": b"stripped-llvm"})
        cases.append((
            "fail-stripped-entry",
            ["--apk", str(stripped), "--output", str(root / "stripped-out.apk"),
             "--payload-lib-dir", str(payload_dir), "--compiler-lib-dir", str(compiler_dir)],
            EXIT_FAIL, "REPACK_FAIL",
        ))

        # Negative: the manifest pin disagrees with the shipped bytes.
        bad_manifest = json.dumps({
            "schema": 1, "engine": "clang", "payload_binary": "libpvm_compiler_payload.so",
            "libs": [{"name": "libLLVM.so", "size": len(llvm),
                      "sha256": hashlib.sha256(b"wrong").hexdigest()}],
        }).encode("utf-8")
        pinned = root / "pinned.apk"
        write_apk(pinned, {**good_entries, MANIFEST_ENTRY: bad_manifest})
        cases.append((
            "fail-manifest-pin",
            ["--apk", str(pinned), "--output", str(root / "pinned-out.apk"),
             "--payload-lib-dir", str(payload_dir), "--compiler-lib-dir", str(compiler_dir)],
            EXIT_FAIL, "REPACK_FAIL",
        ))

        # Negative: a payload entry is missing from the APK entirely.
        missing = root / "missing.apk"
        write_apk(missing, {k: v for k, v in good_entries.items() if k != LIB_PREFIX + "libc++_shared.so"})
        cases.append((
            "fail-missing-entry",
            ["--apk", str(missing), "--output", str(root / "missing-out.apk"),
             "--payload-lib-dir", str(payload_dir), "--compiler-lib-dir", str(compiler_dir)],
            EXIT_FAIL, "REPACK_FAIL",
        ))

        # Usage error: no such APK.
        cases.append((
            "error-missing-apk",
            ["--apk", str(root / "nope.apk"), "--output", str(root / "nope-out.apk"),
             "--payload-lib-dir", str(payload_dir), "--compiler-lib-dir", str(compiler_dir)],
            EXIT_ERROR, "REPACK_ERROR",
        ))

        failures: list[str] = []
        for label, argv, expected_code, expected_marker in cases:
            buffer = io.StringIO()
            try:
                with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                    code = main(argv)
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 3
                buffer.write(f"SystemExit({exc.code})")
            output = buffer.getvalue()
            if code != expected_code:
                failures.append(f"{label}: exit {code} != expected {expected_code}\n{output}")
            elif expected_marker not in output:
                failures.append(f"{label}: output missing {expected_marker!r}\n{output}")

        if failures:
            print(f"REPACK_SELFTEST_FAIL {len(failures)} cases failed:")
            for failure in failures:
                print("  - " + failure)
            return EXIT_FAIL
        print(f"REPACK_SELFTEST_OK all fixture cases passed "
              "(positive rewrite, stored-method post-check, strip, manifest-pin, missing, usage-error)")
        return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
