import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import fastlane_build  # noqa: E402

MANIFEST = REPO / "fastlane-toolbox" / "manifest.json"


class ToolboxTestBase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.out = self.root / "toolbox"
        self.cache = self.root / "cache"

    def tearDown(self):
        self.temp.cleanup()

    def build(self, **kwargs):
        return fastlane_build.build_toolbox(
            MANIFEST, self.out, cache_dir=self.cache, fixture=True, **kwargs
        )

    def replace_apk_entry(self, path, name, data):
        temporary = path.with_suffix(".rewrite")
        with zipfile.ZipFile(path) as source, zipfile.ZipFile(
            temporary, "w", zipfile.ZIP_DEFLATED
        ) as target:
            for info in source.infolist():
                target.writestr(
                    info, data if info.filename == name else source.read(info.filename)
                )
        os.replace(temporary, path)

    def make_apk(self, path, entries=None, signed=False):
        entries = entries or {
            "AndroidManifest.xml": b"\x03\x00\x08\x00",
            "classes.dex": b"dex\n035\x00",
        }
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
            if signed:
                archive.writestr("META-INF/CERT.RSA", b"not a real signature")
        return path


class BuildToolboxTest(ToolboxTestBase):
    def test_fixture_build_has_expected_layout_and_matching_manifest(self):
        manifest = self.build()
        bin_dir = self.out / "bin"
        self.assertTrue((bin_dir / "busybox").is_file())
        self.assertTrue((bin_dir / "sh").is_file())
        self.assertTrue((bin_dir / "ls").is_file())
        self.assertTrue((bin_dir / "manifest.json").is_file())
        self.assertEqual(manifest["profile"], "minimal")
        self.assertEqual(manifest["target"]["abi"], "arm64-v8a")
        self.assertTrue(manifest["fixture"])
        self.assertLess(manifest["totalSize"], 80 * 1024 * 1024)
        by_path = {entry["path"]: entry for entry in manifest["entries"]}
        for path, entry in by_path.items():
            target = self.out / path
            self.assertTrue(target.is_file(), path)
            self.assertEqual(fastlane_build.sha256_file(target), entry["sha256"], path)
            self.assertEqual(target.stat().st_size, entry["size"], path)
            self.assertTrue(os.access(target, os.X_OK), path)

    def test_fixture_shell_smoke(self):
        self.build()
        result = subprocess.run(
            [str(self.out / "bin" / "sh"), "-c", "echo FASTLANE_OK"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("FASTLANE_OK\n", result.stdout)

    def test_build_is_deterministic(self):
        first = self.build()
        second_out = self.root / "toolbox-2"
        second = fastlane_build.build_toolbox(
            MANIFEST, second_out, cache_dir=self.cache, fixture=True
        )
        self.assertEqual(
            json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True)
        )
        for entry in first["entries"]:
            left = (self.out / entry["path"]).read_bytes()
            right = (second_out / entry["path"]).read_bytes()
            self.assertEqual(left, right, entry["path"])

    def test_verify_toolbox_accepts_clean_build(self):
        self.build()
        report = fastlane_build.verify_toolbox(self.out)
        self.assertTrue(report["ok"])
        self.assertEqual(report["elf16k"], "none")
        self.assertEqual(report["symlinks"], 0)

    def test_verify_toolbox_rejects_sha_mismatch(self):
        self.build()
        with open(self.out / "bin" / "ls", "ab") as handle:
            handle.write(b"tamper")
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_toolbox(self.out)

    def test_verify_toolbox_rejects_missing_execute_bit(self):
        self.build()
        os.chmod(self.out / "bin" / "ls", 0o644)
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_toolbox(self.out)

    def test_verify_toolbox_rejects_symlinks_and_unlisted_files(self):
        self.build()
        os.symlink("busybox", self.out / "bin" / "evil")
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_toolbox(self.out)

    def test_verify_toolbox_rejects_manifest_missing(self):
        self.build()
        (self.out / "bin" / "manifest.json").unlink()
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_toolbox(self.out)

    def test_source_manifest_pins_busybox_license_and_source(self):
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        artifact = manifest["artifacts"][0]
        self.assertEqual("GPL-2.0-only", artifact["license"])
        self.assertEqual(64, len(artifact["sha256"]))
        self.assertEqual(64, len(artifact["binarySha256"]))
        self.assertTrue(artifact["source"].startswith("https://"))
        self.assertIn("correspondingSource", artifact)
        self.assertEqual("not-tested", manifest["pendingProfiles"]["distcc-clang"]["status"])
        self.assertIsNone(
            manifest["pendingProfiles"]["distcc-clang"]["requiredArtifacts"][0]["sha256"]
        )


class ElfAlignmentTest(unittest.TestCase):
    def test_parses_64k_aligned_elf(self):
        import struct

        header = bytearray(64)
        header[0:4] = b"\x7fELF"
        header[4] = 2
        header[5] = 1
        struct.pack_into("<H", header, 18, 183)
        struct.pack_into("<Q", header, 32, 64)
        struct.pack_into("<H", header, 54, 56)
        struct.pack_into("<H", header, 56, 1)
        program = bytearray(56)
        struct.pack_into("<I", program, 0, 1)
        struct.pack_into("<Q", program, 8, 0)
        struct.pack_into("<Q", program, 32, 0)
        struct.pack_into("<Q", program, 40, 0)
        struct.pack_into("<Q", program, 48, 0x10000)
        alignments = fastlane_build.elf_load_alignments(bytes(header + program))
        self.assertEqual([0x10000], alignments)

    def test_rejects_4k_elf(self):
        import struct

        header = bytearray(64)
        header[0:4] = b"\x7fELF"
        header[4] = 2
        header[5] = 1
        struct.pack_into("<H", header, 18, 183)
        struct.pack_into("<Q", header, 32, 64)
        struct.pack_into("<H", header, 54, 56)
        struct.pack_into("<H", header, 56, 1)
        program = bytearray(56)
        struct.pack_into("<I", program, 0, 1)
        struct.pack_into("<Q", program, 8, 0)
        struct.pack_into("<Q", program, 32, 0)
        struct.pack_into("<Q", program, 40, 0)
        struct.pack_into("<Q", program, 48, 0x1000)
        alignments = fastlane_build.elf_load_alignments(bytes(header + program))
        self.assertEqual([0x1000], alignments)


class InjectApkTest(ToolboxTestBase):
    def test_inject_adds_executable_bin_entries_before_signing(self):
        self.build()
        apk_in = self.make_apk(self.root / "unsigned.apk")
        apk_out = self.root / "injected.apk"
        count = fastlane_build.inject_apk(apk_in, self.out, apk_out)
        self.assertGreater(count, 0)
        self.assertFalse(fastlane_build.is_apk_signed(apk_out))
        with zipfile.ZipFile(apk_out) as archive:
            names = set(archive.namelist())
            self.assertIn("bin/busybox", names)
            self.assertIn("bin/sh", names)
            self.assertIn("bin/manifest.json", names)
            info = archive.getinfo("bin/busybox")
            self.assertEqual(stat.S_IFREG | 0o755, (info.external_attr >> 16) & 0o177777)
        report = fastlane_build.verify_apk(
            apk_out, self.out / "bin" / "manifest.json", budget_mb=80, allow_fixture=True
        )
        self.assertTrue(report["ok"])
        self.assertEqual("SKIP", report["zipalign"])

    def test_inject_refuses_signed_apk(self):
        self.build()
        signed = self.make_apk(self.root / "signed.apk", signed=True)
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.inject_apk(signed, self.out, self.root / "out.apk")

    def test_detects_v2_signature_block_magic(self):
        apk = self.make_apk(self.root / "v2.apk")
        with open(apk, "ab") as handle:
            handle.write(b"APK Sig Block 42")
        self.assertTrue(fastlane_build.is_apk_signed(apk))

    def test_inject_refuses_duplicate_bin_prefix(self):
        self.build()
        apk = self.make_apk(
            self.root / "duplicate.apk", entries={"bin/planted": b"x"}
        )
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.inject_apk(apk, self.out, self.root / "out.apk")

    def test_verify_apk_rejects_tampered_entry(self):
        self.build()
        apk = self.root / "injected.apk"
        fastlane_build.inject_apk(
            self.make_apk(self.root / "base.apk"), self.out, apk
        )
        self.replace_apk_entry(apk, "bin/busybox", b"tampered")
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_apk(
                apk, self.out / "bin" / "manifest.json", allow_fixture=True
            )

    def test_verify_apk_rejects_unexpected_bin_entry(self):
        self.build()
        apk = self.root / "injected.apk"
        fastlane_build.inject_apk(
            self.make_apk(self.root / "base.apk"), self.out, apk
        )
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("bin/planted", b"#!/system/bin/sh\n")
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_apk(
                apk, self.out / "bin" / "manifest.json", allow_fixture=True
            )

    def test_verify_apk_enforces_size_budget(self):
        self.build()
        apk = self.root / "injected.apk"
        fastlane_build.inject_apk(
            self.make_apk(self.root / "base.apk"), self.out, apk
        )
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_apk(
                apk, self.out / "bin" / "manifest.json", budget_mb=0.000001, allow_fixture=True
            )

    def test_verify_apk_runs_zipalign_check(self):
        self.build()
        apk = self.root / "injected.apk"
        fastlane_build.inject_apk(
            self.make_apk(self.root / "base.apk"), self.out, apk
        )
        fake_zipalign = self.root / "zipalign"
        fake_zipalign.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        fake_zipalign.chmod(0o755)
        report = fastlane_build.verify_apk(
            apk,
            self.out / "bin" / "manifest.json",
            zipalign=str(fake_zipalign),
            allow_fixture=True,
        )
        self.assertEqual("ok", report["zipalign"])

        failing = self.root / "zipalign-fail"
        failing.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        failing.chmod(0o755)
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.verify_apk(
                apk, self.out / "bin" / "manifest.json", zipalign=str(failing), allow_fixture=True
            )

    def test_cli_gate_returns_nonzero_on_tampered_apk(self):
        self.build()
        apk = self.root / "injected.apk"
        fastlane_build.inject_apk(
            self.make_apk(self.root / "base.apk"), self.out, apk
        )
        self.replace_apk_entry(apk, "bin/ls", b"tampered")
        result = subprocess.run(
            [
                sys.executable,
                str(HERE / "fastlane_build.py"),
                "verify-apk",
                "--apk",
                str(apk),
                "--runtime-manifest",
                str(self.out / "bin" / "manifest.json"),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("FAIL", result.stdout + result.stderr)


class CleanupTest(ToolboxTestBase):
    def test_build_output_contains_no_symlinks(self):
        self.build()
        for path in (self.out / "bin").iterdir():
            self.assertFalse(path.is_symlink(), path)

    def test_build_rejects_unsafe_entry_paths(self):
        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
        data["entries"].append(
            {"path": "bin/../escape", "type": "applet", "applet": "ls", "mode": "0755"}
        )
        bad = self.root / "bad.json"
        bad.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaises(fastlane_build.BuildError):
            fastlane_build.build_toolbox(bad, self.out, fixture=True)


if __name__ == "__main__":
    unittest.main()
