import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import fastlane_build  # noqa: E402
import reproduce  # noqa: E402

MANIFEST = REPO / "fastlane-toolbox" / "manifest.json"


class ReproduceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def build(self, name, epoch=1792540800):
        out = self.root / name
        fastlane_build.build_toolbox(
            MANIFEST, out, fixture=True, source_date_epoch=epoch
        )
        return out

    def test_two_fixture_builds_share_a_tree_digest(self):
        report = reproduce.reproduce(MANIFEST, None, True, 2, 1792540800)
        self.assertTrue(report["ok"])
        self.assertEqual(2, report["runs"])
        self.assertEqual(64, len(report["treeDigest"]))
        self.assertEqual(1792540800, report["sourceDateEpoch"])
        self.assertTrue(report["fixture"])

    def test_two_builds_are_byte_identical(self):
        left = self.build("left")
        right = self.build("right")
        for path in sorted(left.rglob("*")):
            if path.is_file():
                relative = path.relative_to(left)
                self.assertEqual(
                    path.read_bytes(),
                    (right / relative).read_bytes(),
                    relative.as_posix(),
                )

    def test_compare_trees_detects_content_change(self):
        left = self.build("left")
        right = self.build("right")
        (right / "bin" / "ls").write_bytes(b"tampered")
        with self.assertRaises(fastlane_build.BuildError):
            reproduce.compare_trees(left, right)

    def test_compare_trees_detects_mode_change(self):
        left = self.build("left")
        right = self.build("right")
        os.chmod(right / "bin" / "ls", 0o700)
        with self.assertRaises(fastlane_build.BuildError):
            reproduce.compare_trees(left, right)

    def test_source_date_epoch_changes_the_digest(self):
        first = self.build("first", epoch=1792540800)
        second = self.build("second", epoch=1792540802)
        self.assertNotEqual(
            fastlane_build.tree_digest(first), fastlane_build.tree_digest(second)
        )

    def test_one_run_is_rejected(self):
        with self.assertRaises(fastlane_build.BuildError):
            reproduce.reproduce(MANIFEST, None, True, 1, None)

    def test_cli_reports_json_and_exit_zero(self):
        result = subprocess.run(
            [
                sys.executable,
                str(HERE / "reproduce.py"),
                "--manifest",
                str(MANIFEST),
                "--fixture",
                "--runs",
                "2",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report["ok"])
        self.assertEqual(2, report["runs"])

    def test_cli_uses_source_date_epoch_environment(self):
        environment = dict(os.environ)
        environment["SOURCE_DATE_EPOCH"] = "1792540804"
        result = subprocess.run(
            [
                sys.executable,
                str(HERE / "reproduce.py"),
                "--manifest",
                str(MANIFEST),
                "--fixture",
                "--runs",
                "2",
            ],
            capture_output=True,
            text=True,
            check=False,
            env=environment,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1792540804, json.loads(result.stdout)["sourceDateEpoch"])


if __name__ == "__main__":
    unittest.main()
