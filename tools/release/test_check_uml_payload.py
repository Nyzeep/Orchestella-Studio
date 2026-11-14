#!/usr/bin/env python3
"""Unit tests for check_uml_payload.py: the single-package UML gate's contract.

The gate was rewritten for the 2026-11-14 single-package round: two accounting
manifests (fast profile + net helper), the --fullpkg single-package smoke
(UML four libraries + embedded rootfs tar + pVM compiler manifest pins + QEMU
manifest + NOTICE.UML), and the both-manifests hard requirement in
--require-payload/--fullpkg modes. This file pins that contract case by case
for CI (tools/release unittest discover); it mirrors
tools/release/test_check_runtime_payload.py's paradigm — synthetic zip-as-APK
fixtures built in a temporary directory, no binary fixtures, no network. The
gate itself also carries an end-to-end `--selftest` for standalone use on the
packaging machine; the two must stay aligned (this file cites the selftest
case names it mirrors).
"""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_uml_payload as gate  # noqa: E402

FAST_MANIFEST = gate.FAST_MANIFEST_ENTRY
NET_MANIFEST = gate.NET_HELPER_MANIFEST_ENTRY
PVM_MANIFEST = gate.PVM_MANIFEST_ENTRY
QEMU_MANIFEST = gate.QEMU_MANIFEST_ENTRY
LIB = gate.LIB_DIR
KERNEL_ENTRY = LIB + "libuml_kernel.so"
STUB_ENTRY = LIB + "libuml_stub.so"
NET_ENTRY = LIB + "libuml_net.so"
PASST_ENTRY = LIB + "libuml_passt.so"
ROOTFS_ENTRY = gate.FULLPKG_ROOTFS_TAR
QEMU_LIB_ENTRY = LIB + "libdroidlayer_qemu.so"
NOTICE_UML_ENTRY = "assets/NOTICE.UML"


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
        digests, problems = gate.parse_manifest(text, FAST_MANIFEST)
        self.assertEqual([], problems)
        self.assertEqual(_sha(b"kernel"), digests["libuml_kernel.so"])
        self.assertEqual(_sha(b"stub"), digests["libuml_stub.so"])

    def test_unparsable_lines_are_findings_not_noise(self) -> None:
        digests, problems = gate.parse_manifest(
            "deadbeef  libuml_kernel.so\n", FAST_MANIFEST)
        self.assertEqual({}, digests)
        self.assertEqual(1, len(problems))
        self.assertIn("unparsable manifest line", problems[0].reason)
        self.assertEqual(FAST_MANIFEST, problems[0].entry)

    def test_duplicate_name_with_conflicting_digest_is_a_finding(self) -> None:
        text = (
            f"{_sha(b'a')}  libuml_net.so\n"
            f"{_sha(b'b')}  libuml_net.so\n"
        )
        digests, problems = gate.parse_manifest(text, NET_MANIFEST)
        self.assertEqual(_sha(b"b"), digests["libuml_net.so"])
        self.assertEqual(1, len(problems))
        self.assertIn("twice with different digests", problems[0].reason)


class ApkEntryMappingTest(unittest.TestCase):
    def test_native_libraries_map_under_lib_arm64(self) -> None:
        self.assertEqual(KERNEL_ENTRY, gate.apk_entry_for("libuml_kernel.so"))
        self.assertEqual(STUB_ENTRY, gate.apk_entry_for("libuml_stub.so"))
        self.assertEqual(NET_ENTRY, gate.apk_entry_for("libuml_net.so"))

    def test_other_accounted_artifacts_map_to_the_apk_root(self) -> None:
        # The java-resource-at-the-root precedent (initramfs.cpio.gz in the QEMU gate).
        self.assertEqual(ROOTFS_ENTRY, gate.apk_entry_for(ROOTFS_ENTRY))


class CheckApkTest(unittest.TestCase):
    """The two-manifest contract: auto/require/fullpkg modes."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.kernel = b"uml-kernel-bytes"
        self.stub = b"uml-stub-bytes"
        self.rootfs = b"rootfs-tar-bytes"
        self.umnet = b"umnet-bytes"
        self.passt = b"passt-bytes"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _apk(self, entries: dict[str, bytes]) -> Path:
        apk = self.root / "app-release.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)
        return apk

    def _fast_manifest(self, with_rootfs: bool = True) -> bytes:
        lines = [
            f"{_sha(self.kernel)}  libuml_kernel.so",
            f"{_sha(self.stub)}  libuml_stub.so",
        ]
        if with_rootfs:
            lines.append(f"{_sha(self.rootfs)}  {ROOTFS_ENTRY}")
        return ("".join(line + "\n" for line in lines)).encode("utf-8")

    def _net_manifest(self) -> bytes:
        return (
            f"{_sha(self.umnet)}  libuml_net.so\n"
            f"{_sha(self.passt)}  libuml_passt.so\n"
        ).encode("utf-8")

    def _full_entries(self) -> dict[str, bytes]:
        """Both manifests, everything accounted and byte-consistent."""
        return {
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            ROOTFS_ENTRY: self.rootfs,
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            FAST_MANIFEST: self._fast_manifest(),
            NET_MANIFEST: self._net_manifest(),
        }

    # --- auto mode ------------------------------------------------------------

    def test_full_payload_passes_and_lists_every_verified_entry(self) -> None:
        result = gate.check_apk(self._apk(self._full_entries()), False, False)
        self.assertEqual("ok", result.status)
        self.assertEqual([], result.problems)
        self.assertEqual(
            {KERNEL_ENTRY, STUB_ENTRY, ROOTFS_ENTRY, NET_ENTRY, PASST_ENTRY},
            {item.entry for item in result.verified},
        )

    def test_fast_only_in_auto_mode_verifies_what_exists(self) -> None:
        # A half-staged package in AUTO mode verifies the manifest that IS
        # present (the review's both-manifests rule is a require/fullpkg-mode
        # discipline; auto mode stays the honest partial verifier).
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            FAST_MANIFEST: self._fast_manifest(with_rootfs=False),
        }), False, False)
        self.assertEqual("ok", result.status)
        self.assertEqual({KERNEL_ENTRY, STUB_ENTRY},
                         {item.entry for item in result.verified})

    def test_digest_mismatch_fails_with_both_sides_of_the_diff(self) -> None:
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: b"not-the-staged-kernel",
            STUB_ENTRY: self.stub,
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            FAST_MANIFEST: self._fast_manifest(with_rootfs=False),
            NET_MANIFEST: self._net_manifest(),
        }), False, False)
        self.assertEqual("fail", result.status)
        self.assertEqual(1, len(result.problems))
        self.assertEqual(KERNEL_ENTRY, result.problems[0].entry)
        self.assertIn("!=", result.problems[0].reason)

    def test_net_helper_digest_mismatch_fails(self) -> None:
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            NET_ENTRY: b"swapped-passt-bytes",
            PASST_ENTRY: self.passt,
            FAST_MANIFEST: self._fast_manifest(with_rootfs=False),
            NET_MANIFEST: self._net_manifest(),
        }), False, False)
        self.assertEqual("fail", result.status)
        self.assertEqual(NET_ENTRY, result.problems[0].entry)

    def test_accounted_but_missing_entry_fails(self) -> None:
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            FAST_MANIFEST: self._fast_manifest(with_rootfs=False),
            NET_MANIFEST: self._net_manifest(),
        }), False, False)
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
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            FAST_MANIFEST: incomplete,
            NET_MANIFEST: self._net_manifest(),
        }), False, False)
        self.assertEqual("fail", result.status)
        self.assertTrue(any("libuml_kernel.so" in p.entry for p in result.problems))

    # --- skip (the honest-degradation shape) -----------------------------------

    def test_both_manifests_missing_skips_in_auto_mode(self) -> None:
        result = gate.check_apk(self._apk({KERNEL_ENTRY: b"unrelated"}), False, False)
        self.assertEqual("skip", result.status)
        self.assertIn("MISSING_ASSET", result.reason)

    # --- the both-manifests hard rule (require / fullpkg) -----------------------

    def test_missing_manifest_fails_in_require_mode(self) -> None:
        result = gate.check_apk(self._apk({KERNEL_ENTRY: b"unrelated"}), True, False)
        self.assertEqual("fail", result.status)
        self.assertIn("--require-payload", result.problems[0].reason)
        self.assertIn(FAST_MANIFEST, result.problems[0].reason)
        self.assertIn(NET_MANIFEST, result.problems[0].reason)

    def test_fast_only_manifest_fails_in_require_mode(self) -> None:
        # Review fix: a half-staged package must not slip out the door — the
        # net helper missing means SPAWN_FAILED (named detail) at runtime.
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            FAST_MANIFEST: self._fast_manifest(with_rootfs=False),
        }), True, False)
        self.assertEqual("fail", result.status)
        self.assertIn(NET_MANIFEST, result.problems[0].reason)

    def test_net_only_manifest_fails_in_require_mode(self) -> None:
        # ...and the kernel-less net-helper-only package cannot launch at all.
        result = gate.check_apk(self._apk({
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            NET_MANIFEST: self._net_manifest(),
        }), True, False)
        self.assertEqual("fail", result.status)
        self.assertIn(FAST_MANIFEST, result.problems[0].reason)

    def test_fast_only_manifest_fails_in_fullpkg_mode_too(self) -> None:
        result = gate.check_apk(self._apk({
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            FAST_MANIFEST: self._fast_manifest(),
        }), False, True)
        self.assertEqual("fail", result.status)
        self.assertIn(NET_MANIFEST, result.problems[0].reason)


class FullpkgSmokeTest(unittest.TestCase):
    """--fullpkg: the single-package completeness smoke (15 reconciled entries)."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.kernel = b"uml-kernel-bytes"
        self.stub = b"uml-stub-bytes"
        self.rootfs = b"rootfs-tar-bytes"
        self.umnet = b"umnet-bytes"
        self.passt = b"passt-bytes"
        self.llvm = b"llvm-bytes" * 100
        self.cppshared = b"cpp-shared-bytes"
        self.clang = b"clang-bytes" * 100
        self.payload = b"fastlane-payload-bytes"
        self.compiler = b"pvm-compiler-payload-bytes"
        self.qemu_lib = b"qemu-lib-bytes" * 100

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _apk(self, entries: dict[str, bytes]) -> Path:
        apk = self.root / "fullpkg.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)
        return apk

    def _pvm_manifest(self, llvm_sha: str | None = None) -> bytes:
        return json.dumps({
            "schema": 1,
            "engine": "clang",
            "payload_binary": "libpvm_compiler_payload.so",
            "libs": [
                {"name": "libLLVM.so", "size": len(self.llvm),
                 "sha256": llvm_sha or _sha(self.llvm)},
                {"name": "libc++_shared.so", "size": len(self.cppshared),
                 "sha256": _sha(self.cppshared)},
                {"name": "libclang-cpp.so", "size": len(self.clang),
                 "sha256": _sha(self.clang)},
            ],
        }).encode("utf-8")

    def _qemu_manifest(self) -> bytes:
        image, initramfs = b"qemu-image-bytes", b"qemu-initramfs-bytes"
        build_inputs, gpl_sources = b"qemu-build-inputs", b"qemu-gpl-sources"
        license_gpl, notice = b"qemu-license", b"qemu-notice"
        return "".join([
            f"{_sha(image)}  Image\n",
            f"{_sha(initramfs)}  initramfs.cpio.gz\n",
            f"{_sha(build_inputs)}  QEMU-BUILD-INPUTS\n",
            f"{_sha(gpl_sources)}  QEMU-GPL-SOURCES.md\n",
            f"{_sha(license_gpl)}  LICENSE.GPL-2.0\n",
            f"{_sha(notice)}  NOTICE.QEMU\n",
            f"{_sha(self.qemu_lib)}  lib/arm64-v8a/libdroidlayer_qemu.so\n",
        ]).encode("utf-8")

    def _fullpkg_entries(self) -> dict[str, bytes]:
        fast = "".join([
            f"{_sha(self.kernel)}  libuml_kernel.so\n",
            f"{_sha(self.stub)}  libuml_stub.so\n",
            f"{_sha(self.rootfs)}  {ROOTFS_ENTRY}\n",
        ]).encode("utf-8")
        net = "".join([
            f"{_sha(self.umnet)}  libuml_net.so\n",
            f"{_sha(self.passt)}  libuml_passt.so\n",
        ]).encode("utf-8")
        entries = {
            KERNEL_ENTRY: self.kernel,
            STUB_ENTRY: self.stub,
            ROOTFS_ENTRY: self.rootfs,
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            LIB + "libLLVM.so": self.llvm,
            LIB + "libc++_shared.so": self.cppshared,
            LIB + "libclang-cpp.so": self.clang,
            LIB + "libfastlane_payload.so": self.payload,
            LIB + "libpvm_compiler_payload.so": self.compiler,
            QEMU_LIB_ENTRY: self.qemu_lib,
            "assets/Image": b"qemu-image-bytes",
            "initramfs.cpio.gz": b"qemu-initramfs-bytes",
            "assets/QEMU-BUILD-INPUTS": b"qemu-build-inputs",
            "assets/QEMU-GPL-SOURCES.md": b"qemu-gpl-sources",
            "assets/LICENSE.GPL-2.0": b"qemu-license",
            "assets/NOTICE.QEMU": b"qemu-notice",
            NOTICE_UML_ENTRY: b"uml-notice",
            FAST_MANIFEST: fast,
            NET_MANIFEST: net,
            PVM_MANIFEST: self._pvm_manifest(),
            QEMU_MANIFEST: self._qemu_manifest(),
        }
        return entries

    def test_complete_single_package_passes(self) -> None:
        result = gate.check_apk(self._apk(self._fullpkg_entries()), False, True)
        self.assertEqual("ok", result.status)
        self.assertEqual([], result.problems)
        self.assertEqual(15, len(result.verified))

    def test_pvm_pin_mismatch_fails(self) -> None:
        # The manifest pins wrong LLVM bytes: strip or packaging corruption.
        entries = self._fullpkg_entries()
        entries[LIB + "libLLVM.so"] = b"stripped-llvm-bytes"
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any("libLLVM.so" in p.entry for p in result.problems))

    def test_missing_rootfs_tar_line_fails(self) -> None:
        # The single-package build must embed the baseline tar: the fast-profile
        # manifest must name it AND the APK must carry it.
        entries = self._fullpkg_entries()
        entries[FAST_MANIFEST] = (
            f"{_sha(self.kernel)}  libuml_kernel.so\n"
            f"{_sha(self.stub)}  libuml_stub.so\n"
        ).encode("utf-8")
        del entries[ROOTFS_ENTRY]
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(ROOTFS_ENTRY in p.entry for p in result.problems))

    def test_missing_qemu_entry_fails(self) -> None:
        entries = self._fullpkg_entries()
        del entries[QEMU_LIB_ENTRY]
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(QEMU_LIB_ENTRY in p.entry for p in result.problems))

    def test_missing_notice_uml_fails(self) -> None:
        entries = self._fullpkg_entries()
        del entries[NOTICE_UML_ENTRY]
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(NOTICE_UML_ENTRY in p.entry for p in result.problems))

    def test_missing_net_helper_entry_fails(self) -> None:
        entries = self._fullpkg_entries()
        del entries[PASST_ENTRY]
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(PASST_ENTRY in p.entry for p in result.problems))


class CommandLineTest(unittest.TestCase):
    def test_exit_codes_skip_json_and_require_mode(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            kernel, stub, umnet, passt = b"k", b"s", b"u", b"p"
            fast_manifest = root / "fast.sha256"
            fast_manifest.write_text(
                f"{_sha(kernel)}  libuml_kernel.so\n{_sha(stub)}  libuml_stub.so\n",
                encoding="utf-8",
            )
            net_manifest = root / "net.sha256"
            net_manifest.write_text(
                f"{_sha(umnet)}  libuml_net.so\n{_sha(passt)}  libuml_passt.so\n",
                encoding="utf-8",
            )

            payload_apk = root / "payload.apk"
            with zipfile.ZipFile(payload_apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, kernel)
                archive.writestr(STUB_ENTRY, stub)
                archive.writestr(NET_ENTRY, umnet)
                archive.writestr(PASST_ENTRY, passt)
                archive.writestr(FAST_MANIFEST, fast_manifest.read_text(encoding="utf-8"))
                archive.writestr(NET_MANIFEST, net_manifest.read_text(encoding="utf-8"))

            out = root / "r.json"
            self.assertEqual(0, gate.main(["--apk", str(payload_apk), "--json", str(out)]))
            self.assertTrue(out.is_file())
            self.assertIn('"status": "ok"', out.read_text(encoding="utf-8"))

            # The both-manifests discipline: require mode passes with both.
            self.assertEqual(0, gate.main(["--apk", str(payload_apk), "--require-payload"]))

            exempt_apk = root / "exempt.apk"
            with zipfile.ZipFile(exempt_apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, b"unrelated")
            self.assertEqual(0, gate.main(["--apk", str(exempt_apk)]))
            self.assertEqual(1, gate.main(["--apk", str(exempt_apk), "--require-payload"]))

    def test_half_manifest_require_mode_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            kernel, stub = b"k", b"s"
            half_apk = root / "half.apk"
            with zipfile.ZipFile(half_apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, kernel)
                archive.writestr(STUB_ENTRY, stub)
                archive.writestr(
                    FAST_MANIFEST,
                    f"{_sha(kernel)}  libuml_kernel.so\n{_sha(stub)}  libuml_stub.so\n")
            self.assertEqual(1, gate.main(["--apk", str(half_apk), "--require-payload"]))

    def test_fullpkg_mode_exit_codes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            complete_apk = root / "complete.apk"
            manifest = (
                f"{_sha(b'k')}  libuml_kernel.so\n"
                f"{_sha(b's')}  libuml_stub.so\n"
                f"{_sha(b'r')}  {ROOTFS_ENTRY}\n"
            )
            net = f"{_sha(b'u')}  libuml_net.so\n{_sha(b'p')}  libuml_passt.so\n"
            with zipfile.ZipFile(complete_apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, b"k")
                archive.writestr(STUB_ENTRY, b"s")
                archive.writestr(ROOTFS_ENTRY, b"r")
                archive.writestr(NET_ENTRY, b"u")
                archive.writestr(PASST_ENTRY, b"p")
                archive.writestr(FAST_MANIFEST, manifest)
                archive.writestr(NET_MANIFEST, net)
                archive.writestr(NOTICE_UML_ENTRY, b"uml-notice")
            # Neither manifest: the fullpkg smoke fails (implies require).
            exempt_apk = root / "exempt.apk"
            with zipfile.ZipFile(exempt_apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, b"unrelated")
            self.assertEqual(1, gate.main(["--apk", str(exempt_apk), "--fullpkg"]))
            # A UML-only APK still lacks the pVM/QEMU/NOTICE side: fail.
            self.assertEqual(1, gate.main(["--apk", str(complete_apk), "--fullpkg"]))
            # json output carries the fullpkg flag.
            out = root / "r.json"
            self.assertEqual(1, gate.main(
                ["--apk", str(complete_apk), "--fullpkg", "--json", str(out)]))
            self.assertIn('"fullpkg": true', out.read_text(encoding="utf-8"))

    def test_mismatch_exits_nonzero_and_a_broken_zip_is_a_usage_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / "mismatch.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(KERNEL_ENTRY, b"other-bytes")
                archive.writestr(STUB_ENTRY, b"s")
                archive.writestr(NET_ENTRY, b"u")
                archive.writestr(PASST_ENTRY, b"p")
                archive.writestr(FAST_MANIFEST,
                                 f"{_sha(b'k')}  libuml_kernel.so\n{_sha(b's')}  libuml_stub.so\n")
                archive.writestr(NET_MANIFEST,
                                 f"{_sha(b'u')}  libuml_net.so\n{_sha(b'p')}  libuml_passt.so\n")
            self.assertEqual(1, gate.main(["--apk", str(apk)]))

            broken = root / "broken.apk"
            broken.write_bytes(b"not a zip")
            self.assertEqual(2, gate.main(["--apk", str(broken)]))
            self.assertEqual(2, gate.main(["--apk", str(root / "absent.apk")]))

    def test_selftest_passes(self) -> None:
        self.assertEqual(0, gate.main(["--selftest"]))


if __name__ == "__main__":
    unittest.main()
