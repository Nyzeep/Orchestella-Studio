# SPDX-License-Identifier: GPL-3.0-or-later
"""Zero-device tests for the AVF device-validation harness.

Every test here is synthetic: no adb, no device, no VM. The harness may only
report what it actually observed, so dry-run is asserted to contain no `pass`.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
import uuid

sys.dont_write_bytecode = True
MODULE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(MODULE_DIR))

import avf_test_fixtures as fixtures
from avf_evidence_io import Rejected, json_bytes


def require_root():
    configured = os.environ.get("AVF_VALIDATION_TEST_ROOT")
    if not configured or not Path(configured).is_absolute():
        raise AssertionError("AVF_VALIDATION_TEST_ROOT must be an explicit absolute external directory")
    return Path(configured) / uuid.uuid4().hex


class DryRunTests(unittest.TestCase):
    def setUp(self):
        import run_avf_device_validation as harness
        self.harness = harness

    def test_dry_run_has_twelve_stages_and_no_pass(self):
        report = self.harness.build_report("dry-run")
        self.harness.validate_report(report)
        self.assertEqual(report["schema_version"], 1)
        self.assertEqual(report["mode"], "dry-run")
        self.assertTrue(report["device_independent"])
        self.assertEqual(report["real_avf_samples"], 0)
        self.assertEqual(len(report["stages"]), 12)
        self.assertEqual([stage["id"] for stage in report["stages"]],
                         [stage.stage_id for stage in self.harness.STAGES])
        for stage in report["stages"]:
            self.assertEqual(stage["status"], "not-tested", stage["id"])
            self.assertEqual(stage["reason_code"], "DRY_RUN_NO_DEVICE", stage["id"])
            self.assertEqual(stage["evidence"]["state"], "not-collected", stage["id"])
            self.assertEqual(stage["rom_binding"]["state"], "not-collected", stage["id"])
            self.assertIsNone(stage["sampled_at_utc"], stage["id"])
        self.assertEqual(report["summary"], {"pass": 0, "fail": 0, "blocked": 0, "not-tested": 12})
        self.assertEqual(report["verdict"], "NOT_TESTED")

    def test_validate_rejects_a_dry_run_that_claims_a_pass(self):
        report = self.harness.build_report("dry-run")
        report["stages"][0]["status"] = "pass"
        report["summary"] = {"pass": 1, "fail": 0, "blocked": 0, "not-tested": 11}
        with self.assertRaisesRegex(Rejected, "REPORT_SCHEMA_REJECTED"):
            self.harness.validate_report(report)

    def test_validate_rejects_missing_stage_and_extra_key(self):
        report = self.harness.build_report("dry-run")
        broken = json.loads(json_bytes(report))
        broken["stages"] = broken["stages"][:11]
        with self.assertRaisesRegex(Rejected, "REPORT_SCHEMA_REJECTED"):
            self.harness.validate_report(broken)

        extra = json.loads(json_bytes(report))
        extra["stages"][0]["raw_console"] = fixtures.SENTINEL
        with self.assertRaisesRegex(Rejected, "REPORT_SCHEMA_REJECTED"):
            self.harness.validate_report(extra)

    def test_validate_rejects_unknown_status_and_wrong_summary(self):
        report = self.harness.build_report("dry-run")
        broken = json.loads(json_bytes(report))
        broken["stages"][0]["status"] = "supported"
        with self.assertRaisesRegex(Rejected, "REPORT_SCHEMA_REJECTED"):
            self.harness.validate_report(broken)

        wrong = json.loads(json_bytes(report))
        wrong["summary"]["pass"] = 12
        with self.assertRaisesRegex(Rejected, "REPORT_SCHEMA_REJECTED"):
            self.harness.validate_report(wrong)

    def test_device_mode_is_blocked_without_invoking_adb(self):
        report = self.harness.build_report("device", device_alias="fixture-device")
        self.harness.validate_report(report)
        self.assertEqual(report["mode"], "device")
        self.assertFalse(report["device_mode"]["implemented"])
        self.assertFalse(report["device_mode"]["adb_invoked"])
        self.assertEqual(report["summary"], {"pass": 0, "fail": 0, "blocked": 12, "not-tested": 0})
        for stage in report["stages"]:
            self.assertEqual(stage["status"], "blocked", stage["id"])
            self.assertEqual(stage["reason_code"], "DEVICE_MODE_NOT_IMPLEMENTED", stage["id"])

    def test_redaction_removes_device_identifiers_and_tokens(self):
        text = ("serial=" + fixtures.SENTINEL
                + " imei:123456789012345"
                + " mac=AA:BB:CC:DD:EE:FF"
                + " token=" + "a" * 64
                + " Authorization: Bearer " + fixtures.SENTINEL)
        cleaned = self.harness.redact(text)
        self.assertNotIn(fixtures.SENTINEL, cleaned)
        self.assertNotIn("123456789012345", cleaned)
        self.assertNotIn("AA:BB:CC:DD:EE:FF", cleaned)
        self.assertNotIn("a" * 64, cleaned)
        self.assertIn("<redacted>", cleaned)

    def test_markdown_is_a_summary_and_never_claims_success(self):
        report = self.harness.build_report("dry-run")
        markdown = self.harness.render_markdown(report)
        self.assertIn("not-tested", markdown)
        self.assertIn("设备无关", markdown)
        self.assertIn("真实 AVF 样本：0", markdown)
        self.assertNotIn("AVF 已支持", markdown)
        self.assertNotIn("AVF 已通过", markdown)
        self.assertNotIn(fixtures.SENTINEL, markdown)


class CliTests(unittest.TestCase):
    def setUp(self):
        self.root = require_root()

    def run_cli(self, *arguments):
        return subprocess.run([sys.executable, "-B", str(MODULE_DIR / "run_avf_device_validation.py"),
                               *arguments],
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=30, shell=False)

    def test_cli_dry_run_writes_parseable_json_and_markdown(self):
        out = self.root / "out"
        process = self.run_cli("--dry-run", "--out", str(out))
        self.assertEqual(process.returncode, 0, process.stderr.decode("utf-8", "replace"))
        summary = json.loads(process.stdout)
        report_path = Path(summary["report_json"])
        markdown_path = Path(summary["report_markdown"])
        self.assertTrue(report_path.is_file())
        self.assertTrue(markdown_path.is_file())
        self.assertTrue(report_path.is_relative_to(out))
        report = json.loads(report_path.read_bytes())
        self.assertEqual(report["mode"], "dry-run")
        self.assertEqual(len(report["stages"]), 12)
        self.assertEqual(report["summary"]["pass"], 0)
        self.assertIn("not-tested", markdown_path.read_text(encoding="utf-8"))
        self.assertNotIn(fixtures.SENTINEL, process.stdout.decode("utf-8", "replace"))

    def test_cli_device_mode_is_blocked_and_does_not_echo_serial(self):
        out = self.root / "out"
        serial_like = "R58M" + fixtures.SENTINEL
        process = self.run_cli("--device", serial_like, "--out", str(out))
        self.assertEqual(process.returncode, 2)
        self.assertNotIn(serial_like.encode(), process.stdout + process.stderr)
        summary = json.loads(process.stdout)
        report = json.loads(Path(summary["report_json"]).read_bytes())
        self.assertEqual(report["summary"]["blocked"], 12)
        self.assertEqual(report["device_mode"]["implemented"], False)

    def test_cli_requires_a_mode_and_an_absolute_out(self):
        missing_mode = self.run_cli("--out", str(self.root / "out"))
        self.assertEqual(missing_mode.returncode, 2)

        relative_out = self.run_cli("--dry-run", "--out", "relative-out")
        self.assertEqual(relative_out.returncode, 2)
        self.assertEqual(json.loads(relative_out.stdout), {"error": "ROOT_REJECTED"})

    def test_cli_rejects_serial_as_device_alias(self):
        out = self.root / "out"
        process = self.run_cli("--dry-run", "--device-alias", "R58M" + fixtures.SENTINEL,
                               "--out", str(out))
        self.assertEqual(process.returncode, 2)
        self.assertNotIn(fixtures.SENTINEL.encode(), process.stdout + process.stderr)

    def test_cli_rejects_a_tampered_report_fixture(self):
        out = self.root / "out"
        report = {
            "schema_version": 1,
            "kind": "avf_device_validation",
            "mode": "dry-run",
            "stages": [{"id": "platform_declaration", "status": "pass"}],
        }
        fixture = self.root / "tampered.json"
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.write_bytes(json_bytes(report))
        process = self.run_cli("--validate", str(fixture), "--out", str(out))
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stdout), {"error": "REPORT_SCHEMA_REJECTED"})
        self.assertNotIn(fixtures.SENTINEL.encode(), process.stdout + process.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
