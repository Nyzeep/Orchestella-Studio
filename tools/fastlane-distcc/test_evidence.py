from __future__ import annotations

import json
import tempfile
from pathlib import Path

import pytest

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


def test_valid_report_is_accepted():
    evidence.validate_report(_report())
    assert len(evidence.EXPECTED_CASES) == 5


def test_missing_case_is_rejected():
    data = _report()[:-1]
    with pytest.raises(evidence.ReportError, match="missing cases"):
        evidence.validate_report(data)


def test_extra_or_missing_field_is_rejected():
    data = _report()
    data[0]["argv"] = "cc hello.c"
    with pytest.raises(evidence.ReportError, match="fixed evidence set"):
        evidence.validate_report(data)


def test_path_like_value_is_rejected():
    data = _report()
    data[0]["failureCode"] = "/tmp/hello.o"
    with pytest.raises(evidence.ReportError):
        evidence.validate_report(data)


def test_token_like_value_is_rejected():
    data = _report()
    data[0]["objectSha256"] = "a" * 32
    with pytest.raises(evidence.ReportError):
        evidence.validate_report(data)


def test_bad_hash_and_negative_counter_are_rejected():
    data = _report()
    data[0]["objectSha256"] = "not-a-hash"
    with pytest.raises(evidence.ReportError, match="SHA-256"):
        evidence.validate_report(data)

    data = _report()
    data[0]["durationMs"] = -1
    with pytest.raises(evidence.ReportError, match="durationMs"):
        evidence.validate_report(data)


def test_wrong_outcome_for_case_is_rejected():
    data = _report()
    data[0]["failureCode"] = "AXP1_CLOSED"
    with pytest.raises(evidence.ReportError, match="unexpected outcome"):
        evidence.validate_report(data)


def test_load_report_reads_json_and_rejects_empty(tmp_path: Path):
    path = tmp_path / "report.json"
    path.write_text(json.dumps(_report()), encoding="utf-8")
    assert len(evidence.load_report(path)) == 5

    empty = tmp_path / "empty.json"
    empty.write_text("[]", encoding="utf-8")
    with pytest.raises(evidence.ReportError, match="non-empty"):
        evidence.load_report(empty)


def test_safe_log_line_has_only_fixed_fields():
    line = evidence.safe_log_line(3, 100, 200, 42, "AXP1_AUTH_FAILED")
    value = json.loads(line)
    assert set(value) == set(evidence.LOG_FIELDS)
    assert "hello.c" not in line
    assert "token" not in line.lower()
    assert "/" not in line


def test_safe_log_line_rejects_bad_values():
    with pytest.raises(ValueError, match="code"):
        evidence.safe_log_line(1, 0, 0, 1, "bad code")
    with pytest.raises(ValueError, match="jobs"):
        evidence.safe_log_line(-1, 0, 0, 1, "OK")


def test_sha256_file_matches_hashlib(tmp_path: Path):
    path = tmp_path / "hello.c"
    path.write_bytes(b"int main(void){return 0;}\n")
    assert evidence.sha256_file(path) == __import__("hashlib").sha256(
        path.read_bytes()
    ).hexdigest()
