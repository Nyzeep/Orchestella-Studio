#!/usr/bin/env python3
"""Prove that the zero-device Fast Lane gates fail closed.

This is a CI self-test, not a build. It deliberately tampers with a synthetic
APK and asserts that the verifier and the injector refuse to proceed.
"""

from __future__ import annotations

import json
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import check_licenses  # noqa: E402
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


def expect_problem(label: str, action) -> None:
    problems = action()
    if not problems:
        raise SystemExit(f"FAIL gate did not report a problem: {label}")
    print(f"BLOCKED {label}: {problems[0]}")


def remove_entry(source: Path, target: Path, name: str) -> None:
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(
        target, "w", zipfile.ZIP_DEFLATED
    ) as output:
        for info in archive.infolist():
            if info.filename != name:
                output.writestr(info, archive.read(info.filename))


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

        missing = root / "missing.apk"
        remove_entry(injected, missing, "bin/ls")
        expect_block(
            "missing-bin-entry",
            lambda: fastlane_build.verify_apk(
                missing, toolbox / "bin" / "manifest.json", allow_fixture=True
            ),
        )

        traversal = root / "traversal.apk"
        traversal.write_bytes(injected.read_bytes())
        with zipfile.ZipFile(traversal, "a") as archive:
            archive.writestr("bin/../escape", b"x")
        expect_block(
            "apk-path-traversal",
            lambda: fastlane_build.verify_apk(
                traversal, toolbox / "bin" / "manifest.json", allow_fixture=True
            ),
        )

        tampered_toolbox = root / "tampered-toolbox"
        fastlane_build.build_toolbox(
            REPO / "fastlane-toolbox" / "manifest.json", tampered_toolbox, fixture=True
        )
        with open(tampered_toolbox / "bin" / "sh", "ab") as handle:
            handle.write(b"tamper")
        expect_block("toolbox-sha", lambda: fastlane_build.verify_toolbox(tampered_toolbox))
        budget_toolbox = root / "budget-toolbox"
        fastlane_build.build_toolbox(
            REPO / "fastlane-toolbox" / "manifest.json", budget_toolbox, fixture=True
        )
        expect_block(
            "toolbox-size-budget",
            lambda: fastlane_build.verify_toolbox(budget_toolbox, budget_mb=0.000001),
        )

        source_manifest = json.loads(
            (REPO / "fastlane-toolbox" / "manifest.json").read_text(encoding="utf-8")
        )
        no_license = json.loads(json.dumps(source_manifest))
        del no_license["artifacts"][0]["license"]
        no_license_path = root / "no-license.json"
        no_license_path.write_text(json.dumps(no_license), encoding="utf-8")
        expect_problem(
            "license-missing",
            lambda: check_licenses.check(
                no_license_path,
                REPO / "NOTICE-fastlane.md",
                REPO / "fastlane-toolbox" / "SBOM.spdx.json",
            ),
        )

        unsafe = json.loads(json.dumps(source_manifest))
        unsafe["entries"].append(
            {"path": "bin/../escape", "type": "applet", "applet": "ls", "mode": "0755"}
        )
        unsafe_path = root / "unsafe.json"
        unsafe_path.write_text(json.dumps(unsafe), encoding="utf-8")
        expect_block(
            "manifest-path-traversal",
            lambda: fastlane_build.build_toolbox(
                unsafe_path, root / "unsafe-toolbox", fixture=True
            ),
        )
    print("GATE_BLOCK_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
