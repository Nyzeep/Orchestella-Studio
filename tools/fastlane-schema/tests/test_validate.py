import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parents[1]
ROOT = HERE.parent.parent
VALIDATOR = HERE / "validate.py"
FIXTURES = HERE / "fixtures"
PREFLIGHT = FIXTURES / "preflight"
SUMMARY = FIXTURES / "run-summary"

VALID_PREFLIGHT = [
    "terminal-ok.json",
    "terminal-blocked-fln1-7.json",
    "blocked-external-permission-denied.json",
]


def cli(*args: object) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VALIDATOR), *map(str, args)],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )


def output(result: subprocess.CompletedProcess[str]) -> str:
    return result.stdout + result.stderr


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def copy_summary(tmp_path: Path, **overrides: object) -> Path:
    document = read_json(SUMMARY / "ok.json")
    document.update(overrides)
    target = tmp_path / "run-summary.json"
    write_json(target, document)
    return target


@pytest.mark.parametrize("name", VALID_PREFLIGHT)
def test_valid_preflight_fixtures(name: str) -> None:
    result = cli("--report", PREFLIGHT / name)
    assert result.returncode == 0, output(result)
    assert "OK preflight" in result.stdout
    assert "FAIL" not in result.stderr


def test_terminal_blocked_fixture_records_fln1_7_execve_errno_13() -> None:
    document = read_json(PREFLIGHT / "terminal-blocked-fln1-7.json")
    assert document["outcome"] == "PREFLIGHT_TERMINAL_BLOCKED"
    assert document["terminal"] == {
        "status": "blocked",
        "reason_code": "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM",
        "fln1_code": 7,
        "exec_stage": "execve",
        "errno": 13,
    }


def test_blocked_external_fixture_uses_permission_denied() -> None:
    document = read_json(PREFLIGHT / "blocked-external-permission-denied.json")
    assert document["outcome"] == "PREFLIGHT_BLOCKED_EXTERNAL"
    assert document["permission"] == "DENIED"
    assert document["terminal"]["reason_code"] == "PERMISSION_DENIED"


def test_valid_preflight_fixtures_name_the_three_not_tested_areas() -> None:
    required = {
        "encrypted_storage_persistence",
        "20_cycles",
        "negative_cases",
    }
    for name in VALID_PREFLIGHT:
        document = read_json(PREFLIGHT / name)
        assert required <= set(document["not_tested"])


@pytest.mark.parametrize(
    ("name", "code", "path"),
    [
        ("missing-stage.json", "E_SCHEMA_REQUIRED", "/stage_results/delete"),
        ("bad-outcome.json", "E_SCHEMA_ENUM", "/outcome"),
        ("not-tested-missing.json", "E_SCHEMA_MIN_ITEMS", "/not_tested"),
        ("not-tested-missing.json", "E_SCHEMA_CONTAINS", "/not_tested"),
    ],
)
def test_invalid_preflight_fixtures(name: str, code: str, path: str) -> None:
    result = cli("--report", PREFLIGHT / "invalid" / name)
    assert result.returncode == 1
    assert f"reason={code}" in result.stderr, output(result)
    assert f"path={path}" in result.stderr, output(result)


def test_valid_summary_fixture() -> None:
    result = cli("--summary", SUMMARY / "ok.json")
    assert result.returncode == 0, output(result)
    assert "OK summary" in result.stdout


def test_valid_blocked_summary_fixture() -> None:
    result = cli("--summary", SUMMARY / "terminal-blocked.json")
    assert result.returncode == 0, output(result)


@pytest.mark.parametrize(
    ("name", "code", "path"),
    [
        ("serial-recorded-true.json", "E_SERIAL_RECORDED", "/serial_recorded"),
        ("absolute-report-path.json", "E_SCHEMA_PATTERN", "/report_path"),
        ("absolute-report-path.json", "E_REPORT_PATH_NOT_RELATIVE", "/report_path"),
        ("path-traversal.json", "E_SCHEMA_PATTERN", "/report_path"),
        ("missing-field.json", "E_SCHEMA_REQUIRED", "/report_sha256"),
        ("token-field.json", "E_SCHEMA_ADDITIONAL_PROPERTY", "/"),
        ("token-alias.json", "E_TOKEN_PRESENT", "/alias"),
        ("token-report-path.json", "E_TOKEN_PRESENT", "/report_path"),
    ],
)
def test_invalid_summary_fixtures(name: str, code: str, path: str) -> None:
    result = cli("--summary", SUMMARY / "invalid" / name)
    assert result.returncode == 1
    assert f"reason={code}" in result.stderr, output(result)
    assert f"path={path}" in result.stderr, output(result)


def test_strict_binding_ok(tmp_path: Path) -> None:
    shutil.copy(PREFLIGHT / "terminal-ok.json", tmp_path / "report.json")
    summary = copy_summary(tmp_path)
    result = cli(
        "--report",
        tmp_path / "report.json",
        "--summary",
        summary,
        "--strict",
    )
    assert result.returncode == 0, output(result)
    assert "OK binding" in result.stdout


def test_strict_binding_hash_mismatch(tmp_path: Path) -> None:
    shutil.copy(PREFLIGHT / "terminal-ok.json", tmp_path / "report.json")
    summary = copy_summary(tmp_path, report_sha256="0" * 64)
    result = cli(
        "--report",
        tmp_path / "report.json",
        "--summary",
        summary,
        "--strict",
    )
    assert result.returncode == 1
    assert "E_SUMMARY_REPORT_HASH_MISMATCH" in result.stderr


def test_strict_binding_alias_mismatch(tmp_path: Path) -> None:
    shutil.copy(PREFLIGHT / "terminal-ok.json", tmp_path / "report.json")
    summary = copy_summary(tmp_path, alias="other-device")
    result = cli(
        "--report",
        tmp_path / "report.json",
        "--summary",
        summary,
        "--strict",
    )
    assert result.returncode == 1
    assert "E_SUMMARY_ALIAS_MISMATCH" in result.stderr


def test_strict_binding_outcome_mismatch(tmp_path: Path) -> None:
    blocked = PREFLIGHT / "terminal-blocked-fln1-7.json"
    shutil.copy(blocked, tmp_path / "report.json")
    summary = copy_summary(
        tmp_path,
        report_sha256=hashlib.sha256(blocked.read_bytes()).hexdigest(),
    )
    result = cli(
        "--report",
        tmp_path / "report.json",
        "--summary",
        summary,
        "--strict",
    )
    assert result.returncode == 1
    assert "E_SUMMARY_OUTCOME_MISMATCH" in result.stderr


def test_strict_requires_canonical_report_path(tmp_path: Path) -> None:
    (tmp_path / "sub").mkdir()
    shutil.copy(PREFLIGHT / "terminal-ok.json", tmp_path / "sub" / "report.json")
    summary = copy_summary(tmp_path, report_path="sub/report.json")
    result = cli("--summary", summary, "--strict")
    assert result.returncode == 1
    assert "E_REPORT_PATH_NOT_CANONICAL" in result.stderr


def test_strict_missing_report(tmp_path: Path) -> None:
    summary = copy_summary(tmp_path)
    result = cli("--summary", summary, "--strict")
    assert result.returncode == 1
    assert "E_SUMMARY_REPORT_MISSING" in result.stderr


def test_strict_rejects_non_iso_timestamp(tmp_path: Path) -> None:
    report = read_json(PREFLIGHT / "terminal-ok.json")
    report["started_at_utc"] = "not-a-timestamp"
    target = tmp_path / "report.json"
    write_json(target, report)

    relaxed = cli("--report", target)
    assert relaxed.returncode == 0, output(relaxed)

    strict = cli("--report", target, "--strict")
    assert strict.returncode == 1
    assert "E_SCHEMA_FORMAT" in strict.stderr
    assert "path=/started_at_utc" in strict.stderr


def test_schema_dir_override(tmp_path: Path) -> None:
    shutil.copy(
        ROOT / "tools" / "fastlane-build" / "preflight-report.schema.json",
        tmp_path / "preflight-report.schema.json",
    )
    shutil.copy(HERE / "run-summary.schema.json", tmp_path / "run-summary.schema.json")
    result = cli(
        "--report",
        PREFLIGHT / "terminal-ok.json",
        "--schema-dir",
        tmp_path,
    )
    assert result.returncode == 0, output(result)


def test_schema_id_mismatch_is_a_config_error(tmp_path: Path) -> None:
    shutil.copy(
        ROOT / "tools" / "fastlane-build" / "preflight-report.schema.json",
        tmp_path / "preflight-report.schema.json",
    )
    schema = read_json(HERE / "run-summary.schema.json")
    schema["$id"] = "urn:orchestella:fastlane:dv01:run:v2"
    write_json(tmp_path / "run-summary.schema.json", schema)
    result = cli("--summary", SUMMARY / "ok.json", "--schema-dir", tmp_path)
    assert result.returncode == 2
    assert "E_SCHEMA_ID_MISMATCH" in result.stderr
    assert "file=run-summary.schema.json" in result.stderr


def test_missing_input_is_a_config_error_without_path_echo() -> None:
    missing = "/no/such/canary-path/report.json"
    result = cli("--report", missing)
    assert result.returncode == 2
    assert "E_INPUT_NOT_FOUND" in result.stderr
    assert missing not in output(result)


def test_malformed_json_does_not_echo_input(tmp_path: Path) -> None:
    canary = "CANARY_SERIAL_0001"
    target = tmp_path / "broken.json"
    target.write_text('{"device_alias": "' + canary + '"', encoding="utf-8")
    result = cli("--report", target)
    assert result.returncode == 2
    assert "E_INPUT_NOT_JSON" in result.stderr
    assert canary not in output(result)


def test_no_target_is_a_usage_error() -> None:
    result = cli()
    assert result.returncode == 2
    assert "E_USAGE" in result.stderr


def test_unknown_argument_does_not_echo_its_value() -> None:
    canary = "CANARY_SERIAL_0001"
    result = cli("--report", PREFLIGHT / "terminal-ok.json", f"--{canary}", canary)
    assert result.returncode == 2
    assert "E_USAGE" in result.stderr
    assert canary not in output(result)


PREFLIGHT_CANARIES = (
    "CANARY_MODEL_X",
    "E/AVC: denied",
    "/data/app/canary",
    "CANARY_SERIAL_0001",
    "abcdef0123456789abcdef0123456789",
    "canary@example.invalid",
)


def test_preflight_diagnostics_do_not_echo_sensitive_values() -> None:
    result = cli("--report", PREFLIGHT / "invalid" / "sensitive-values.json")
    assert result.returncode == 1
    text = output(result)
    for canary in PREFLIGHT_CANARIES:
        assert canary not in text, canary
    assert "FAIL preflight" in text


SUMMARY_CANARIES = (
    "canary@example.invalid",
    "CANARY_MODEL_X",
    "/home/canary/secret/report.json",
    "E/AVC: denied",
    "/data/app/canary",
    "CANARY_TOKEN_abcdef0123456789",
)


def test_summary_diagnostics_do_not_echo_sensitive_values() -> None:
    result = cli("--summary", SUMMARY / "invalid" / "sensitive-values.json")
    assert result.returncode == 1
    text = output(result)
    for canary in SUMMARY_CANARIES:
        assert canary not in text, canary
    assert "FAIL summary" in text


def test_extra_property_name_is_not_echoed(tmp_path: Path) -> None:
    canary_key = "CANARY_SERIAL_0001"
    document = read_json(SUMMARY / "ok.json")
    document[canary_key] = "x"
    target = tmp_path / "run-summary.json"
    write_json(target, document)
    result = cli("--summary", target)
    assert result.returncode == 1
    assert "E_SCHEMA_ADDITIONAL_PROPERTY" in result.stderr
    assert canary_key not in output(result)
