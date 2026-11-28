#!/usr/bin/env python3
"""Unit and fake-ADB end-to-end tests for qa_package_guard (TDD)."""

from __future__ import annotations

import hashlib
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any

sys.path.insert(0, os.path.dirname(__file__))

import qa_package_guard as guard  # noqa: E402


def _make_fake_apk(path: Path, *, abis: tuple[str, ...] = ("arm64-v8a",), extra: bytes = b"apk") -> str:
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("AndroidManifest.xml", b"binary-manifest-" + extra)
        for abi in abis:
            zf.writestr(f"lib/{abi}/libstub.so", b"\x7fELF" + extra)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _make_apk_info(
    *,
    apk_path: Path,
    application_id: str,
    version_name: str,
    version_code: int = 603,
    abis: list[str] | None = None,
    instrumentations: list[dict[str, str]] | None = None,
    cert_sha256: str = "3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06",
    sig_passed: bool = True,
) -> dict[str, Any]:
    return {
        "name": apk_path.name,
        "size_bytes": apk_path.stat().st_size,
        "sha256": hashlib.sha256(apk_path.read_bytes()).hexdigest(),
        "application_id": application_id,
        "version_name": version_name,
        "version_code": version_code,
        "min_sdk": 26,
        "target_sdk": 36,
        "debuggable": True,
        "abis": list(abis) if abis is not None else ["arm64-v8a"],
        "instrumentations": list(instrumentations) if instrumentations is not None else [],
        "apksigner": {
            "passed": sig_passed,
            "schemes": {"v2": True, "v3": True},
            "signer_count": 1 if sig_passed else 0,
            "signer_cert_sha256s": [cert_sha256] if sig_passed and cert_sha256 else [],
            "signer_pubkey_sha256s": ["eed7a7ff3f989fc2946d38d4ca3bb934dfc9b66acf31ad641f07703f05bc48e4"],
            "signer_dns": ["C=US, O=Android, CN=Android Debug"],
            "lines": [
                f"Signer #1 certificate SHA-256 digest: {cert_sha256}"
            ] if sig_passed and cert_sha256 else [],
        },
    }


class TestQaTargetAndHostApkVerification(unittest.TestCase):
    def test_resolve_qa_target_accepts_existing_and_wave02_purposes_and_rejects_non_qa(self) -> None:
        w0105_spec = guard.resolve_qa_target(
            default_purpose="w0105",
            instrumentation_class="io.github.nyzeep.orchestella.W0105FullValidationProbe",
        )
        self.assertEqual(w0105_spec.purpose, "w0105")
        self.assertEqual(w0105_spec.app_package, "io.github.nyzeep.orchestella.qa.w0105")
        self.assertEqual(w0105_spec.test_package, "io.github.nyzeep.orchestella.qa.w0105.test")
        self.assertEqual(
            w0105_spec.instrumentation_component,
            "io.github.nyzeep.orchestella.qa.w0105.test/io.github.nyzeep.orchestella.W0105FullValidationProbe",
        )

        w0203_spec = guard.resolve_qa_target(
            purpose="w0203",
            default_purpose="w0105",
            instrumentation_class="io.github.nyzeep.orchestella.W0105FullValidationProbe",
        )
        self.assertEqual(w0203_spec.app_package, "io.github.nyzeep.orchestella.qa.w0203")
        self.assertEqual(w0203_spec.test_package, "io.github.nyzeep.orchestella.qa.w0203.test")

        for bad_pkg, expected_code in (
            ("io.github.nyzeep.orchestella", "RELEASE_PACKAGE_FORBIDDEN"),
            ("io.github.nyzeep.orchestella.dev", "DEV_PACKAGE_FORBIDDEN"),
            ("com.example.other", "NON_QA_PACKAGE_FORBIDDEN"),
            ("io.github.nyzeep.orchestella.qa.bad-purpose", "INVALID_QA_PURPOSE"),
        ):
            with self.assertRaises(guard.QaGuardError) as ctx:
                guard.resolve_qa_target(
                    package=bad_pkg,
                    default_purpose="w0105",
                    instrumentation_class="io.github.nyzeep.orchestella.W0105FullValidationProbe",
                )
            self.assertEqual(ctx.exception.code, expected_code)

        with self.assertRaises(guard.QaGuardError) as conflict_ctx:
            guard.resolve_qa_target(
                purpose="w0203",
                package="io.github.nyzeep.orchestella.qa.w0105",
                default_purpose="w0105",
                instrumentation_class="io.github.nyzeep.orchestella.W0105FullValidationProbe",
            )
        self.assertEqual(conflict_ctx.exception.code, "QA_TARGET_CONFLICT")

    def test_parsers_for_apkanalyzer_aapt2_and_apksigner(self) -> None:
        xml_text = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:versionCode="603"
    android:versionName="0.6.3-qa.w0203.4cb8081"
    package="io.github.nyzeep.orchestella.qa.w0203">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
    <instrumentation
        android:name="io.github.nyzeep.orchestella.W0105FullValidationProbe"
        android:targetPackage="io.github.nyzeep.orchestella.qa.w0203" />
    <application android:debuggable="true" />
</manifest>"""
        parsed_xml = guard.parse_apkanalyzer_manifest_xml(xml_text)
        self.assertEqual(parsed_xml["application_id"], "io.github.nyzeep.orchestella.qa.w0203")
        self.assertEqual(parsed_xml["version_name"], "0.6.3-qa.w0203.4cb8081")
        self.assertEqual(parsed_xml["version_code"], 603)
        self.assertTrue(parsed_xml["debuggable"])
        self.assertEqual(
            parsed_xml["instrumentations"],
            [{"name": "io.github.nyzeep.orchestella.W0105FullValidationProbe", "target_package": "io.github.nyzeep.orchestella.qa.w0203"}],
        )

        xmltree = """N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="io.github.nyzeep.orchestella.qa.w0105.test" (Raw: "io.github.nyzeep.orchestella.qa.w0105.test")
      E: uses-sdk (line=5)
        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26
        A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=36
      E: instrumentation (line=9)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="io.github.nyzeep.orchestella.W0105FullValidationProbe" (Raw: "io.github.nyzeep.orchestella.W0105FullValidationProbe")
        A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="io.github.nyzeep.orchestella.qa.w0105" (Raw: "io.github.nyzeep.orchestella.qa.w0105")
      E: application (line=16)
        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true"""
        parsed_tree = guard.parse_aapt2_xmltree(xmltree)
        self.assertEqual(parsed_tree["application_id"], "io.github.nyzeep.orchestella.qa.w0105.test")
        self.assertEqual(
            parsed_tree["instrumentations"],
            [{"name": "io.github.nyzeep.orchestella.W0105FullValidationProbe", "target_package": "io.github.nyzeep.orchestella.qa.w0105"}],
        )

        apksigner_out = """Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: 3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06
Signer #1 public key SHA-256 digest: eed7a7ff3f989fc2946d38d4ca3bb934dfc9b66acf31ad641f07703f05bc48e4"""
        parsed_sig = guard.parse_apksigner_output(apksigner_out, "", 0)
        self.assertTrue(parsed_sig["passed"])
        self.assertEqual(
            parsed_sig["signer_cert_sha256s"],
            ["3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06"],
        )

    def test_verify_qa_apk_pair_rejects_mismatches_and_accepts_valid_pair(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app_apk = Path(tmp) / "app.apk"
            test_apk = Path(tmp) / "test.apk"
            _make_fake_apk(app_apk, abis=("arm64-v8a",), extra=b"app")
            _make_fake_apk(test_apk, abis=(), extra=b"test")

            target = guard.resolve_qa_target(
                purpose="w0203",
                default_purpose="w0105",
                instrumentation_class="io.github.nyzeep.orchestella.W0105FullValidationProbe",
            )
            valid_app = _make_apk_info(
                apk_path=app_apk,
                application_id="io.github.nyzeep.orchestella.qa.w0203",
                version_name="0.6.3-qa.w0203.4cb8081",
            )
            valid_test = _make_apk_info(
                apk_path=test_apk,
                application_id="io.github.nyzeep.orchestella.qa.w0203.test",
                version_name="",
                abis=[],
                instrumentations=[{
                    "name": "io.github.nyzeep.orchestella.W0105FullValidationProbe",
                    "target_package": "io.github.nyzeep.orchestella.qa.w0203",
                }],
            )
            proof = guard.verify_qa_apk_pair(app_apk, test_apk, target, app_info=valid_app, test_info=valid_test)
            self.assertEqual(proof["qa_target"]["app_package"], "io.github.nyzeep.orchestella.qa.w0203")
            self.assertEqual(proof["app_apk"]["sha256"], valid_app["sha256"])

            bad_cases = [
                ("release app", {**valid_app, "application_id": "io.github.nyzeep.orchestella"}, valid_test, "RELEASE_PACKAGE_FORBIDDEN"),
                ("dev app", {**valid_app, "application_id": "io.github.nyzeep.orchestella.dev"}, valid_test, "DEV_PACKAGE_FORBIDDEN"),
                ("other qa app", {**valid_app, "application_id": "io.github.nyzeep.orchestella.qa.w0105"}, valid_test, "APP_PACKAGE_MISMATCH"),
                ("wrong purpose version", {**valid_app, "version_name": "0.6.3-qa.w0105.4cb8081"}, valid_test, "APK_VERSION_PURPOSE_MISMATCH"),
                ("wrong abi", {**valid_app, "abis": ["x86_64"]}, valid_test, "APK_ABI_MISMATCH"),
                ("wrong test target", valid_app, {**valid_test, "instrumentations": [{"name": "io.github.nyzeep.orchestella.W0105FullValidationProbe", "target_package": "io.github.nyzeep.orchestella.qa.w0105"}]}, "INSTRUMENTATION_TARGET_MISMATCH"),
                ("ambiguous instrumentation", valid_app, {**valid_test, "instrumentations": valid_test["instrumentations"] * 2}, "INSTRUMENTATION_AMBIGUOUS"),
                ("signature mismatch", valid_app, {**valid_test, "apksigner": {**valid_test["apksigner"], "signer_cert_sha256s": ["b" * 64]}}, "APK_SIGNATURE_MISMATCH"),
                ("invalid signature", {**valid_app, "apksigner": {**valid_app["apksigner"], "passed": False, "signer_cert_sha256s": []}}, valid_test, "APK_SIGNATURE_INVALID"),
            ]
            for label, bad_app, bad_test, code in bad_cases:
                with self.subTest(label=label):
                    with self.assertRaises(guard.QaGuardError) as ctx:
                        guard.verify_qa_apk_pair(app_apk, test_apk, target, app_info=bad_app, test_info=bad_test)
                    self.assertEqual(ctx.exception.code, code)


class _FakeCmdResult:
    def __init__(self, stdout: str = "", stderr: str = "", returncode: int = 0) -> None:
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode


class _FakeDeviceAdb:
    """Fake ADB tracking every read/write command against a multi-package device."""

    def __init__(
        self,
        *,
        app_sha: str,
        test_sha: str,
        staged_files: dict[str, str] | None = None,
        signature_conflict: bool = False,
        version_name: str = "0.6.3-qa.w0105.4cb8081",
        target_pkg: str = "io.github.nyzeep.orchestella.qa.w0105",
    ) -> None:
        self.calls: list[tuple[str, ...]] = []
        self.target_pkg = target_pkg
        self.test_pkg = f"{target_pkg}.test"
        self.packages: dict[str, dict[str, str]] = {
            "io.github.nyzeep.orchestella": {
                "path": "/data/app/~~rel/io.github.nyzeep.orchestella/base.apk",
                "sha256": "1" * 64,
                "version_name": "0.6.3",
            },
            "io.github.nyzeep.orchestella.dev": {
                "path": "/data/app/~~dev/io.github.nyzeep.orchestella.dev/base.apk",
                "sha256": "2" * 64,
                "version_name": "0.6.3-dev.4cb8081",
            },
            "io.github.nyzeep.orchestella.qa.w305": {
                "path": "/data/app/~~w305/io.github.nyzeep.orchestella.qa.w305/base.apk",
                "sha256": "3" * 64,
                "version_name": "0.6.3-qa.w305.4cb8081",
            },
            target_pkg: {
                "path": f"/data/app/~~qa/{target_pkg}/base.apk",
                "sha256": app_sha,
                "version_name": version_name,
            },
            self.test_pkg: {
                "path": f"/data/app/~~qatest/{self.test_pkg}/base.apk",
                "sha256": test_sha,
                "version_name": "",
            },
        }
        self.staged_files = dict(staged_files or {})
        self.signature_conflict = signature_conflict
        self.instrumentation_target = target_pkg

    def write_calls(self) -> list[tuple[str, ...]]:
        writes: list[tuple[str, ...]] = []
        for c in self.calls:
            if c[0] in ("install", "uninstall", "push"):
                writes.append(c)
            elif c[0] == "shell" and len(c) > 1:
                joined = " ".join(c[1:])
                if any(tok in joined for tok in ("rm ", "mkdir ", "cp ", "chmod ", "pm grant", "pm clear")):
                    writes.append(c)
        return writes

    def run(self, *args: str, timeout: int = 120, check: bool = True) -> _FakeCmdResult:
        self.calls.append(args)
        if args == ("shell", "pm", "list", "packages", "io.github.nyzeep.orchestella"):
            lines = [f"package:{pkg}" for pkg in sorted(self.packages)]
            return _FakeCmdResult("\n".join(lines) + "\n")

        if args[:3] == ("shell", "pm", "path") and len(args) == 4:
            pkg = args[3]
            if pkg in self.packages:
                return _FakeCmdResult(f"package:{self.packages[pkg]['path']}\n")
            return _FakeCmdResult("", returncode=1)

        if args[:3] == ("shell", "dumpsys", "package") and len(args) == 4:
            pkg = args[3]
            if pkg in self.packages:
                vname = self.packages[pkg]["version_name"]
                return _FakeCmdResult(f"Package [{pkg}] (abc):\n  versionName={vname}\n  versionCode=603 minSdk=26 targetSdk=36\n")
            return _FakeCmdResult("", returncode=0)

        if args[:2] == ("shell", "sha256sum") and len(args) == 3:
            target_path = args[2]
            for info in self.packages.values():
                if info["path"] == target_path:
                    return _FakeCmdResult(f"{info['sha256']}  {target_path}\n")
            return _FakeCmdResult("No such file", returncode=1)

        if args == ("shell", "pm", "list", "instrumentation"):
            if self.test_pkg in self.packages:
                return _FakeCmdResult(
                    f"instrumentation:{self.test_pkg}/io.github.nyzeep.orchestella.W0105FullValidationProbe "
                    f"(target={self.instrumentation_target})\n"
                )
            return _FakeCmdResult("")

        if args[:2] == ("shell", "run-as") and len(args) >= 4 and args[3] == "sha256sum":
            rel = args[4]
            if rel in self.staged_files:
                return _FakeCmdResult(f"{self.staged_files[rel]}  {rel}\n")
            return _FakeCmdResult("No such file", returncode=1)

        if args[0] == "uninstall":
            pkg = args[1]
            if pkg in self.packages:
                del self.packages[pkg]
                return _FakeCmdResult("Success\n")
            return _FakeCmdResult("Failure [DELETE_FAILED_INTERNAL_ERROR]\n", returncode=1)

        if args[:3] == ("install", "-r", "-t"):
            apk_path = Path(args[3])
            if self.signature_conflict:
                return _FakeCmdResult(
                    "",
                    "adb: failed to install: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package signatures do not match previously installed version]",
                    returncode=1,
                )
            digest = hashlib.sha256(apk_path.read_bytes()).hexdigest()
            is_test = "test" in apk_path.name
            pkg = self.test_pkg if is_test else self.target_pkg
            self.packages[pkg] = {
                "path": f"/data/app/~~new/{pkg}/base.apk",
                "sha256": digest,
                "version_name": "" if is_test else "0.6.3-qa.w0105.4cb8081",
            }
            return _FakeCmdResult("Success\n")

        return _FakeCmdResult("")


class TestFakeAdbScopedInstallAndCleanup(unittest.TestCase):
    def _build_fixture(self, tmp: str) -> tuple[Path, Path, guard.QaTargetSpec, dict[str, Any], dict[str, str]]:
        app_apk = Path(tmp) / "app.apk"
        test_apk = Path(tmp) / "test.apk"
        app_sha = _make_fake_apk(app_apk, abis=("arm64-v8a",), extra=b"app-v1")
        test_sha = _make_fake_apk(test_apk, abis=(), extra=b"test-v1")
        target = guard.resolve_qa_target(
            default_purpose="w0105",
            instrumentation_class="io.github.nyzeep.orchestella.W0105FullValidationProbe",
        )
        app_info = _make_apk_info(
            apk_path=app_apk,
            application_id=target.app_package,
            version_name="0.6.3-qa.w0105.4cb8081",
        )
        test_info = _make_apk_info(
            apk_path=test_apk,
            application_id=target.test_package,
            version_name="",
            abis=[],
            instrumentations=[{"name": target.instrumentation_class, "target_package": target.app_package}],
        )
        proof = guard.verify_qa_apk_pair(app_apk, test_apk, target, app_info=app_info, test_info=test_info)
        staged = {"files/distro-inputs/debian-13-rootfs.tar.xz": "4" * 64}
        return app_apk, test_apk, target, proof, staged

    def test_default_install_never_uninstalls_and_preserves_other_packages(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app_apk, test_apk, target, proof, staged = self._build_fixture(tmp)
            adb = _FakeDeviceAdb(app_sha="9" * 64, test_sha="8" * 64, staged_files=staged)
            report = guard.install_qa_candidate(
                adb,
                app_apk=app_apk,
                test_apk=test_apk,
                target=target,
                apk_proof=proof,
                device_alias="device-4k",
                clean_install=False,
            )
            self.assertEqual(report["mode"], "incremental")
            self.assertEqual(report["device_alias"], "device-4k")
            self.assertFalse(any(c[0] == "uninstall" for c in adb.calls))
            self.assertEqual(
                [c[:3] for c in adb.write_calls()],
                [("install", "-r", "-t"), ("install", "-r", "-t")],
            )
            self.assertIn("io.github.nyzeep.orchestella", adb.packages)
            self.assertIn("io.github.nyzeep.orchestella.dev", adb.packages)
            self.assertIn("io.github.nyzeep.orchestella.qa.w305", adb.packages)
            self.assertEqual(report["installed_after"]["app"]["sha256"], proof["app_apk"]["sha256"])

    def test_clean_install_uninstalls_only_exact_qa_and_test_packages(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app_apk, test_apk, target, proof, staged = self._build_fixture(tmp)
            adb = _FakeDeviceAdb(app_sha="9" * 64, test_sha="8" * 64, staged_files=staged)
            report = guard.install_qa_candidate(
                adb,
                app_apk=app_apk,
                test_apk=test_apk,
                target=target,
                apk_proof=proof,
                device_alias="device-4k",
                clean_install=True,
            )
            self.assertEqual(report["mode"], "clean_qa_only")
            uninstalls = [c for c in adb.calls if c[0] == "uninstall"]
            self.assertEqual(
                uninstalls,
                [
                    ("uninstall", "io.github.nyzeep.orchestella.qa.w0105.test"),
                    ("uninstall", "io.github.nyzeep.orchestella.qa.w0105"),
                ],
            )
            self.assertEqual(
                report["clean_uninstall"]["removed"],
                ["io.github.nyzeep.orchestella.qa.w0105.test", "io.github.nyzeep.orchestella.qa.w0105"],
            )
            self.assertIn("io.github.nyzeep.orchestella", adb.packages)
            self.assertIn("io.github.nyzeep.orchestella.dev", adb.packages)
            self.assertIn("io.github.nyzeep.orchestella.qa.w305", adb.packages)

            for bad_pkg in ("io.github.nyzeep.orchestella", "io.github.nyzeep.orchestella.dev", "com.other"):
                with self.assertRaises(guard.QaGuardError):
                    guard.clean_uninstall_qa_target(adb, bad_pkg)

    def test_signature_conflict_aborts_cleanly_with_actionable_next_step(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app_apk, test_apk, target, proof, staged = self._build_fixture(tmp)
            adb = _FakeDeviceAdb(app_sha="9" * 64, test_sha="8" * 64, staged_files=staged, signature_conflict=True)
            with self.assertRaises(guard.QaGuardError) as ctx:
                guard.install_qa_candidate(
                    adb,
                    app_apk=app_apk,
                    test_apk=test_apk,
                    target=target,
                    apk_proof=proof,
                    device_alias="device-4k",
                    clean_install=False,
                )
            self.assertEqual(ctx.exception.code, "INSTALL_SIGNATURE_CONFLICT")
            self.assertIn("--clean-install", ctx.exception.detail)
            self.assertFalse(any(c[0] == "uninstall" for c in adb.calls))

    def test_skip_install_verifies_on_device_apks_instrumentation_and_inputs_with_zero_writes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app_apk, test_apk, target, proof, staged = self._build_fixture(tmp)
            adb_ok = _FakeDeviceAdb(
                app_sha=proof["app_apk"]["sha256"],
                test_sha=proof["test_apk"]["sha256"],
                staged_files=staged,
            )
            res = guard.verify_installed_qa_candidate(
                adb_ok,
                target=target,
                apk_proof=proof,
                device_alias="device-4k",
                expected_staged_files=staged,
            )
            self.assertTrue(res["verified"])
            self.assertEqual(adb_ok.write_calls(), [])

            adb_bad_apk = _FakeDeviceAdb(app_sha="f" * 64, test_sha=proof["test_apk"]["sha256"], staged_files=staged)
            with self.assertRaises(guard.QaGuardError) as ctx1:
                guard.verify_installed_qa_candidate(
                    adb_bad_apk, target=target, apk_proof=proof, device_alias="device-4k", expected_staged_files=staged
                )
            self.assertEqual(ctx1.exception.code, "INSTALLED_APP_APK_MISMATCH")
            self.assertEqual(adb_bad_apk.write_calls(), [])

            adb_bad_inst = _FakeDeviceAdb(
                app_sha=proof["app_apk"]["sha256"], test_sha=proof["test_apk"]["sha256"], staged_files=staged
            )
            adb_bad_inst.instrumentation_target = "io.github.nyzeep.orchestella.qa.w305"
            with self.assertRaises(guard.QaGuardError) as ctx2:
                guard.verify_installed_qa_candidate(
                    adb_bad_inst, target=target, apk_proof=proof, device_alias="device-4k", expected_staged_files=staged
                )
            self.assertEqual(ctx2.exception.code, "INSTALLED_INSTRUMENTATION_MISMATCH")
            self.assertEqual(adb_bad_inst.write_calls(), [])

            adb_bad_stage = _FakeDeviceAdb(
                app_sha=proof["app_apk"]["sha256"],
                test_sha=proof["test_apk"]["sha256"],
                staged_files={"files/distro-inputs/debian-13-rootfs.tar.xz": "0" * 64},
            )
            with self.assertRaises(guard.QaGuardError) as ctx3:
                guard.verify_installed_qa_candidate(
                    adb_bad_stage, target=target, apk_proof=proof, device_alias="device-4k", expected_staged_files=staged
                )
            self.assertEqual(ctx3.exception.code, "STAGED_INPUT_MISMATCH")
            self.assertEqual(adb_bad_stage.write_calls(), [])

    def test_resolve_unique_device_rejects_empty_and_ambiguous_devices(self) -> None:
        empty_proc = _FakeCmdResult("List of devices attached\n\n")
        with self.assertRaises(guard.QaGuardError) as ctx_empty:
            guard.resolve_unique_device("adb", None, run_cmd=lambda *a, **k: empty_proc)
        self.assertEqual(ctx_empty.exception.code, "DEVICE_UNAVAILABLE")

        two_proc = _FakeCmdResult("List of devices attached\ndev-1\tdevice\ndev-2\tdevice\n")
        with self.assertRaises(guard.QaGuardError) as ctx_two:
            guard.resolve_unique_device("adb", None, run_cmd=lambda *a, **k: two_proc)
        self.assertEqual(ctx_two.exception.code, "DEVICE_NOT_UNIQUE")
        self.assertEqual(guard.resolve_unique_device("adb", "dev-2", run_cmd=lambda *a, **k: two_proc), "dev-2")


if __name__ == "__main__":
    unittest.main()
