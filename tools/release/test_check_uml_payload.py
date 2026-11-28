#!/usr/bin/env python3
"""Unit tests for check_uml_payload.py: the single-package UML gate's contract.

The gate was rewritten for the 2026-11-14 single-package round: two accounting
manifests (fast profile + net helper), the --fullpkg single-package smoke
(UML four libraries + embedded rootfs tar + pVM compiler manifest pins + QEMU
manifest + NOTICE.UML), and the both-manifests hard requirement in
--require-payload/--fullpkg modes. W1-04 (R-08, 2026-11-22) extends the
--fullpkg smoke with the NOTICE.UML <-> APK reconciliation: the packaged
notice's declared UML-lane entries (the libuml_* native libraries and the
Alpine rootfs tar) must all be really shipped, and every shipped
lib/<abi>/libuml_* entry must be declared — fail-closed in both directions.
This file pins that contract case by case
for CI (tools/release unittest discover); it mirrors
tools/release/test_check_runtime_payload.py's paradigm — synthetic zip-as-APK
fixtures built in a temporary directory, no binary fixtures, no network. The
gate itself also carries an end-to-end `--selftest` for standalone use on the
packaging machine; the two must stay aligned (this file cites the selftest
case names it mirrors).
"""

from __future__ import annotations

import contextlib
import hashlib
import io
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
UML_BUILD_INPUTS_ENTRY = "assets/UML-BUILD-INPUTS"
UML_GPL_SOURCES_ENTRY = "assets/UML-GPL-SOURCES.md"
# The verified packaging path of the notice is `assets/NOTICE.UML` (AGP ships
# app/src/main/assets/** at assets/**; the gate itself requires that entry),
# so the fullpkg fixture carries the repo asset's real bytes: the fixture APK
# and the real packaging then agree by construction, and a NOTICE.UML edit
# that drifts from the payload fails here instead of at release time.
REPO_ROOT = Path(__file__).resolve().parents[2]
NOTICE_UML_ASSET = REPO_ROOT / "app" / "src" / "main" / "assets" / "NOTICE.UML"
UML_BUILD_INPUTS_ASSET = REPO_ROOT / "app" / "src" / "main" / "assets" / "UML-BUILD-INPUTS"
UML_GPL_SOURCES_ASSET = REPO_ROOT / "app" / "src" / "main" / "assets" / "UML-GPL-SOURCES.md"
CLOSED_NOTICE_UML = (
    "NOTICE — UML engine components shipped in this application\n"
    f"the pinned fast-profile kernel pair ({KERNEL_ENTRY} and {STUB_ENTRY}), "
    f"the umnet/passt network helper pair ({NET_ENTRY} and {PASST_ENTRY}) "
    f"and the Alpine minirootfs baseline tar ({ROOTFS_ENTRY}).\n"
    "liborchestella_uml_pty.so is GPL-3.0-or-later.\n"
).encode("utf-8")


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
    """--fullpkg: the single-package completeness smoke.

    15 digest-verified entries plus, since W1-04 (R-08, 2026-11-22), the
    NOTICE.UML <-> shipped-entries declaration reconciliation (presence-level:
    the notice pins no digests, so it contributes problems, not Verified).
    """

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
            # The real shipped notice bytes, not a placeholder: the fullpkg
            # gate parses the packaged text and reconciles its declarations
            # against this fixture's payload, so the fixture must carry a
            # notice that actually declares the entries the fixture ships.
            NOTICE_UML_ENTRY: NOTICE_UML_ASSET.read_bytes(),
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

    # --- the W1-04 (R-08) NOTICE.UML <-> APK reconciliation ---------------------

    def test_missing_notice_declared_uml_entry_fails(self) -> None:
        # Direction (a): NOTICE.UML declares libuml_passt.so but the APK no
        # longer ships it — the notice and the payload disagree, fail-closed.
        # The accounting manifests flag the same drift; this pins that the
        # notice reconciliation flags it too, with its own named reason.
        entries = self._fullpkg_entries()
        del entries[PASST_ENTRY]
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(
            PASST_ENTRY in p.entry and NOTICE_UML_ENTRY in p.reason
            for p in result.problems))

    def test_undeclared_uml_library_fails(self) -> None:
        # Direction (b): a new libuml_*.so ships without a NOTICE.UML
        # declaration — an incomplete GPL notice must block the release.
        entries = self._fullpkg_entries()
        undeclared = LIB + "libuml_extra.so"
        entries[undeclared] = b"undeclared-uml-bytes"
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(
            undeclared in p.entry and NOTICE_UML_ENTRY in p.reason
            for p in result.problems))

    def test_unreadable_notice_uml_fails(self) -> None:
        # A notice that cannot be read as text cannot prove anything: the
        # gate must not wave it through as "no evidence of a problem".
        entries = self._fullpkg_entries()
        entries[NOTICE_UML_ENTRY] = b"\xff\xfe\x00not-utf8"
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(NOTICE_UML_ENTRY in p.entry for p in result.problems))

    def test_undeclared_rootfs_fails_even_when_the_manifest_matches_the_apk(self) -> None:
        entries = self._fullpkg_entries()
        entries[NOTICE_UML_ENTRY] = entries[NOTICE_UML_ENTRY].replace(ROOTFS_ENTRY.encode(), b"")
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertEqual([ROOTFS_ENTRY], [problem.entry for problem in result.problems])
        self.assertIn(NOTICE_UML_ENTRY, result.problems[0].reason)

    def test_missing_net_helper_entry_fails(self) -> None:
        entries = self._fullpkg_entries()
        del entries[PASST_ENTRY]
        result = gate.check_apk(self._apk(entries), False, True)
        self.assertEqual("fail", result.status)
        self.assertTrue(any(PASST_ENTRY in p.entry for p in result.problems))

    # --- W3-05 (R-05) release attribution closure gate -------------------------

    def test_fullpkg_passes_and_surfaces_open_items_from_real_notice(self) -> None:
        apk = self._apk(self._fullpkg_entries())
        result = gate.check_apk(apk, False, True)
        self.assertEqual("ok", result.status)
        self.assertEqual([], result.problems)
        self.assertEqual(3, len(result.notice_open_items))

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = gate.main(["--apk", str(apk), "--fullpkg"])
        output = buf.getvalue()
        self.assertEqual(0, code)
        self.assertIn("FULLPKG_GATE_OK", output)
        self.assertEqual(3, output.count("UML_NOTICE_OPEN_ITEM "))

    def test_require_attribution_closed_passes_with_closed_notice_and_companion_records(self) -> None:
        entries = self._fullpkg_entries()
        entries[NOTICE_UML_ENTRY] = CLOSED_NOTICE_UML
        entries[UML_BUILD_INPUTS_ENTRY] = UML_BUILD_INPUTS_ASSET.read_bytes()
        entries[UML_GPL_SOURCES_ENTRY] = UML_GPL_SOURCES_ASSET.read_bytes()
        apk = self._apk(entries)

        result = gate.check_apk(apk, False, True, require_attribution_closed=True)
        self.assertEqual("ok", result.status)
        self.assertEqual([], result.problems)
        self.assertEqual([], result.notice_open_items)

        out_json = self.root / "closed.json"
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = gate.main([
                "--apk", str(apk), "--fullpkg",
                "--require-release-attribution-closed",
                "--json", str(out_json),
            ])
        output = buf.getvalue()
        self.assertEqual(0, code)
        self.assertIn("FULLPKG_GATE_OK", output)
        self.assertNotIn("UML_NOTICE_OPEN_ITEM", output)
        payload = json.loads(out_json.read_text(encoding="utf-8"))
        self.assertTrue(payload["require_release_attribution_closed"])
        self.assertEqual([], payload["notice_open_items"])

    def test_require_attribution_closed_fails_on_real_notice_open_items(self) -> None:
        entries = self._fullpkg_entries()
        entries[UML_BUILD_INPUTS_ENTRY] = UML_BUILD_INPUTS_ASSET.read_bytes()
        entries[UML_GPL_SOURCES_ENTRY] = UML_GPL_SOURCES_ASSET.read_bytes()
        apk = self._apk(entries)

        result = gate.check_apk(apk, False, True, require_attribution_closed=True)
        self.assertEqual("fail", result.status)
        self.assertEqual(3, len(result.notice_open_items))
        notice_problems = [p for p in result.problems if p.entry == NOTICE_UML_ENTRY]
        self.assertEqual(3, len(notice_problems))

        buf = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
            code = gate.main([
                "--apk", str(apk), "--fullpkg",
                "--require-release-attribution-closed",
            ])
        output = buf.getvalue()
        self.assertEqual(1, code)
        self.assertIn("FULLPKG_GATE_FAIL", output)
        self.assertEqual(3, output.count("UML_NOTICE_OPEN_ITEM "))

    def test_require_attribution_closed_fails_when_uml_build_inputs_missing(self) -> None:
        entries = self._fullpkg_entries()
        entries[NOTICE_UML_ENTRY] = CLOSED_NOTICE_UML
        entries[UML_GPL_SOURCES_ENTRY] = UML_GPL_SOURCES_ASSET.read_bytes()
        apk = self._apk(entries)

        result = gate.check_apk(apk, False, True, require_attribution_closed=True)
        self.assertEqual("fail", result.status)
        self.assertEqual([UML_BUILD_INPUTS_ENTRY], [p.entry for p in result.problems])

    def test_require_attribution_closed_fails_when_uml_gpl_sources_missing(self) -> None:
        entries = self._fullpkg_entries()
        entries[NOTICE_UML_ENTRY] = CLOSED_NOTICE_UML
        entries[UML_BUILD_INPUTS_ENTRY] = UML_BUILD_INPUTS_ASSET.read_bytes()
        apk = self._apk(entries)

        result = gate.check_apk(apk, False, True, require_attribution_closed=True)
        self.assertEqual("fail", result.status)
        self.assertEqual([UML_GPL_SOURCES_ENTRY], [p.entry for p in result.problems])

    def test_require_attribution_closed_fails_when_companion_asset_is_empty(self) -> None:
        entries = self._fullpkg_entries()
        entries[NOTICE_UML_ENTRY] = CLOSED_NOTICE_UML
        entries[UML_BUILD_INPUTS_ENTRY] = b"   \n"
        entries[UML_GPL_SOURCES_ENTRY] = UML_GPL_SOURCES_ASSET.read_bytes()
        apk = self._apk(entries)

        result = gate.check_apk(apk, False, True, require_attribution_closed=True)
        self.assertEqual("fail", result.status)
        self.assertEqual([UML_BUILD_INPUTS_ENTRY], [p.entry for p in result.problems])

    def test_require_attribution_closed_cli_requires_fullpkg(self) -> None:
        apk = self._apk(self._fullpkg_entries())
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            with self.assertRaises(SystemExit) as ctx:
                gate.main(["--apk", str(apk), "--require-release-attribution-closed"])
        self.assertEqual(2, ctx.exception.code)


class NoticeUmlDeclarationTest(unittest.TestCase):
    """The NOTICE.UML text parser (W1-04, R-08) and OPEN: extractor (W3-05, R-05).

    The gate carries no copy of the notice's contents — the packaged notice
    text is the one declaration source, and this parser only recognizes the
    grammar the real notice uses. These tests pin that grammar against the
    real repo asset, so a NOTICE.UML edit that changes the declaration form
    fails here before it can silently weaken the gate.
    """

    def test_real_repo_notice_declares_exactly_the_uml_lane(self) -> None:
        text = NOTICE_UML_ASSET.read_text(encoding="utf-8")
        declared_libs, declared_tars = gate.parse_notice_uml_entries(text)
        self.assertEqual(
            {KERNEL_ENTRY, STUB_ENTRY, NET_ENTRY, PASST_ENTRY}, declared_libs)
        self.assertEqual({ROOTFS_ENTRY}, declared_tars)

    def test_real_repo_notice_declares_optional_full_profile_entries(self) -> None:
        text = NOTICE_UML_ASSET.read_text(encoding="utf-8")
        declared_full = gate.parse_notice_uml_full_entries(text)
        self.assertEqual(
            {LIB + "libuml_kernel_full.so", LIB + "libuml_stub_full.so"},
            declared_full,
        )

    def test_real_repo_notice_surfaces_expected_open_items_without_spillover(self) -> None:
        text = NOTICE_UML_ASSET.read_text(encoding="utf-8")
        items = gate.find_notice_uml_open_items(text)
        self.assertEqual(3, len(items))
        self.assertIn("linux-fast", items[0])
        self.assertNotIn("Configuration delivery", items[0])
        self.assertIn("licensing-attribution", items[1])
        self.assertIn("corresponding-source", items[2])
        self.assertNotIn("Upgrade discipline", items[2])

    def test_find_notice_uml_open_items_handles_closed_consecutive_and_bare_markers(self) -> None:
        self.assertEqual([], gate.find_notice_uml_open_items(CLOSED_NOTICE_UML.decode("utf-8")))
        self.assertEqual([], gate.find_notice_uml_open_items("REOPEN: ignored\nopen: lowercase\n"))
        items = gate.find_notice_uml_open_items(
            "Intro.\nOPEN: first item\ncontinues here.\nOPEN: second item\n\nOPEN:\n"
        )
        self.assertEqual(
            ["OPEN: first item continues here.", "OPEN: second item", "OPEN:"],
            items,
        )

    def test_bare_names_map_to_the_arm64_lib_entries(self) -> None:
        # The real notice also names components in bare form ("libuml_net.so
        # (the umnet supervisor ...)"); bare names reconcile through the
        # jniLibs unpacking rule, same as the accounting manifests do.
        text = (
            "the kernel libuml_kernel.so and its stub libuml_stub.so ship, "
            "plus libuml_net.so and libuml_passt.so\n"
        )
        declared_libs, declared_tars = gate.parse_notice_uml_entries(text)
        self.assertEqual(
            {KERNEL_ENTRY, STUB_ENTRY, NET_ENTRY, PASST_ENTRY}, declared_libs)
        self.assertEqual(set(), declared_tars)

    def test_path_tokens_are_taken_literally_including_the_abi(self) -> None:
        # A declared lib/<abi>/libuml_* path is the declared APK entry, ABI
        # included: a notice declaring lib/x86/... while the APK ships
        # lib/arm64-v8a/... must surface as a disagreement, not a match.
        text = "ships lib/x86/libuml_kernel.so and lib/arm64-v8a/libuml_net.so"
        declared_libs, declared_tars = gate.parse_notice_uml_entries(text)
        self.assertEqual({"lib/x86/libuml_kernel.so", NET_ENTRY}, declared_libs)
        self.assertEqual(set(), declared_tars)


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


class FullProfilePayloadGateTest(unittest.TestCase):
    """W01-02 dual-profile and --qa-full-profile gate contracts."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.fast_kernel = b"fast-kernel-bytes"
        self.fast_stub = b"fast-stub-bytes"
        self.full_kernel = b"full-kernel-bytes"
        self.full_stub = b"full-stub-bytes"
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

    def _apk(self, name: str, entries: dict[str, bytes]) -> Path:
        apk = self.root / name
        with zipfile.ZipFile(apk, "w") as archive:
            for entry_name, payload in entries.items():
                archive.writestr(entry_name, payload)
        return apk

    def _full_manifest(self) -> bytes:
        return (
            "# profile=full-smp-arm64\n"
            f"{_sha(self.full_kernel)}  libuml_kernel_full.so\n"
            f"{_sha(self.full_stub)}  libuml_stub_full.so\n"
        ).encode("utf-8")

    def _fast_manifest(self) -> bytes:
        return (
            f"{_sha(self.fast_kernel)}  libuml_kernel.so\n"
            f"{_sha(self.fast_stub)}  libuml_stub.so\n"
            f"{_sha(self.rootfs)}  {ROOTFS_ENTRY}\n"
        ).encode("utf-8")

    def _net_manifest(self) -> bytes:
        return (
            f"{_sha(self.umnet)}  libuml_net.so\n"
            f"{_sha(self.passt)}  libuml_passt.so\n"
        ).encode("utf-8")

    def _dual_notice(self) -> bytes:
        return (
            "NOTICE — UML engine components shipped in this application\n"
            f"{KERNEL_ENTRY} and {STUB_ENTRY}, "
            f"{LIB}libuml_kernel_full.so and {LIB}libuml_stub_full.so, "
            f"{NET_ENTRY} and {PASST_ENTRY}, and {ROOTFS_ENTRY}.\n"
        ).encode("utf-8")

    def _qa_full_entries(self) -> dict[str, bytes]:
        return {
            LIB + "libuml_kernel_full.so": self.full_kernel,
            LIB + "libuml_stub_full.so": self.full_stub,
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            "assets/uml-full-profile-v1.sha256": self._full_manifest(),
            NET_MANIFEST: self._net_manifest(),
            NOTICE_UML_ENTRY: (
                "NOTICE — UML engine components\n"
                f"{LIB}libuml_kernel_full.so {LIB}libuml_stub_full.so "
                f"{NET_ENTRY} {PASST_ENTRY}\n"
            ).encode("utf-8"),
        }

    def test_require_full_profile_rejects_fast_only_apk_with_profile_mismatch(self) -> None:
        apk = self._apk("fast_only.apk", {
            KERNEL_ENTRY: self.fast_kernel,
            STUB_ENTRY: self.fast_stub,
            ROOTFS_ENTRY: self.rootfs,
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            FAST_MANIFEST: self._fast_manifest(),
            NET_MANIFEST: self._net_manifest(),
        })
        out = self.root / "mismatch.json"
        buf = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
            code = gate.main(["--apk", str(apk), "--require-full-profile", "--json", str(out)])
        self.assertEqual(1, code)
        payload = json.loads(out.read_text(encoding="utf-8"))
        self.assertIn("PROFILE_MISMATCH", payload["failure_codes"])

    def test_require_full_profile_detects_missing_stub_and_digest_mismatch(self) -> None:
        no_stub = self._qa_full_entries()
        del no_stub[LIB + "libuml_stub_full.so"]
        out_stub = self.root / "nostub.json"
        buf = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
            code = gate.main([
                "--apk", str(self._apk("nostub.apk", no_stub)),
                "--qa-full-profile", "--json", str(out_stub),
            ])
        self.assertEqual(1, code)
        self.assertIn("MISSING_STUB", json.loads(out_stub.read_text(encoding="utf-8"))["failure_codes"])

        bad_kernel = self._qa_full_entries()
        bad_kernel[LIB + "libuml_kernel_full.so"] = b"corrupt-full-kernel"
        out_sha = self.root / "badsha.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate.main([
                "--apk", str(self._apk("badsha.apk", bad_kernel)),
                "--qa-full-profile", "--json", str(out_sha),
            ])
        self.assertEqual(1, code)
        self.assertIn("SHA256_MISMATCH", json.loads(out_sha.read_text(encoding="utf-8"))["failure_codes"])

    def test_qa_full_profile_passes_without_qemu_or_pvm_and_reports_scope(self) -> None:
        apk = self._apk("qa_full.apk", self._qa_full_entries())
        out = self.root / "qa.json"
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = gate.main(["--apk", str(apk), "--qa-full-profile", "--json", str(out)])
        self.assertEqual(0, code)
        payload = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual("ok", payload["status"])
        self.assertEqual("qa-uml-full-profile", payload["verified_scope"])
        self.assertEqual(4, len(payload["verified"]))

    def test_fullpkg_with_require_full_profile_verifies_all_17_entries(self) -> None:
        base = FullpkgSmokeTest()
        base.setUp()
        try:
            entries = base._fullpkg_entries()
        finally:
            base.tearDown()
        # Replace fast kernel/stub/rootfs in entries with matching local test bytes
        entries[KERNEL_ENTRY] = self.fast_kernel
        entries[STUB_ENTRY] = self.fast_stub
        entries[ROOTFS_ENTRY] = self.rootfs
        entries[NET_ENTRY] = self.umnet
        entries[PASST_ENTRY] = self.passt
        entries[FAST_MANIFEST] = self._fast_manifest()
        entries[NET_MANIFEST] = self._net_manifest()
        entries[LIB + "libuml_kernel_full.so"] = self.full_kernel
        entries[LIB + "libuml_stub_full.so"] = self.full_stub
        entries["assets/uml-full-profile-v1.sha256"] = self._full_manifest()
        entries[NOTICE_UML_ENTRY] = self._dual_notice()

        apk = self._apk("dual_fullpkg.apk", entries)
        out = self.root / "dual.json"
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = gate.main([
                "--apk", str(apk), "--fullpkg", "--require-full-profile", "--json", str(out),
            ])
        self.assertEqual(0, code)
        payload = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual("ok", payload["status"])
        self.assertEqual(17, len(payload["verified"]))

    def test_standard_fullpkg_rejects_leaked_full_profile_entries(self) -> None:
        base = FullpkgSmokeTest()
        base.setUp()
        try:
            entries = base._fullpkg_entries()
        finally:
            base.tearDown()
        entries[KERNEL_ENTRY] = self.fast_kernel
        entries[STUB_ENTRY] = self.fast_stub
        entries[ROOTFS_ENTRY] = self.rootfs
        entries[NET_ENTRY] = self.umnet
        entries[PASST_ENTRY] = self.passt
        entries[FAST_MANIFEST] = self._fast_manifest()
        entries[NET_MANIFEST] = self._net_manifest()
        entries[LIB + "libuml_kernel_full.so"] = self.full_kernel
        entries[LIB + "libuml_stub_full.so"] = self.full_stub
        entries["assets/uml-full-profile-v1.sha256"] = self._full_manifest()
        entries[NOTICE_UML_ENTRY] = self._dual_notice()

        apk = self._apk("leaked_full_in_release.apk", entries)
        out = self.root / "leaked_fullpkg.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate.main(["--apk", str(apk), "--fullpkg", "--json", str(out)])
        self.assertEqual(1, code)
        payload = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual("fail", payload["status"])
        self.assertIn("FULL_PROFILE_FORBIDDEN", payload["failure_codes"])

    def test_standard_auto_and_require_payload_reject_stray_full_profile(self) -> None:
        # Even without fast/net manifests, stray full-profile assets must fail rather than skip.
        for label, entries in (
            ("stray_kernel", {LIB + "libuml_kernel_full.so": self.full_kernel}),
            ("stray_stub", {LIB + "libuml_stub_full.so": self.full_stub}),
            ("stray_manifest", {"assets/uml-full-profile-v1.sha256": self._full_manifest()}),
        ):
            apk = self._apk(f"auto_{label}.apk", entries)
            out = self.root / f"auto_{label}.json"
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = gate.main(["--apk", str(apk), "--json", str(out)])
            self.assertEqual(1, code, label)
            payload = json.loads(out.read_text(encoding="utf-8"))
            self.assertIn("FULL_PROFILE_FORBIDDEN", payload["failure_codes"], label)

        req_apk = self._apk("req_with_full.apk", {
            KERNEL_ENTRY: self.fast_kernel,
            STUB_ENTRY: self.fast_stub,
            NET_ENTRY: self.umnet,
            PASST_ENTRY: self.passt,
            FAST_MANIFEST: self._fast_manifest(),
            NET_MANIFEST: self._net_manifest(),
            LIB + "libuml_kernel_full.so": self.full_kernel,
            LIB + "libuml_stub_full.so": self.full_stub,
            "assets/uml-full-profile-v1.sha256": self._full_manifest(),
        })
        out_req = self.root / "req_with_full.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate.main(["--apk", str(req_apk), "--require-payload", "--json", str(out_req)])
        self.assertEqual(1, code)
        self.assertIn("FULL_PROFILE_FORBIDDEN", json.loads(out_req.read_text(encoding="utf-8"))["failure_codes"])

    def test_qa_full_profile_rejects_fake_manifest_and_pin_drift(self) -> None:
        # 1. Wrong # profile= header or unexpected extra entry in full manifest must fail.
        wrong_header = self._qa_full_entries()
        wrong_header["assets/uml-full-profile-v1.sha256"] = (
            "# profile=fast-up-arm64\n"
            f"{_sha(self.full_kernel)}  libuml_kernel_full.so\n"
            f"{_sha(self.full_stub)}  libuml_stub_full.so\n"
        ).encode("utf-8")
        out_hdr = self.root / "wrong_hdr.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate.main([
                "--apk", str(self._apk("wrong_hdr.apk", wrong_header)),
                "--qa-full-profile", "--json", str(out_hdr),
            ])
        self.assertEqual(1, code)
        self.assertIn("MANIFEST_APK_MISMATCH", json.loads(out_hdr.read_text(encoding="utf-8"))["failure_codes"])

        # 1b. Missing # profile= header must also fail.
        missing_header = self._qa_full_entries()
        missing_header["assets/uml-full-profile-v1.sha256"] = (
            f"{_sha(self.full_kernel)}  libuml_kernel_full.so\n"
            f"{_sha(self.full_stub)}  libuml_stub_full.so\n"
        ).encode("utf-8")
        out_missing_hdr = self.root / "missing_hdr.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate.main([
                "--apk", str(self._apk("missing_hdr.apk", missing_header)),
                "--qa-full-profile", "--json", str(out_missing_hdr),
            ])
        self.assertEqual(1, code)
        self.assertIn("MANIFEST_APK_MISMATCH", json.loads(out_missing_hdr.read_text(encoding="utf-8"))["failure_codes"])

        extra_entry = self._qa_full_entries()
        extra_entry["assets/uml-full-profile-v1.sha256"] = (
            "# profile=full-smp-arm64\n"
            f"{_sha(self.full_kernel)}  libuml_kernel_full.so\n"
            f"{_sha(self.full_stub)}  libuml_stub_full.so\n"
            f"{_sha(self.fast_kernel)}  libuml_kernel.so\n"
        ).encode("utf-8")
        out_extra = self.root / "extra_entry.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate.main([
                "--apk", str(self._apk("extra_entry.apk", extra_entry)),
                "--qa-full-profile", "--json", str(out_extra),
            ])
        self.assertEqual(1, code)
        self.assertIn("MANIFEST_APK_MISMATCH", json.loads(out_extra.read_text(encoding="utf-8"))["failure_codes"])

        # 2. Self-consistent fake binaries + fake manifest must fail when --pin-source checks real pins.
        pin_source = REPO_ROOT / "app" / "src" / "main" / "java" / "io" / "github" / "nyzeep" / "orchestella" / "UmlKernelAssets.java"
        out_pin = self.root / "fake_pins.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate.main([
                "--apk", str(self._apk("fake_pins.apk", self._qa_full_entries())),
                "--qa-full-profile",
                "--pin-source", str(pin_source),
                "--json", str(out_pin),
            ])
        self.assertEqual(1, code)
        self.assertIn("MANIFEST_APK_MISMATCH", json.loads(out_pin.read_text(encoding="utf-8"))["failure_codes"])


if __name__ == "__main__":
    unittest.main()
