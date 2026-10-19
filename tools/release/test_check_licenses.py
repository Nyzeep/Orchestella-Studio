#!/usr/bin/env python3
"""Tests for tools/release/check_licenses.py (standard library only)."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().with_name("check_licenses.py")

NOTICE_QEMU = (
    "See guest-assets/SOURCE-OFFER.txt, docs/adr/0014-licensing.md section 3 "
    "and tools/qemu-android/gpl/QEMU-GPL-SOURCES.md.\n"
)
NOTICE_GUEST = (
    "The agent is statically linked with musl 1.2.6-r2.\n"
    "Musl pin: tools/phase5/guest-agent-inputs.lock.json.\n"
    "The initramfs uses guest-assets/inputs/inputs.lock with "
    "MUSL_VERSION=1.2.5.\n"
)
GPL_SOURCES = (
    "## musl and compiler runtime\n"
    "\n"
    "- Version: `1.2.5`\n"
    "- Corresponding source: https://musl.libc.org/releases/musl-1.2.5.tar.gz\n"
    "\n"
    "Project code is GPL-3.0-or-later. Inputs: "
    "guest-assets/inputs/inputs.lock.\n"
)
AGENT_LOCK = {
    "schema": 1,
    "provenance": {
        "packages": [
            {"name": "musl", "version": "1.2.6-r2"},
            {"name": "musl-dev", "version": "1.2.6-r2"},
        ]
    },
}
MANIFEST_FILES = [
    "LICENSE",
    "app/src/main/assets/NOTICE.QEMU",
    "app/src/main/assets/NOTICE.GUEST-RUNTIME",
    "app/src/main/assets/QEMU-GPL-SOURCES.md",
    "app/src/main/assets/QEMU-BUILD-INPUTS",
    "app/src/main/assets/LICENSE.GPL-2.0",
    "guest-assets/GPL-SOURCES.md",
    "guest-assets/SOURCE-OFFER.txt",
    "guest-assets/inputs/inputs.lock",
    "tools/qemu-android/gpl/QEMU-GPL-SOURCES.md",
    "tools/qemu-android/gpl/QEMU-BUILD-INPUTS",
    "tools/qemu-android/gpl/LICENSE.GPL-2.0",
    "tools/qemu-android/gpl/NOTICE.QEMU",
]


def write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class LicenseCheckTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        write(
            self.root,
            "LICENSE",
            "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n",
        )
        write(self.root, "app/src/main/assets/NOTICE.QEMU", NOTICE_QEMU)
        write(self.root, "app/src/main/assets/NOTICE.GUEST-RUNTIME", NOTICE_GUEST)
        write(self.root, "app/src/main/assets/QEMU-GPL-SOURCES.md", NOTICE_QEMU)
        write(
            self.root,
            "app/src/main/assets/NOTICE.PHASE5",
            "Original project modules are GPL-3.0-or-later.\n",
        )
        write(self.root, "app/src/main/assets/QEMU-BUILD-INPUTS", "schema=1\n")
        write(self.root, "app/src/main/assets/LICENSE.GPL-2.0", "GPLv2\n")
        write(self.root, "guest-assets/GPL-SOURCES.md", GPL_SOURCES)
        write(self.root, "guest-assets/SOURCE-OFFER.txt", "See GPL-SOURCES.md.\n")
        write(
            self.root,
            "guest-assets/inputs/inputs.lock",
            "MUSL_VERSION=1.2.5\n",
        )
        write(
            self.root,
            "tools/phase5/guest-agent-inputs.lock.json",
            json.dumps(AGENT_LOCK),
        )
        write(
            self.root,
            "tools/qemu-android/gpl/QEMU-GPL-SOURCES.md",
            NOTICE_QEMU,
        )
        write(self.root, "tools/qemu-android/gpl/QEMU-BUILD-INPUTS", "schema=1\n")
        write(self.root, "tools/qemu-android/gpl/LICENSE.GPL-2.0", "GPLv2\n")
        write(self.root, "tools/qemu-android/gpl/NOTICE.QEMU", NOTICE_QEMU)
        write(self.root, "docs/adr/0014-licensing.md", "# ADR-0014\n")
        write(
            self.root,
            "guest-assets/display/display-relay.py",
            "# SPDX-License-Identifier: GPL-3.0-or-later\n",
        )
        write(
            self.root,
            "guest-assets/initramfs/init",
            "# SPDX-License-Identifier: GPL-3.0-or-later\n",
        )
        write(
            self.root,
            "tools/release/release_bundle_manifest.json",
            json.dumps({"schema": 1, "files": MANIFEST_FILES}),
        )

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_check(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(self.root)],
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_failure(self, result: subprocess.CompletedProcess[str], text: str) -> None:
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(text, result.stdout)

    def test_valid_fixture_passes(self) -> None:
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("SUMMARY:", result.stdout)
        self.assertIn("0 failed", result.stdout)

    def test_notice_musl_mismatch_fails(self) -> None:
        path = self.root / "app/src/main/assets/NOTICE.GUEST-RUNTIME"
        path.write_text(NOTICE_GUEST.replace("1.2.6-r2", "1.2.5"), encoding="utf-8")
        self.assert_failure(
            self.run_check(),
            "FAIL  NOTICE.GUEST-RUNTIME musl package matches the guest-agent lock",
        )

    def test_gpl_sources_musl_mismatch_fails(self) -> None:
        path = self.root / "guest-assets/GPL-SOURCES.md"
        path.write_text(
            GPL_SOURCES.replace("1.2.5", "1.2.6"), encoding="utf-8"
        )
        self.assert_failure(
            self.run_check(),
            "FAIL  GPL-SOURCES.md initramfs musl matches inputs.lock",
        )

    def test_broken_reference_fails(self) -> None:
        path = self.root / "app/src/main/assets/NOTICE.QEMU"
        path.write_text(
            NOTICE_QEMU + "guest-assets/does-not-exist.txt\n", encoding="utf-8"
        )
        self.assert_failure(
            self.run_check(),
            "guest-assets/does-not-exist.txt",
        )

    def test_missing_required_file_fails(self) -> None:
        (self.root / "guest-assets/SOURCE-OFFER.txt").unlink()
        self.assert_failure(
            self.run_check(),
            "FAIL  required file exists: guest-assets/SOURCE-OFFER.txt",
        )

    def test_manifest_missing_notice_fails(self) -> None:
        manifest_path = self.root / "tools/release/release_bundle_manifest.json"
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
        data["files"] = [
            rel
            for rel in data["files"]
            if not rel.endswith("NOTICE.QEMU")
        ]
        manifest_path.write_text(json.dumps(data), encoding="utf-8")
        self.assert_failure(
            self.run_check(),
            "FAIL  release bundle manifest includes NOTICE.QEMU",
        )

    def test_manifest_entry_missing_fails(self) -> None:
        manifest_path = self.root / "tools/release/release_bundle_manifest.json"
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
        data["files"].append("guest-assets/missing.bin")
        manifest_path.write_text(json.dumps(data), encoding="utf-8")
        self.assert_failure(
            self.run_check(),
            "FAIL  every release bundle manifest file exists",
        )

    def test_apache_spdx_in_project_source_fails(self) -> None:
        path = self.root / "guest-assets/display/display-relay.py"
        path.write_text("# SPDX-License-Identifier: Apache-2.0\n", encoding="utf-8")
        self.assert_failure(
            self.run_check(),
            "FAIL  project source carries GPL-3.0-or-later SPDX: guest-assets/display/display-relay.py",
        )

    def test_non_gpl3_license_fails(self) -> None:
        (self.root / "LICENSE").write_text(
            "Apache License\nVersion 2.0\n", encoding="utf-8"
        )
        self.assert_failure(
            self.run_check(),
            "FAIL  root LICENSE is the GNU GPL version 3 text",
        )


if __name__ == "__main__":
    unittest.main()
