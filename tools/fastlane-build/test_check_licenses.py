import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import check_licenses  # noqa: E402

MANIFEST = REPO / "fastlane-toolbox" / "manifest.json"
NOTICE = REPO / "NOTICE-fastlane.md"
SBOM = REPO / "fastlane-toolbox" / "SBOM.spdx.json"


class LicenseCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.sbom = json.loads(SBOM.read_text(encoding="utf-8"))

    def tearDown(self):
        self.temp.cleanup()

    def write_manifest(self):
        path = self.root / "manifest.json"
        path.write_text(json.dumps(self.data), encoding="utf-8")
        return path

    def write_sbom(self):
        path = self.root / "SBOM.json"
        path.write_text(json.dumps(self.sbom), encoding="utf-8")
        return path

    def test_checked_in_manifest_notice_and_sbom_are_complete(self):
        self.assertEqual([], check_licenses.check(MANIFEST, NOTICE, SBOM))

    def test_missing_license_is_rejected(self):
        del self.data["artifacts"][0]["license"]
        problems = check_licenses.check(self.write_manifest(), NOTICE, SBOM)
        self.assertTrue(any("license" in problem for problem in problems), problems)

    def test_unknown_license_is_rejected(self):
        self.data["artifacts"][0]["license"] = "Proprietary-No-Go"
        problems = check_licenses.check(self.write_manifest(), NOTICE, SBOM)
        self.assertTrue(any("allow" in problem or "license" in problem for problem in problems))

    def test_gpl_artifact_requires_corresponding_source(self):
        del self.data["artifacts"][0]["correspondingSource"]
        problems = check_licenses.check(self.write_manifest(), NOTICE, SBOM)
        self.assertTrue(any("corresponding" in problem for problem in problems), problems)

    def test_missing_binary_sha_is_rejected(self):
        self.data["artifacts"][0]["sha256"] = None
        problems = check_licenses.check(self.write_manifest(), NOTICE, SBOM)
        self.assertTrue(any("sha256" in problem for problem in problems), problems)

    def test_notice_must_name_each_artifact(self):
        notice = self.root / "NOTICE.md"
        notice.write_text("no third-party notices here\n", encoding="utf-8")
        problems = check_licenses.check(MANIFEST, notice, SBOM)
        self.assertTrue(any("NOTICE" in problem for problem in problems), problems)

    def test_sbom_must_list_each_artifact(self):
        sbom = self.root / "SBOM.json"
        sbom.write_text(json.dumps({"packages": []}), encoding="utf-8")
        problems = check_licenses.check(MANIFEST, NOTICE, sbom)
        self.assertTrue(any("SBOM" in problem for problem in problems), problems)

    def test_sbom_version_must_match_the_manifest(self):
        self.sbom["packages"][0]["versionInfo"] = "0.0.0"
        problems = check_licenses.check(MANIFEST, NOTICE, self.write_sbom())
        self.assertTrue(any("version" in problem for problem in problems), problems)

    def test_sbom_license_must_match_the_manifest(self):
        self.sbom["packages"][0]["licenseDeclared"] = "MIT"
        problems = check_licenses.check(MANIFEST, NOTICE, self.write_sbom())
        self.assertTrue(any("licenseDeclared" in problem for problem in problems), problems)

    def test_sbom_checksum_must_match_the_manifest(self):
        self.sbom["packages"][0]["checksums"] = [
            {"algorithm": "SHA256", "checksumValue": "0" * 64}
        ]
        problems = check_licenses.check(MANIFEST, NOTICE, self.write_sbom())
        self.assertTrue(any("SHA256" in problem for problem in problems), problems)

    def test_sbom_must_not_list_stale_packages(self):
        self.sbom["packages"].append(
            {
                "SPDXID": "SPDXRef-Package-stale",
                "name": "stale-tool",
                "versionInfo": "1",
                "downloadLocation": "https://example.invalid/stale",
                "licenseDeclared": "MIT",
                "licenseConcluded": "MIT",
                "copyrightText": "nobody",
                "checksums": [],
            }
        )
        problems = check_licenses.check(MANIFEST, NOTICE, self.write_sbom())
        self.assertTrue(any("stale-tool" in problem for problem in problems), problems)

    def test_sbom_requires_describes_relationship(self):
        self.sbom["relationships"] = []
        problems = check_licenses.check(MANIFEST, NOTICE, self.write_sbom())
        self.assertTrue(any("DESCRIBES" in problem for problem in problems), problems)

    def test_notice_must_name_the_binary_sha(self):
        notice = self.root / "NOTICE.md"
        notice.write_text(
            NOTICE.read_text(encoding="utf-8").replace(
                "ebd2865edcab0b590c7d0edb70d3e782cbfb541e518a390ced3a3e186509bc7f",
                "0" * 64,
            ),
            encoding="utf-8",
        )
        problems = check_licenses.check(MANIFEST, notice, SBOM)
        self.assertTrue(any("binary sha256" in problem for problem in problems), problems)

    def test_notice_must_name_the_package_sha(self):
        notice = self.root / "NOTICE.md"
        notice.write_text(
            NOTICE.read_text(encoding="utf-8").replace(
                "1d8e7a7fc2ed69bdb8eb7be9d962489c21a0f75b72d8a325437c8a09d4cfac70",
                "0" * 64,
            ),
            encoding="utf-8",
        )
        problems = check_licenses.check(MANIFEST, notice, SBOM)
        self.assertTrue(any("package sha256" in problem for problem in problems), problems)

    def test_pending_profile_must_not_be_marked_shipped(self):
        self.data["pendingProfiles"]["distcc-clang"]["status"] = "shipped"
        problems = check_licenses.check(self.write_manifest(), NOTICE, SBOM)
        self.assertTrue(any("must not ship" in problem for problem in problems), problems)

    def test_pending_component_must_be_named_in_notice(self):
        notice = self.root / "NOTICE.md"
        notice.write_text(
            NOTICE.read_text(encoding="utf-8").replace("distcc", "removed-component"),
            encoding="utf-8",
        )
        problems = check_licenses.check(MANIFEST, notice, SBOM)
        self.assertTrue(any("not named in NOTICE" in problem for problem in problems), problems)


if __name__ == "__main__":
    unittest.main()
