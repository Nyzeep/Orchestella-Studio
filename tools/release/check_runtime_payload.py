#!/usr/bin/env python3
"""Release gate: the APK must actually carry the QEMU runtime payload.

Why this exists
---------------
0.6.0 shipped from a worktree whose `app/src/main/jniLibs/` had been emptied into
`artifacts/qa-jniLibs-4k-arm64/` (session 25). The build stayed green, `verify_apk.py`
stayed green, and the published APK contained `assets/Image` plus the guest initramfs
but **no `libdroidlayer_qemu.so`**. Every user who installed it got "Linux 实例不可用":
`NativeExecutableStore.ensure()` throws `missing from both nativeLibraryDir and the APK`
before QEMU is ever spawned.

The payload is gitignored on purpose (132 MB binary, licensing), so a clean checkout —
including CI — builds a package that cannot boot. This gate is the missing piece: it
reads `assets/runtime-artifacts-v1.sha256` and proves, byte for byte, that the three
runtime-critical entries are present in the APK. Run it before uploading anything.

Standard library only; no network.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

DEFAULT_MANIFEST = Path("app/src/main/assets/runtime-artifacts-v1.sha256")

# APK entry -> manifest key. The kernel and the notices ship under assets/ (AssetManager),
# the initramfs ships as a java resource at the APK root (the classloader reads it, and
# aapt must not rename its *.gz), the native library ships under lib/<abi>/.
REQUIRED_ENTRIES: tuple[tuple[str, str], ...] = (
    ("assets/Image", "Image"),
    ("initramfs.cpio.gz", "initramfs.cpio.gz"),
    ("lib/arm64-v8a/libdroidlayer_qemu.so", "lib/arm64-v8a/libdroidlayer_qemu.so"),
)

# Needed for port forwarding; the guest still boots without it, so it is reported as a
# warning rather than a hard failure.
AUXILIARY_ENTRIES: tuple[tuple[str, str], ...] = (
    ("lib/arm64-v8a/libgvproxy.so", ""),
)


@dataclass(frozen=True)
class Problem:
    entry: str
    reason: str


def parse_manifest(text: str) -> dict[str, str]:
    """`<sha256>  <name>` lines -> {name: digest}; blank lines and comments ignored."""
    digests: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        digest, name = parts[0].lower(), parts[-1]
        if len(digest) != 64:
            continue
        digests[name] = digest
    return digests


def sha256_of(zip_file: zipfile.ZipFile, entry: str) -> str | None:
    if entry not in zip_file.namelist():
        return None
    digest = hashlib.sha256()
    with zip_file.open(entry) as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_apk(apk: Path, manifest: dict[str, str]) -> tuple[list[Problem], list[Problem]]:
    """(hard problems, warnings)."""
    problems: list[Problem] = []
    warnings: list[Problem] = []
    with zipfile.ZipFile(apk) as archive:
        for entry, key in REQUIRED_ENTRIES:
            actual = sha256_of(archive, entry)
            if actual is None:
                problems.append(Problem(entry, "missing from the APK"))
                continue
            expected = manifest.get(key)
            if expected is None:
                problems.append(Problem(entry, f"no digest for {key!r} in the manifest"))
            elif actual != expected:
                problems.append(Problem(entry, f"sha256 {actual[:16]}… != manifest {expected[:16]}…"))
        for entry, _ in AUXILIARY_ENTRIES:
            if sha256_of(archive, entry) is None:
                warnings.append(Problem(entry, "missing from the APK (port forwarding will fail)"))
    return problems, warnings


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="APK to inspect")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST,
                        help=f"runtime artifact manifest (default: {DEFAULT_MANIFEST})")
    parser.add_argument("--json", type=Path, help="write a machine-readable result")
    parser.add_argument("--allow-missing", action="store_true",
                        help="report payload problems but exit 0 (internal/QA builds only)")
    args = parser.parse_args(argv)

    if not args.apk.is_file():
        print(f"PAYLOAD_GATE_ERROR apk not found: {args.apk}", file=sys.stderr)
        return 2
    if not args.manifest.is_file():
        print(f"PAYLOAD_GATE_ERROR manifest not found: {args.manifest}", file=sys.stderr)
        return 2

    manifest = parse_manifest(args.manifest.read_text(encoding="utf-8"))
    try:
        problems, warnings = check_apk(args.apk, manifest)
    except zipfile.BadZipFile as exc:
        print(f"PAYLOAD_GATE_ERROR not a zip/apk: {exc}", file=sys.stderr)
        return 2

    for warning in warnings:
        print(f"PAYLOAD_GATE_WARN {warning.entry}: {warning.reason}")
    for problem in problems:
        print(f"PAYLOAD_GATE_FAIL {problem.entry}: {problem.reason}")

    ok = not problems
    if ok:
        print("PAYLOAD_GATE_OK kernel, initramfs and the QEMU native library are present and match the manifest")
    else:
        print(
            "PAYLOAD_GATE_HINT the QEMU payload is gitignored, so a clean checkout cannot build a bootable APK. "
            "Restore lib/arm64-v8a/{libdroidlayer_qemu,libgvproxy}.so into app/src/main/jniLibs/ "
            "(see artifacts/qa-jniLibs-4k-arm64/README.md) and rebuild before publishing.",
            file=sys.stderr,
        )

    if args.json:
        args.json.write_text(
            json.dumps(
                {
                    "apk": str(args.apk),
                    "ok": ok,
                    "problems": [{"entry": p.entry, "reason": p.reason} for p in problems],
                    "warnings": [{"entry": w.entry, "reason": w.reason} for w in warnings],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    if ok or args.allow_missing:
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
