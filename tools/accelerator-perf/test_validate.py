"""Unit tests for the report validator and the zero-device dry-run gate."""

import contextlib
import io
import json
import unittest
from pathlib import Path

import collect
import perfstats
import validate

HERE = Path(__file__).resolve().parent
GOLDEN = HERE / "fixtures" / "expected-report.json"
SCHEMA_FILE = HERE / "schema" / "accelerator-perf-v1.json"


def golden():
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def defect_text(report):
    return " | ".join(validate.validate_report(report))


class GoldenReportTest(unittest.TestCase):
    def test_committed_golden_report_is_valid(self):
        self.assertEqual([], validate.validate_report(golden()))

    def test_self_test_gate_passes(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, validate.self_test())
        self.assertIn("ACCELERATOR_PERF_OK", output.getvalue())


class SchemaTest(unittest.TestCase):
    def test_missing_keys_are_reported(self):
        report = golden()
        del report["protocol"]

        self.assertIn("missing keys: protocol", defect_text(report))

    def test_wrong_schema_is_rejected(self):
        report = golden()
        report["schema"] = "accelerator-perf-v2"

        self.assertIn(
            "schema must be accelerator-perf-v1", defect_text(report))

    def test_alias_must_be_safe(self):
        report = golden()
        report["device_alias"] = "/data/local/tmp"

        self.assertIn("device_alias must be a safe alias", defect_text(report))

    def test_timestamp_must_be_utc_seconds(self):
        report = golden()
        report["generated_at_utc"] = "2026-10-23 00:00:00"

        self.assertIn(
            "generated_at_utc must be an ISO-8601 UTC second",
            validate.validate_report(report))

    def test_protocol_drift_is_rejected(self):
        report = golden()
        report["protocol"]["min_samples"] = 3

        self.assertIn(
            "protocol.min_samples must be 10", defect_text(report))

    def test_duplicate_scenario_mode_pair_is_rejected(self):
        report = golden()
        report["scenarios"].append(dict(report["scenarios"][0]))

        defects = defect_text(report)

        self.assertIn("duplicate scenario/mode pair", defects)


class ScenarioTest(unittest.TestCase):
    def test_distribution_order_is_enforced(self):
        report = golden()
        report["scenarios"][0]["median_ms"] = report["scenarios"][0]["max_ms"] + 1

        self.assertIn(
            "min <= median <= max is violated", defect_text(report))

    def test_negative_iqr_is_rejected(self):
        report = golden()
        report["scenarios"][0]["iqr_ms"] = -1

        self.assertIn("iqr must not be negative", defect_text(report))

    def test_failure_codes_must_match_the_failure_count(self):
        report = golden()
        report["scenarios"][1]["failure_codes"] = {"TIMEOUT": 1}

        self.assertIn(
            "failure_codes must sum to failures", defect_text(report))

    def test_unknown_failure_code_is_rejected(self):
        report = golden()
        report["scenarios"][1]["failures"] = 1
        report["scenarios"][1]["failure_codes"] = {"RAW_LOGCAT": 1}

        defects = defect_text(report)

        self.assertIn("unknown failure code", defects)

    def test_insufficient_samples_must_agree_with_n(self):
        report = golden()
        report["scenarios"][0]["insufficient_samples"] = True

        self.assertIn(
            "insufficient_samples disagrees with n", defect_text(report))

    def test_empty_scenario_must_not_carry_a_distribution(self):
        report = golden()
        entry = report["scenarios"][0]
        entry.update({"n": 0, "insufficient_samples": True})

        self.assertIn(
            "median_ms must be null when n is 0", defect_text(report))

    def test_phases_are_required(self):
        report = golden()
        report["scenarios"][0]["phases_ms"] = {"compile": 1}

        self.assertIn(
            "phases_ms must have the four fixed phases", defect_text(report))

    def test_offload_ratio_is_bounded(self):
        report = golden()
        report["scenarios"][0]["offload_permille"] = 1001

        self.assertIn(
            "offload_permille must be within [0, 1000]", defect_text(report))


class SchemaFileTest(unittest.TestCase):
    """The published schema is load-bearing: it must agree with the code."""

    def setUp(self):
        self.published = json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))

    def test_required_properties_match_the_validator(self):
        self.assertEqual(validate.REQUIRED_KEYS, set(self.published["required"]))

    def test_vocabularies_match_the_collector(self):
        scenario = self.published["$defs"]["scenario"]["properties"]

        self.assertEqual(list(collect.SCENARIOS), scenario["scenario"]["enum"])
        self.assertEqual(list(collect.MODES), scenario["mode"]["enum"])
        self.assertEqual(
            sorted(collect.ALLOWED_CODES),
            sorted(scenario["failure_codes"]["propertyNames"]["enum"]),
        )

    def test_protocol_constants_match(self):
        protocol = self.published["properties"]["protocol"]["properties"]

        self.assertEqual(perfstats.MIN_SAMPLES, protocol["min_samples"]["const"])
        self.assertEqual(perfstats.IQR_METHOD, protocol["iqr_method"]["const"])
        self.assertEqual(
            collect.SPEEDUP_THRESHOLD, protocol["speedup_threshold"]["const"])
        self.assertEqual(
            collect.REGRESSION_PERMILLE, protocol["regression_permille"]["const"])

    def test_golden_report_uses_only_declared_fields(self):
        entry = golden()["scenarios"][0]

        self.assertEqual(
            set(self.published["$defs"]["scenario"]["required"]), set(entry))


if __name__ == "__main__":
    unittest.main()
