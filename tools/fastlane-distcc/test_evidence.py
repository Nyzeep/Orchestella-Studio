"""AX-06 evidence-report tests, runnable with the standard library only.

The previous version depended on pytest, which no workflow installed and no CI
step ran, so the checks below never executed. They are plain ``unittest`` now
and are wired into the main CI job next to the other tool tests: a test that
cannot run is worse than no test, because it looks green.
"""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import evidence


def _entry(case: str, outcome: str, failure: str, **overrides):
    value = {
        "case": case,
        "outcome": outcome,
        "failureCode": failure,
        "objectSha256": "a" * 64,
        "localSha256": "a" * 64,
        "hashEqual": True,
        "durationMs": 1,
        "bytesFromClient": 10,
        "bytesToClient": 20,
    }
    value.update(overrides)
    return value


def _report():
    return [
        _entry(name, outcome, failure)
        for name, (outcome, failure) in evidence.EXPECTED_CASES.items()
    ]


class ReportValidationTest(unittest.TestCase):
    def test_valid_report_is_accepted(self):
        evidence.validate_report(_report())

        self.assertEqual(5, len(evidence.EXPECTED_CASES))

    def test_missing_case_is_rejected(self):
        with self.assertRaisesRegex(evidence.ReportError, "missing cases"):
            evidence.validate_report(_report()[:-1])

    def test_extra_or_missing_field_is_rejected(self):
        data = _report()
        data[0]["argv"] = "cc hello.c"

        with self.assertRaisesRegex(evidence.ReportError, "fixed evidence set"):
            evidence.validate_report(data)

    def test_path_like_value_is_rejected(self):
        data = _report()
        data[0]["failureCode"] = "/tmp/hello.o"

        with self.assertRaises(evidence.ReportError):
            evidence.validate_report(data)

    def test_token_like_value_is_rejected(self):
        data = _report()
        data[0]["objectSha256"] = "a" * 32

        with self.assertRaises(evidence.ReportError):
            evidence.validate_report(data)

    def test_bad_hash_and_negative_counter_are_rejected(self):
        bad_hash = _report()
        bad_hash[0]["objectSha256"] = "not-a-hash"
        with self.assertRaisesRegex(evidence.ReportError, "SHA-256"):
            evidence.validate_report(bad_hash)

        negative = _report()
        negative[0]["durationMs"] = -1
        with self.assertRaisesRegex(evidence.ReportError, "durationMs"):
            evidence.validate_report(negative)

    def test_wrong_outcome_for_case_is_rejected(self):
        data = _report()
        data[0]["failureCode"] = "AXP1_CLOSED"

        with self.assertRaisesRegex(evidence.ReportError, "unexpected outcome"):
            evidence.validate_report(data)


class LoadReportTest(unittest.TestCase):
    def test_load_report_reads_json_and_rejects_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "report.json"
            path.write_text(json.dumps(_report()), encoding="utf-8")
            self.assertEqual(5, len(evidence.load_report(path)))

            empty = root / "empty.json"
            empty.write_text("[]", encoding="utf-8")
            with self.assertRaisesRegex(evidence.ReportError, "non-empty"):
                evidence.load_report(empty)


class SafeLogLineTest(unittest.TestCase):
    def test_safe_log_line_has_only_fixed_fields(self):
        line = evidence.safe_log_line(3, 100, 200, 42, "AXP1_AUTH_FAILED")
        value = json.loads(line)

        self.assertEqual(set(evidence.LOG_FIELDS), set(value))
        self.assertNotIn("hello.c", line)
        self.assertNotIn("token", line.lower())
        self.assertNotIn("/", line)

    def test_safe_log_line_rejects_bad_values(self):
        with self.assertRaisesRegex(ValueError, "code"):
            evidence.safe_log_line(1, 0, 0, 1, "bad code")
        with self.assertRaisesRegex(ValueError, "jobs"):
            evidence.safe_log_line(-1, 0, 0, 1, "OK")


class Sha256Test(unittest.TestCase):
    def test_sha256_file_matches_hashlib(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hello.c"
            path.write_bytes(b"int main(void){return 0;}\n")

            self.assertEqual(
                hashlib.sha256(path.read_bytes()).hexdigest(),
                evidence.sha256_file(path),
            )


if __name__ == "__main__":
    unittest.main()
