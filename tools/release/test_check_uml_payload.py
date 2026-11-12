#!/usr/bin/env python3
"""Unit tests for check_uml_payload.py: the UML gate's skip/fail contract.

Mirrors tools/release/test_check_runtime_payload.py (the repo's gate-test
paradigm): synthetic zip-as-APK fixtures built in a temporary directory, no
binary fixtures, no network. The gate itself also carries an end-to-end
`--selftest` for standalone use on the packaging machine; this file pins the
same contract case by case for CI (tools/ci's unittest discover picks it up).
"""

from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_uml_payload as gate  # noqa: E402

MANIFEST_ENTRY = gate.MANIFEST_ENTRY
KERNEL_ENTRY = gate.LIB_DIR + "libuml_kernel.so"
STUB_ENTRY = gate.LIB_DIR + "libuml_stub.so"
ROOTFS_ENTRY = "alpine-minirootfs-3.22.2-aarch64.tar.gz"


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ParseManifestTest(unittest.TestCase):
    def test_reads_packaged_names_and_ignores_blank_and_comment_lines(self) -> None:
        text = (
            "# uml-fast-profile-v1\n"
            f"{_sha(b'kernel')}  libuml_kernel.so\n"
            f"{_sha(b'stub')}  libuml_stub.so\n"
            "\n"
        )
        digests, problems = gate.parse_manifest(text)
        self.assertEqual([], problems)
        self.assertEqual(_sha(b"kernel"), digests["libuml_kernel.so"])
        self.assertEqual(_sha(b"stub"), digests["libuml_stub.so"])
        self.assertEqual([], problems)

    def test_unparsable_lines_are_findings_not_noise(self) -> None:
        digests, problems = gate.parse_manifest("deadbeef  libuml_kernel.so\n")
        self.assertEqual({}, digests)
        self.assertEqual(1, len(problems))
        self.assertIn("unparsable manifest line", problems[0].reason)

    def test_duplicate_name_with_conflicting_digest_is_a_finding(self) -> None:
        text = (
            f"{_sha(b'a')}  libuml_kernel.so\n"
            f"{_sha(b'b')}  libuml_kernel.so\n"
        )
        digests, problems = gate.parse_manifest(text)
        self.assertEqual(_sha(b"b"), digests["libuml_kernel.so"])
        self.assertEqual(1, len(problems))
        self.assertIn("twice with different digests", problems[0].reason)


class ApkEntryMappingTest(unittest.TestCase):
    def test_native_libraries_map_under_lib_arm64(self) -> None:
        self.assertEqual("lib/arm64-v8a/libuml_kernel.so", gate.apk_entry_for("libuml_kernel.so"))
        self.assertEqual("lib/arm64-v8a/libuml_stub.so", gate.apk_entry_for("libuml_stub.so"))

    def test_other_accounted_artifacts_map_to_the_apk_root(self) -> None:
        # The java-resource-at-the-root precedent (initramfs.cpio.gz in the QEMU gate).
        self.assertEqual(ROOTFS_ENTRY, gate.apk_entry_for(ROOTFS_ENTRY))


class CheckApkTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.kernel = b"uml-kernel-bytes"
        self.stub = b"uml-stub-bytes"
        self.rootfs = b"rootfs-tar-bytes"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _apk(self, entries: dict[str, bytes]) -> Path:
        apk = self.root / "app-release.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)
        return apk

    def _manifest(self, with_rootfs: bool = True) -> bytes:
        lines = [
            f"{_sha(self.kernel)}  libuml_kernel.so",
            f"{_sha(self.stub)}  libuml_stub.so",
        ]
        if with_rootfs:
            lines.append(f"{_sha(self.rootfs)}  {ROOTFS_ENTRY}")
        return ("".join(line + "\n" for line in lines)).encode("utf-8")

    def test_full_payload_passes_and_lists_every_verified_entry(self) -> None:
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            ROOTFS_ENTRY: self.rootfs,
            MANIFEST_ENTRY: self._manifest(),
        }), require_payload=False)
        self.assertEqual("ok", result.status)
        self.assertEqual([], result.problems)
        self.assertEqual(
            {KERNEL_ENTRY, STUB_ENTRY, ROOTFS_ENTRY},
            {item.entry for item in result.verified},
        )

    def test_digest_mismatch_fails_with_both_sides_of_the_diff(self) -> None:
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: b"not-the-staged-kernel",
            STUB_ENTRY: self.stub,
            MANIFEST_ENTRY: self._manifest(with_rootfs=False),
        }), require_payload=False)
        self.assertEqual("fail", result.status)
        self.assertEqual(1, len(result.problems))
        self.assertEqual(KERNEL_ENTRY, result.problems[0].entry)
        self.assertIn("!=", result.problems[0].reason)

    def test_accounted_but_missing_entry_fails(self) -> None:
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            MANIFEST_ENTRY: self._manifest(with_rootfs=False),
        }), require_payload=False)
        self.assertEqual("fail", result.status)
        self.assertEqual(STUB_ENTRY, result.problems[0].entry)
        self.assertIn("missing from the APK", result.problems[0].reason)

    def test_incomplete_manifest_is_a_finding(self) -> None:
        # stageUmlFastProfile always stages both libraries; a manifest without
        # the kernel line means the accounting itself drifted.
        incomplete = f"{_sha(self.stub)}  libuml_stub.so\n".encode("utf-8")
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            MANIFEST_ENTRY: incomplete,
        }), require_payload=False)
        self.assertEqual("fail", result.status)
        self.assertTrue(any("libuml_kernel.so" in p.entry for p in result.problems))

    def test_missing_manifest_skips_in_auto_mode(self) -> None:
        result = gate.check_apk(self._apk({KERNEL_ENTRY: b"unrelated"}), require_payload=False)
        self.assertEqual("skip", result.status)
        self.assertIn("MISSING_ASSET", result.reason)

    def test_missing_manifest_fails_in_require_mode(self) -> None:
        result = gate.check_apk(self._apk({KERNEL_ENTRY: b"unrelated"}), require_payload=True)
        self.assertEqual("fail", result.status)
        self.assertIn("--require-payload", result.problems[0].reason)
        self.assertIn("fastProfileDir", result.problems[0].reason)


class CommandLineTest(unittest.TestCase):
    def test_exit_codes_skip_json_and_require_mode(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            kernel, stub = b"k", b"s"
            manifest = root / "m.sha256"
            manifest.write_text(
                f"{_sha(kernel)}  libuml_kernel.so\n{_sha(stub)}  libuml_stub.so\n",
                encoding="utf-8",
            )

            payload_apk = root / "payload.apk"
            with zipfile.ZipFile(payload_apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, kernel)
                archive.writestr(STUB_ENTRY, stub)
                archive.writestr(MANIFEST_ENTRY, manifest.read_text(encoding="utf-8"))

            out = root / "r.json"
            self.assertEqual(0, gate.main(["--apk", str(payload_apk), "--json", str(out)]))
            self.assertTrue(out.is_file())
            self.assertIn('"status": "ok"', out.read_text(encoding="utf-8"))

            self.assertEqual(0, gate.main(["--apk", str(payload_apk), "--require-payload"]))

            exempt_apk = root / "exempt.apk"
            with zipfile.ZipFile(exempt_apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, b"unrelated")
            self.assertEqual(0, gate.main(["--apk", str(exempt_apk)]))
            self.assertEqual(1, gate.main(["--apk", str(exempt_apk), "--require-payload"]))

    def test_mismatch_exits_nonzero_and_a_broken_zip_is_a_usage_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / "mismatch.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, b"other-bytes")
                archive.writestr(STUB_ENTRY, b"s")
                archive.writestr(MANIFEST_ENTRY,
                                 f"{_sha(b'k')}  libuml_kernel.so\n{_sha(b's')}  libuml_stub.so\n")
            self.assertEqual(1, gate.main(["--apk", str(apk)]))

            broken = root / "broken.apk"
            broken.write_bytes(b"not a zip")
            self.assertEqual(2, gate.main(["--apk", str(broken)]))
            self.assertEqual(2, gate.main(["--apk", str(root / "absent.apk")]))

    def test_selftest_passes(self) -> None:
        self.assertEqual(0, gate.main(["--selftest"]))


if __name__ == "__main__":
    unittest.main()
