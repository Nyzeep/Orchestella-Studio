#!/usr/bin/env python3
"""Unit tests for the release APK gate (no Android tools required)."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("verify_apk.py")
SPEC = importlib.util.spec_from_file_location("verify_apk", MODULE_PATH)
verify_apk = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(verify_apk)

RELEASE_ID = "io.github.nyzeep.orchestella"
VALID_SHA = "a" * 64


class IdentityTests(unittest.TestCase):
    def test_release_identity_is_accepted(self) -> None:
        self.assertEqual(
            [],
            verify_apk.validate_identity(RELEASE_ID, "0.5.3", RELEASE_ID, "0.5.3"),
        )
        self.assertEqual(
            [],
            verify_apk.validate_identity(RELEASE_ID, "0.5.3-rc.1", RELEASE_ID, None),
        )

    def test_internal_suffixes_and_commit_sha_are_rejected(self) -> None:
        for version in ("0.5.3-dev.881e37a", "0.5.3-qa.runtimeperf.881e37a", "0.5.3.dirty", "0.5.3-881e37a"):
            with self.subTest(version=version):
                errors = verify_apk.validate_identity(RELEASE_ID, version, RELEASE_ID, None)
                self.assertTrue(errors, version)

    def test_wrong_application_id_is_rejected(self) -> None:
        errors = verify_apk.validate_identity(
            RELEASE_ID + ".dev", "0.5.3", RELEASE_ID, "0.5.3"
        )
        self.assertTrue(any("applicationId" in error for error in errors))

    def test_version_code_follows_the_version_formula(self) -> None:
        self.assertEqual([], verify_apk.validate_version_code("0.5.3", "503"))
        self.assertTrue(verify_apk.validate_version_code("0.5.3", "502"))

    def test_wrong_expected_version_is_rejected(self) -> None:
        errors = verify_apk.validate_identity(RELEASE_ID, "0.5.3", RELEASE_ID, "0.5.4")
        self.assertTrue(any("expected" in error for error in errors))


class CertificateTests(unittest.TestCase):
    def test_debug_certificate_is_rejected(self) -> None:
        errors = verify_apk.validate_certificate("CN=Android Debug,O=Android,C=US")
        self.assertTrue(any("debug" in error.lower() for error in errors))

    def test_release_certificate_is_accepted(self) -> None:
        self.assertEqual([], verify_apk.validate_certificate("CN=Orchestella Release,O=Nyzeep,C=CN"))
        self.assertTrue(verify_apk.validate_certificate(""))


class MetadataTests(unittest.TestCase):
    def test_complete_fixture_passes(self) -> None:
        errors, details = verify_apk.verify_metadata(
            {
                "applicationId": RELEASE_ID,
                "versionName": "0.5.3",
                "certificateDn": "CN=Orchestella Release,O=Nyzeep,C=CN",
                "apkSha256": VALID_SHA,
            },
            RELEASE_ID,
            "0.5.3",
        )
        self.assertEqual([], errors)
        self.assertEqual("0.5.3", details["versionName"])

    def test_debug_fixture_fails(self) -> None:
        errors, _ = verify_apk.verify_metadata(
            {
                "applicationId": RELEASE_ID,
                "versionName": "0.5.3",
                "certificateDn": "CN=Android Debug,O=Android,C=US",
                "apkSha256": VALID_SHA,
            },
            RELEASE_ID,
            "0.5.3",
        )
        self.assertTrue(errors)

    def test_short_checksum_is_rejected(self) -> None:
        self.assertTrue(verify_apk.validate_sha256("abc"))


class BadgingTests(unittest.TestCase):
    def test_package_line_is_parsed(self) -> None:
        line = (
            "package: name='io.github.nyzeep.orchestella' versionCode='503' "
            "versionName='0.5.3' platformBuildVersionName='16'\n"
        )
        self.assertEqual((RELEASE_ID, "503", "0.5.3"), verify_apk.parse_badging(line))


if __name__ == "__main__":
    unittest.main()
