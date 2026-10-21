#!/usr/bin/env python3
"""Prove that the zero-device Fast Lane gates fail closed.

This is a CI self-test, not a build. It deliberately tampers with a synthetic
APK and asserts that the verifier and the injector refuse to proceed.
"""

from __future__ import annotations

import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import fastlane_build  # noqa: E402


def make_apk(path: Path) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
        archive.writestr("classes.dex", b"dex\n035\x00")


def replace_entry(path: Path, name: str, data: bytes) -> None:
    temporary = path.with_suffix(".rewrite")
    with zipfile.ZipFile(path) as source, zipfile.ZipFile(
        temporary, "w", zipfile.ZIP_DEFLATED
    ) as target:
        for info in source.infolist():
            target.writestr(
                info, data if info.filename == name else source.read(info.filename)
            )
    path.unlink()
    temporary.rename(path)


def expect_block(label: str, action) -> None:
    try:
        action()
    except fastlane_build.BuildError as failure:
        print(f"BLOCKED {label}: {failure}")
        return
    raise SystemExit(f"FAIL gate did not block: {label}")


def main() -> int:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        toolbox = root / "toolbox"
        fastlane_build.build_toolbox(
            REPO / "fastlane-toolbox" / "manifest.json", toolbox, fixture=True
        )
        unsigned = root / "unsigned.apk"
        make_apk(unsigned)
        injected = root / "injected.apk"
        fastlane_build.inject_apk(unsigned, toolbox, injected)

        tampered = root / "tampered.apk"
        tampered.write_bytes(injected.read_bytes())
        replace_entry(tampered, "bin/sh", b"tampered")
        expect_block(
            "tampered-bin-sha",
            lambda: fastlane_build.verify_apk(
                tampered, toolbox / "bin" / "manifest.json", allow_fixture=True
            ),
        )

        signed = root / "signed.apk"
        with zipfile.ZipFile(signed, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
            archive.writestr("META-INF/CERT.RSA", b"not a signature")
        expect_block(
            "signed-apk-injection",
            lambda: fastlane_build.inject_apk(signed, toolbox, root / "out.apk"),
        )

        oversized = root / "oversized.apk"
        oversized.write_bytes(injected.read_bytes())
        expect_block(
            "size-budget",
            lambda: fastlane_build.verify_apk(
                oversized,
                toolbox / "bin" / "manifest.json",
                budget_mb=0.000001,
                allow_fixture=True,
            ),
        )
    print("GATE_BLOCK_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
