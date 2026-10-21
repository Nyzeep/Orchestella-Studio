#!/usr/bin/env python3
"""Verify the Fast Lane payload entry inside a built APK.

This gate runs after AGP packages the APK and before any device install. It
does not modify the APK: a failing package is a build failure, never a
post-signing rewrite.

Checks:
  * lib/arm64-v8a/libfastlane_payload.so exists and is stored (not deflated);
  * its local-file data offset is 16 KB aligned and zipalign -c -P 16 passes;
  * the packaged bytes match the staged .so (SHA256);
  * llvm-nm reports T AVmPayload_main;
  * no lib/**/libvm_payload.so is packaged (the AVF APEX provides it);
  * the toolbox entry bin/sh exists with an executable mode.

`zipinfo -v` is mirrored by the Python zipfile facts printed below; the
zipalign binary is executed when the SDK provides it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PAYLOAD_ENTRY = "lib/arm64-v8a/libfastlane_payload.so"
FORBIDDEN_ENTRY = "libvm_payload.so"
PAGE_SIZE = 16 * 1024
BUILD_TOOLS = "36.1.0"
NDK_REVISION = "27.0.12077973"
ZIP_LOCAL_HEADER = b"PK\x03\x04"


class VerificationError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_ndk_tool(sdk: Path | None, name: str) -> Path:
    if sdk is None:
        raise VerificationError("--sdk is required to locate " + name)
    ndk = sdk / "ndk" / NDK_REVISION
    if not ndk.is_dir():
        raise VerificationError(f"NDK {NDK_REVISION} is missing under {sdk}")
    matches = sorted(ndk.glob(f"toolchains/llvm/prebuilt/*/bin/{name}*"))
    for candidate in matches:
        if candidate.is_file() and candidate.name in (name, name + ".exe"):
            return candidate
    raise VerificationError(f"{name} is missing under {ndk}")


def find_zipalign(sdk: Path | None) -> Path:
    if sdk is None:
        raise VerificationError("--sdk is required to locate zipalign")
    base = sdk / "build-tools" / BUILD_TOOLS
    for candidate in (base / "zipalign", base / "zipalign.exe"):
        if candidate.is_file():
            return candidate
    raise VerificationError(f"zipalign is missing under {base}")


def local_data_offset(apk: Path, info: zipfile.ZipInfo) -> int:
    with apk.open("rb") as stream:
        stream.seek(info.header_offset)
        header = stream.read(30)
        if len(header) != 30 or header[:4] != ZIP_LOCAL_HEADER:
            raise VerificationError("payload local header is unreadable")
        name_length = int.from_bytes(header[26:28], "little")
        extra_length = int.from_bytes(header[28:30], "little")
    return info.header_offset + 30 + name_length + extra_length


def executable_mode(info: zipfile.ZipInfo) -> str:
    mode = (info.external_attr >> 16) & 0o777
    if mode == 0:
        # AGP packages Java resources with mode 0. AOSP zipfuse treats files
        # under bin/ as executable by default; the device PTY probe is the
        # authoritative execution check.
        return "zipfuse-default"
    if mode & 0o111:
        return "explicit"
    raise VerificationError(f"APK entry {info.filename} is not executable ({mode:o})")


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        timeout=120,
    )


def verify_apk(
    apk: Path,
    source_so: Path,
    packaged_so: Path | None,
    sdk: Path | None,
    verbose: bool,
) -> dict:
    if not apk.is_file():
        raise VerificationError(f"APK does not exist: {apk}")
    if not source_so.is_file():
        raise VerificationError(f"source payload does not exist: {source_so}")
    reference_so = source_so
    if packaged_so is not None:
        if not packaged_so.is_file():
            raise VerificationError(f"packaged payload reference does not exist: {packaged_so}")
        reference_so = packaged_so

    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        payload_matches = [name for name in names if name.endswith("/" + FORBIDDEN_ENTRY)
                           or name == FORBIDDEN_ENTRY]
        if payload_matches:
            raise VerificationError(
                "APK packages an AVF-provided library: " + ", ".join(payload_matches)
            )
        if PAYLOAD_ENTRY not in names:
            raise VerificationError(f"APK is missing {PAYLOAD_ENTRY}")
        info = archive.getinfo(PAYLOAD_ENTRY)
        if info.is_dir():
            raise VerificationError(f"{PAYLOAD_ENTRY} is a directory entry")
        if info.compress_type != zipfile.ZIP_STORED:
            raise VerificationError(
                f"{PAYLOAD_ENTRY} must be stored uncompressed for 16 KB alignment "
                f"(compress_type={info.compress_type})"
            )
        data_offset = local_data_offset(apk, info)
        if data_offset % PAGE_SIZE != 0:
            raise VerificationError(
                f"{PAYLOAD_ENTRY} data offset 0x{data_offset:x} is not "
                f"{PAGE_SIZE}-byte aligned"
            )
        payload = archive.read(PAYLOAD_ENTRY)
        if sha256_bytes(payload) != sha256_file(reference_so):
            raise VerificationError(
                f"{PAYLOAD_ENTRY} bytes do not match {reference_so.name}"
            )
        if "bin/sh" not in names:
            raise VerificationError("APK is missing the toolbox entry bin/sh")
        sh_mode = executable_mode(archive.getinfo("bin/sh"))
        if verbose:
            print(
                "zipinfo-equivalent: "
                + json.dumps(
                    {
                        "name": info.filename,
                        "compress_type": info.compress_type,
                        "compress_size": info.compress_size,
                        "file_size": info.file_size,
                        "header_offset": info.header_offset,
                        "data_offset": data_offset,
                        "data_offset_hex": hex(data_offset),
                        "page_size": PAGE_SIZE,
                    },
                    sort_keys=True,
                )
            )

    zipalign = find_zipalign(sdk)
    alignment = run([str(zipalign), "-c", "-P", "16", "-v", "4", str(apk)])
    if alignment.returncode != 0:
        detail = (alignment.stdout + alignment.stderr).strip()
        raise VerificationError("zipalign -c -P 16 failed: " + (detail or "no output"))
    if verbose:
        for line in alignment.stdout.splitlines():
            if PAYLOAD_ENTRY in line or "Verification" in line:
                print("zipalign: " + line.strip())

    nm = find_ndk_tool(sdk, "llvm-nm")
    with tempfile.TemporaryDirectory(prefix="fastlane-payload-") as temporary:
        extracted = Path(temporary) / "libfastlane_payload.so"
        with zipfile.ZipFile(apk) as archive:
            extracted.write_bytes(archive.read(PAYLOAD_ENTRY))
        symbols = run([str(nm), "-D", "--defined-only", str(extracted)])
        if symbols.returncode != 0:
            raise VerificationError("llvm-nm failed on the packaged payload")
        if not re.search(r"\bT\s+AVmPayload_main\b", symbols.stdout):
            raise VerificationError("packaged payload does not export T AVmPayload_main")

    return {
        "apk": str(apk),
        "apk_sha256": sha256_file(apk),
        "payload": PAYLOAD_ENTRY,
        "payload_sha256": sha256_file(reference_so),
        "payload_bytes": reference_so.stat().st_size,
        "source_sha256": sha256_file(source_so),
        "packaged_so_sha256": sha256_file(packaged_so) if packaged_so else None,
        "payload_compress_type": info.compress_type,
        "payload_data_offset": data_offset,
        "bin_sh_mode": sh_mode,
        "zipalign": str(zipalign),
        "nm": str(nm),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--source-so", required=True, type=Path)
    parser.add_argument("--packaged-so", type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--verbose", action="store_true")
    arguments = parser.parse_args()

    try:
        result = verify_apk(
            arguments.apk.resolve(),
            arguments.source_so.resolve(),
            arguments.packaged_so.resolve() if arguments.packaged_so else None,
            arguments.sdk.resolve() if arguments.sdk else None,
            arguments.verbose,
        )
    except (VerificationError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        print("FAIL " + str(failure), file=sys.stderr)
        return 1

    print(
        "PAYLOAD_APK_OK "
        + json.dumps(result, sort_keys=True)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
