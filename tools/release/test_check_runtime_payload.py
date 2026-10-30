#!/usr/bin/env python3
"""Unit tests for check_runtime_payload.py: a payload-less APK must fail the gate."""

from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_runtime_payload as gate  # noqa: E402


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ParseManifestTest(unittest.TestCase):
    def test_reads_digest_and_name_and_skips_noise(self) -> None:
        text = (
            "# generated\n"
            f"{_sha(b'kernel')}  Image\n"
            f"{'A' * 64}  initramfs.cpio.gz\n"
            "\n"
            "not a manifest line\n"
        )
        parsed = gate.parse_manifest(text)
        self.assertEqual(_sha(b"kernel"), parsed["Image"])
        self.assertEqual("a" * 64, parsed["initramfs.cpio.gz"])
        self.assertEqual(2, len(parsed))


class CheckApkTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.kernel = b"kernel-bytes"
        self.initramfs = b"initramfs-bytes"
        self.qemu = b"qemu-bytes"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _apk(self, *, with_qemu: bool = True, with_gvproxy: bool = True, qemu_bytes: bytes | None = None) -> Path:
        apk = self.root / "app-release.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("assets/Image", self.kernel)
            archive.writestr("initramfs.cpio.gz", self.initramfs)
            if with_qemu:
                archive.writestr("lib/arm64-v8a/libdroidlayer_qemu.so", qemu_bytes if qemu_bytes is not None else self.qemu)
            if with_gvproxy:
                archive.writestr("lib/arm64-v8a/libgvproxy.so", b"gvproxy-bytes")
        return apk

    def _manifest(self) -> dict[str, str]:
        return {
            "Image": _sha(self.kernel),
            "initramfs.cpio.gz": _sha(self.initramfs),
            "lib/arm64-v8a/libdroidlayer_qemu.so": _sha(self.qemu),
        }

    def test_complete_payload_passes(self) -> None:
        problems, warnings = gate.check_apk(self._apk(), self._manifest())
        self.assertEqual([], problems)
        self.assertEqual([], warnings)

    def test_payload_less_apk_is_the_0_6_0_incident(self) -> None:
        problems, _ = gate.check_apk(self._apk(with_qemu=False), self._manifest())
        self.assertEqual(1, len(problems))
        self.assertEqual("lib/arm64-v8a/libdroidlayer_qemu.so", problems[0].entry)
        self.assertIn("missing from the APK", problems[0].reason)

    def test_payload_from_a_different_build_is_rejected(self) -> None:
        problems, _ = gate.check_apk(self._apk(qemu_bytes=b"older-qemu"), self._manifest())
        self.assertEqual(1, len(problems))
        self.assertIn("!=", problems[0].reason)

    def test_missing_gvproxy_only_warns(self) -> None:
        problems, warnings = gate.check_apk(self._apk(with_gvproxy=False), self._manifest())
        self.assertEqual([], problems)
        self.assertEqual("lib/arm64-v8a/libgvproxy.so", warnings[0].entry)


class CommandLineTest(unittest.TestCase):
    def test_exit_codes_and_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / "a.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/Image", b"k")
            manifest = root / "m.sha256"
            manifest.write_text(f"{_sha(b'k')}  Image\n", encoding="utf-8")

            out = root / "r.json"
            self.assertEqual(0, gate.main(["--apk", str(apk), "--manifest", str(manifest),
                                           "--json", str(out), "--allow-missing"]))
            self.assertTrue(out.is_file())

            self.assertEqual(1, gate.main(["--apk", str(apk), "--manifest", str(manifest)]))

    def test_missing_files_are_usage_errors(self) -> None:
        self.assertEqual(2, gate.main(["--apk", "does-not-exist.apk"]))
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "a.apk"
            apk.write_bytes(b"not a zip")
            manifest = Path(tmp) / "m.sha256"
            manifest.write_text("", encoding="utf-8")
            self.assertEqual(2, gate.main(["--apk", str(apk), "--manifest", str(manifest)]))


if __name__ == "__main__":
    unittest.main()
