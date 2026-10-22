#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Device-independent host runner for Fast Lane DV-01B.

The runner owns the host half of one bounded QA run:

1. install the QA APK and its instrumentation APK with ``adb install -r -g``;
2. best-effort grant the two AVF permissions (a failure blocks the run);
3. launch ``AvfQaProbeInstrumentation`` with the contract ``qaMode``;
4. pull the device report with ``run-as`` (never root);
5. archive a sanitized run under
   ``<artifact-root>/protected-fastlane/<UTC date>/<mode>/<token>/``.

It never records a serial, the run token, raw adb output, logcat or a local
absolute path.  The token only appears in the run directory name and in
process memory.  ``commands.txt`` is a placeholder-only audit plan.

Standard library only.  See ``README.md`` next to this file.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

sys.dont_write_bytecode = True

RUN_SCHEMA = "orchestella.fastlane.dv01.run.v1"
PREFLIGHT_SCHEMA = "orchestella.fastlane.dv01.preflight.v1"
FULL_SCHEMA = "orchestella.fastlane.dv01.v1"
QUALIFICATION_KIND = "avf_qa_probe"

MODES = ("qualification", "preflight", "full")
QA_MODES = {
    "qualification": "qualification",
    "preflight": "fastlane-dv01-preflight",
    "full": "fastlane-dv01",
}
REPORT_NAMES = {
    "qualification": "probe-{token}.json",
    "preflight": "fastlane-dv01-preflight-{token}.json",
    "full": "fastlane-dv01-{token}.json",
}
BLOCKED_OUTCOMES = {
    "qualification": "QUALIFICATION_BLOCKED",
    "preflight": "PREFLIGHT_BLOCKED_EXTERNAL",
    "full": "FULL_BLOCKED",
}
SUCCESS_OUTCOMES = {
    "qualification": "QUALIFICATION_OBSERVED",
}

DEFAULT_ALIAS = "qa-device"
DEFAULT_QA_PACKAGE = "io.github.nyzeep.orchestella.qa.dv01"
QA_RUNNER = "io.github.nyzeep.orchestella.AvfQaProbeInstrumentation"
DEVICE_REPORT_DIR = "files/avf-qa"
PERMISSIONS = (
    "android.permission.MANAGE_VIRTUAL_MACHINE",
    "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
)
PREFLIGHT_STAGES = (
    "parameter_validation",
    "platform_facts",
    "create",
    "run",
    "payload_ready",
    "control_hello",
    "terminal_pty",
    "stop",
    "delete",
)
PREFLIGHT_NOT_TESTED = (
    "encrypted_storage_persistence",
    "20_cycles",
    "negative_cases",
)
PREFLIGHT_OUTCOMES = (
    "PREFLIGHT_TERMINAL_OK",
    "PREFLIGHT_TERMINAL_BLOCKED",
    "PREFLIGHT_BLOCKED_EXTERNAL",
)
FULL_OUTCOMES = {
    "PASS",
    "FAILED",
    "REJECTED",
    "BLOCKED_EXTERNAL",
    "DV01_CORE_PASS_TERMINAL_BLOCKED",
}
PREFLIGHT_STOP_STATES = ("CONFIRMED", "UNCONFIRMED", "ALREADY_STOPPED", "NOT_TESTED")

QUALIFICATION_REQUIRED = {
    "schema_version",
    "kind",
    "qa_package",
    "mode",
    "device_facts",
    "qualification",
    "stage_results",
}
PREFLIGHT_REQUIRED = {
    "schema",
    "mode",
    "device_alias",
    "sdk_int",
    "abi",
    "virtualization_framework",
    "protected_bit",
    "permission",
    "apk_sha256",
    "payload_sha256",
    "toolbox_manifest_sha256",
    "stage_results",
    "terminal",
    "stop",
    "outcome",
    "started_at_utc",
    "finished_at_utc",
    "not_tested",
}
FULL_REQUIRED = {
    "schema",
    "device_alias",
    "sdk_int",
    "build_id",
    "apk_sha256",
    "payload_sha256",
    "toolbox_manifest_sha256",
    "auth_token",
    "stage_results",
    "cycles",
    "stop_timings_ms",
    "failure_codes",
    "negative_cases",
    "not_tested",
    "outcome",
    "started_at_utc",
    "finished_at_utc",
}
SUMMARY_REQUIRED = {
    "schema",
    "mode",
    "alias",
    "outcome",
    "reason_code",
    "report_path",
    "report_sha256",
    "apk_sha256",
    "test_apk_sha256",
    "started_at_utc",
    "finished_at_utc",
    "adb_invoked",
    "serial_recorded",
}

ALIAS_RE = re.compile(r"[A-Za-z0-9._-]{1,32}")
TOKEN_RE = re.compile(r"[0-9a-f]{16,64}")
SERIAL_RE = re.compile(r"[A-Za-z0-9._:-]{1,128}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
PACKAGE_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+")
DATE_RE = re.compile(r"[0-9]{8}")
REASON_CODE_RE = re.compile(r"[A-Z][A-Z0-9_]{1,63}")
NOT_TESTED_RE = re.compile(r"[a-z0-9_]{1,64}")

ADB_TIMEOUT_SECONDS = 120
INSTALL_TIMEOUT_SECONDS = 300
INSTRUMENT_TIMEOUT_SECONDS = 3600
MAX_REPORT_BYTES = 2 * 1024 * 1024
MAX_SCHEMA_OUTPUT = 64 * 1024

EXIT_OK = 0
EXIT_RUN_FAILED = 1
EXIT_ARGUMENTS = 2
EXIT_DEVICE_BLOCKED = 3

ARGUMENT_REASONS = {
    "ALIAS_REJECTED",
    "TOKEN_REJECTED",
    "SERIAL_REJECTED",
    "ARTIFACT_ROOT_REJECTED",
    "APK_NOT_FOUND",
    "TEST_APK_NOT_FOUND",
    "TOOLBOX_SHA_REJECTED",
    "QA_PACKAGE_REJECTED",
}

REPO_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR_RELATIVE = Path("tools") / "fastlane-schema" / "validate.py"


def _resolved_temp_roots() -> tuple[Path, ...]:
    candidates = {Path("/tmp"), Path("/var/tmp"), Path("/dev/shm")}
    try:
        candidates.add(Path(tempfile.gettempdir()))
    except OSError:
        pass
    resolved = set()
    for candidate in candidates:
        try:
            resolved.add(candidate.resolve())
        except OSError:
            resolved.add(candidate)
    return tuple(sorted(resolved, key=str))


TEMP_ROOTS = _resolved_temp_roots()


class RunnerError(Exception):
    """A fixed reason code; never attach a value, path or OS error text."""

    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


@dataclass(frozen=True)
class Plan:
    mode: str
    alias: str
    token: str
    apk: Path
    test_apk: Path
    apk_sha256: str
    test_apk_sha256: str
    toolbox_sha: str | None
    serial: str | None
    artifact_root: Path
    adb: str
    qa_package: str
    dry_run: bool

    @property
    def qa_mode(self) -> str:
        return QA_MODES[self.mode]

    @property
    def test_package(self) -> str:
        return f"{self.qa_package}.test"

    @property
    def report_device_path(self) -> str:
        return f"{DEVICE_REPORT_DIR}/{REPORT_NAMES[self.mode].format(token=self.token)}"


def utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def utc_date() -> str:
    return datetime.now(UTC).strftime("%Y%m%d")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n").encode(
        "utf-8"
    )


def _parse_json(data: bytes) -> object:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise RunnerError("REPORT_JSON_INVALID")
            result[key] = value
        return result

    if len(data) > MAX_REPORT_BYTES:
        raise RunnerError("REPORT_TOO_LARGE")
    try:
        text = data.decode("utf-8-sig")
    except UnicodeError:
        raise RunnerError("REPORT_ENCODING_INVALID") from None
    try:
        return json.loads(
            text,
            object_pairs_hook=unique,
            parse_constant=lambda _value: (_ for _ in ()).throw(
                RunnerError("REPORT_JSON_INVALID")
            ),
        )
    except RunnerError:
        raise
    except (json.JSONDecodeError, RecursionError):
        raise RunnerError("REPORT_JSON_INVALID") from None


def _resolve_adb(explicit: str | None) -> str:
    if explicit:
        candidate = Path(explicit).expanduser()
        if not candidate.is_file():
            raise RunnerError("ADB_UNAVAILABLE")
        return str(candidate.resolve())
    found = shutil.which("adb")
    if found:
        return found
    for candidate in ("/opt/android-sdk/platform-tools/adb", "/usr/bin/adb"):
        if Path(candidate).is_file():
            return candidate
    raise RunnerError("ADB_UNAVAILABLE")


def _validate_root(value: str) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise RunnerError("ARTIFACT_ROOT_REJECTED")
    resolved = path.resolve()
    if resolved == REPO_ROOT or REPO_ROOT in resolved.parents:
        raise RunnerError("ARTIFACT_ROOT_REJECTED")
    if any(
        resolved == temp_root or temp_root in resolved.parents
        for temp_root in TEMP_ROOTS
    ):
        raise RunnerError("ARTIFACT_ROOT_REJECTED")
    if resolved.exists() and not resolved.is_dir():
        raise RunnerError("ARTIFACT_ROOT_REJECTED")
    return resolved


def _validate_alias(value: str) -> str:
    if not ALIAS_RE.fullmatch(value):
        raise RunnerError("ALIAS_REJECTED")
    return value


def _validate_token(value: str) -> str:
    if not TOKEN_RE.fullmatch(value):
        raise RunnerError("TOKEN_REJECTED")
    return value


def _validate_serial(value: str | None) -> str | None:
    if value is None:
        return None
    if not SERIAL_RE.fullmatch(value):
        raise RunnerError("SERIAL_REJECTED")
    return value


def _validate_package(value: str, reason: str) -> str:
    if not PACKAGE_RE.fullmatch(value) or ".qa." not in value:
        raise RunnerError(reason)
    return value


def _require_file(path_value: str, reason: str) -> Path:
    path = Path(path_value).expanduser()
    if not path.is_absolute():
        path = path.resolve()
    if not path.is_file():
        raise RunnerError(reason)
    return path.resolve()


def _parse_arguments(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="run_dv01.py",
        description="Device-independent Fast Lane DV-01B host runner.",
    )
    parser.add_argument("--mode", required=True, choices=MODES)
    parser.add_argument("--alias", default=DEFAULT_ALIAS)
    parser.add_argument("--token")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--test-apk", required=True)
    parser.add_argument("--toolbox-sha")
    parser.add_argument("--serial")
    parser.add_argument("--artifact-root", required=True)
    parser.add_argument("--adb")
    parser.add_argument("--qa-package", default=DEFAULT_QA_PACKAGE)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(argv)


def _build_plan(args: argparse.Namespace) -> Plan:
    alias = _validate_alias(args.alias)
    apk = _require_file(args.apk, "APK_NOT_FOUND")
    test_apk = _require_file(args.test_apk, "TEST_APK_NOT_FOUND")
    toolbox_sha = args.toolbox_sha
    if toolbox_sha is not None:
        if not SHA256_RE.fullmatch(toolbox_sha):
            raise RunnerError("TOOLBOX_SHA_REJECTED")
    elif args.mode in ("preflight", "full"):
        raise RunnerError("TOOLBOX_SHA_REJECTED")
    serial = args.serial if args.serial is not None else os.environ.get("ANDROID_SERIAL")
    serial = _validate_serial(serial or None)
    artifact_root = _validate_root(args.artifact_root)
    token = _choose_token(artifact_root, args.token)
    adb = _resolve_adb(args.adb) if not args.dry_run else (args.adb or "adb")
    qa_package = _validate_package(args.qa_package, "QA_PACKAGE_REJECTED")
    if args.mode in ("preflight", "full") and ".qa.dv01" not in qa_package:
        raise RunnerError("QA_PACKAGE_REJECTED")
    return Plan(
        mode=args.mode,
        alias=alias,
        token=token,
        apk=apk,
        test_apk=test_apk,
        apk_sha256=sha256_file(apk),
        test_apk_sha256=sha256_file(test_apk),
        toolbox_sha=toolbox_sha,
        serial=serial,
        artifact_root=artifact_root,
        adb=adb,
        qa_package=qa_package,
        dry_run=args.dry_run,
    )


def generate_token() -> str:
    return secrets.token_hex(8)


def _token_in_use(root: Path, token: str) -> bool:
    pattern = f"*/*/{token}"
    base = root / "protected-fastlane"
    if not base.is_dir():
        return False
    return any(candidate.exists() for candidate in base.glob(pattern))


def _choose_token(root: Path, explicit: str | None) -> str:
    if explicit is not None:
        token = _validate_token(explicit)
        if _token_in_use(root, token):
            raise RunnerError("ARTIFACT_CONFLICT")
        return token
    for _attempt in range(8):
        token = generate_token()
        if not _token_in_use(root, token):
            return token
    raise RunnerError("TOKEN_EXHAUSTED")


def _mkdir_private(path: Path) -> None:
    if path.is_symlink():
        raise RunnerError("ARTIFACT_ROOT_REJECTED")
    if path.exists():
        if not path.is_dir():
            raise RunnerError("ARTIFACT_ROOT_REJECTED")
        return
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except OSError:
        pass


def _prepare_run_dir(plan: Plan, date_name: str) -> Path:
    root = plan.artifact_root
    _mkdir_private(root)
    base = root / "protected-fastlane"
    _mkdir_private(base)
    date_dir = base / date_name
    _mkdir_private(date_dir)
    mode_dir = date_dir / plan.mode
    _mkdir_private(mode_dir)
    run_dir = mode_dir / plan.token
    try:
        run_dir.mkdir(exist_ok=False, mode=0o700)
    except FileExistsError:
        raise RunnerError("ARTIFACT_CONFLICT") from None
    try:
        os.chmod(run_dir, 0o700)
    except OSError:
        pass
    return run_dir


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.parent / f".{path.name}.tmp-{uuid.uuid4().hex}"
    descriptor = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(pending, path)
    except FileExistsError:
        raise RunnerError("ARTIFACT_WRITE_FAILED") from None
    finally:
        try:
            pending.unlink()
        except FileNotFoundError:
            pass
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def _display(plan: Plan, argv: list[str]) -> str:
    """Render an adb argv with placeholders, never with run-specific values."""
    rendered = []
    previous = None
    for index, argument in enumerate(argv):
        if index == 0:
            rendered.append("adb")
        elif previous == "-s":
            rendered.append("<serial>")
        elif previous == "qaRunToken":
            rendered.append("<token>")
        elif previous == "qaDeviceAlias":
            rendered.append("<alias>")
        elif argument == str(plan.apk):
            rendered.append("<apk>")
        elif argument == str(plan.test_apk):
            rendered.append("<test-apk>")
        elif argument == plan.report_device_path:
            rendered.append("<report-path>")
        else:
            rendered.append(argument)
        previous = argument
    return " ".join(rendered)


def _base_argv(plan: Plan, command: list[str]) -> list[str]:
    prefix = [plan.adb]
    if plan.serial is not None:
        prefix += ["-s", plan.serial]
    return prefix + command


def _planned_commands(plan: Plan) -> list[list[str]]:
    commands = [
        ["install", "-r", "-g", str(plan.apk)],
        ["install", "-r", "-g", str(plan.test_apk)],
        ["shell", "pm", "grant", plan.qa_package, PERMISSIONS[0]],
        ["shell", "pm", "grant", plan.qa_package, PERMISSIONS[1]],
    ]
    instrument = [
        "shell",
        "am",
        "instrument",
        "-w",
        "-e",
        "qaMode",
        plan.qa_mode,
        "-e",
        "qaRunToken",
        plan.token,
        "-e",
        "qaDeviceAlias",
        plan.alias,
    ]
    if plan.mode != "qualification" and plan.toolbox_sha is not None:
        instrument += ["-e", "qaToolboxSha", plan.toolbox_sha]
    instrument.append(f"{plan.test_package}/{QA_RUNNER}")
    commands.append(instrument)
    commands.append(
        ["shell", "run-as", plan.qa_package, "cat", plan.report_device_path]
    )
    return commands


class Adb:
    def __init__(self, plan: Plan) -> None:
        self.plan = plan
        self.executed: list[str] = []

    def run(self, command: list[str], timeout: int) -> subprocess.CompletedProcess:
        argv = _base_argv(self.plan, command)
        self.executed.append(_display(self.plan, argv))
        try:
            return subprocess.run(
                argv,
                capture_output=True,
                check=False,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            raise RunnerError("ADB_TIMEOUT") from None
        except OSError:
            raise RunnerError("ADB_INVOCATION_FAILED") from None


def _output(result: subprocess.CompletedProcess) -> str:
    data = (result.stdout or b"") + (result.stderr or b"")
    return data.decode("utf-8", errors="replace")


def _grant_failed(result: subprocess.CompletedProcess) -> bool:
    if result.returncode != 0:
        return True
    text = _output(result).strip().lower()
    if not text:
        return False
    markers = ("error", "exception", "not allowed", "unknown permission", "denied")
    return any(marker in text for marker in markers)


def _validate_report(report: object, plan: Plan) -> None:
    if not isinstance(report, dict):
        raise RunnerError("REPORT_REQUIRED_FIELDS_MISSING")
    required = {
        "qualification": QUALIFICATION_REQUIRED,
        "preflight": PREFLIGHT_REQUIRED,
        "full": FULL_REQUIRED,
    }[plan.mode]
    if not required.issubset(report):
        raise RunnerError("REPORT_REQUIRED_FIELDS_MISSING")

    if plan.mode == "qualification":
        if report.get("kind") != QUALIFICATION_KIND:
            raise RunnerError("REPORT_SCHEMA_MISMATCH")
        if report.get("mode") != "qualification":
            raise RunnerError("REPORT_MODE_MISMATCH")
        if report.get("qa_package") != plan.qa_package:
            raise RunnerError("REPORT_PACKAGE_MISMATCH")
        return

    if plan.mode == "preflight":
        if report.get("schema") != PREFLIGHT_SCHEMA:
            raise RunnerError("REPORT_SCHEMA_MISMATCH")
        if report.get("mode") != QA_MODES["preflight"]:
            raise RunnerError("REPORT_MODE_MISMATCH")
        if report.get("permission") not in (
            "GRANTED",
            "DENIED",
            "UNKNOWN",
            "NOT_APPLICABLE",
        ):
            raise RunnerError("REPORT_PERMISSION_INVALID")
        if report.get("outcome") not in PREFLIGHT_OUTCOMES:
            raise RunnerError("REPORT_OUTCOME_INVALID")
        if report.get("stop") not in PREFLIGHT_STOP_STATES:
            raise RunnerError("REPORT_STOP_INVALID")
        stages = report.get("stage_results")
        if not isinstance(stages, dict) or not set(PREFLIGHT_STAGES).issubset(stages):
            raise RunnerError("REPORT_STAGE_RESULTS_INVALID")
        stage_keys = {"status", "reason_code", "detail"}
        stage_states = {"pass", "fail", "blocked", "not-tested"}
        for stage in PREFLIGHT_STAGES:
            entry = stages.get(stage)
            if not isinstance(entry, dict):
                raise RunnerError("REPORT_STAGE_RESULTS_INVALID")
            keys = set(entry)
            if not {"status", "reason_code"}.issubset(keys):
                raise RunnerError("REPORT_STAGE_RESULTS_INVALID")
            if not keys.issubset(stage_keys):
                raise RunnerError("REPORT_STAGE_RESULTS_INVALID")
            if entry.get("status") not in stage_states:
                raise RunnerError("REPORT_STAGE_RESULTS_INVALID")
            reason_code = entry.get("reason_code")
            if not isinstance(reason_code, str) or not REASON_CODE_RE.fullmatch(
                reason_code
            ):
                raise RunnerError("REPORT_STAGE_RESULTS_INVALID")
        terminal = report.get("terminal")
        terminal_keys = {"status", "reason_code", "fln1_code", "exec_stage", "errno"}
        if not isinstance(terminal, dict) or set(terminal) != terminal_keys:
            raise RunnerError("REPORT_TERMINAL_INVALID")
        if terminal.get("status") not in ("pass", "fail", "blocked"):
            raise RunnerError("REPORT_TERMINAL_INVALID")
        terminal_reason = terminal.get("reason_code")
        if not isinstance(terminal_reason, str) or not REASON_CODE_RE.fullmatch(
            terminal_reason
        ):
            raise RunnerError("REPORT_TERMINAL_INVALID")
        not_tested = report.get("not_tested")
        if not isinstance(not_tested, list):
            raise RunnerError("REPORT_NOT_TESTED_INVALID")
        for value in not_tested:
            if not isinstance(value, str) or not NOT_TESTED_RE.fullmatch(value):
                raise RunnerError("REPORT_NOT_TESTED_INVALID")
        if len(set(not_tested)) != len(not_tested):
            raise RunnerError("REPORT_NOT_TESTED_INVALID")
        for value in PREFLIGHT_NOT_TESTED:
            if value not in not_tested:
                raise RunnerError("REPORT_NOT_TESTED_INVALID")

        outcome = report.get("outcome")
        if outcome == "PREFLIGHT_TERMINAL_OK":
            if (
                terminal.get("status") != "pass"
                or terminal.get("reason_code") != "PREFLIGHT_TERMINAL_OK"
                or report.get("stop") != "CONFIRMED"
            ):
                raise RunnerError("REPORT_OUTCOME_INVALID")
            for stage in ("terminal_pty", "stop", "delete"):
                if stages[stage].get("status") != "pass":
                    raise RunnerError("REPORT_OUTCOME_INVALID")
        elif outcome == "PREFLIGHT_BLOCKED_EXTERNAL":
            allowed = {
                "PREFLIGHT_BLOCKED_EXTERNAL",
                "HIDDEN_API_BLOCKED",
                "PERMISSION_DENIED",
                "PROTECTED_UNAVAILABLE",
                "PAYLOAD_VERIFY_FAILED",
                "QA_ARGUMENTS_REJECTED",
                "PREFLIGHT_FAILED",
            }
            if terminal.get("reason_code") not in allowed:
                raise RunnerError("REPORT_OUTCOME_INVALID")
        if report.get("stop") == "UNCONFIRMED" and stages["delete"].get(
            "status"
        ) != "blocked":
            raise RunnerError("REPORT_OUTCOME_INVALID")
    else:
        if report.get("schema") != FULL_SCHEMA:
            raise RunnerError("REPORT_SCHEMA_MISMATCH")
        if report.get("outcome") not in FULL_OUTCOMES:
            raise RunnerError("REPORT_OUTCOME_INVALID")

    if report.get("device_alias") != plan.alias:
        raise RunnerError("REPORT_ALIAS_MISMATCH")


def _validate_summary_local(summary: dict) -> None:
    if not SUMMARY_REQUIRED.issubset(summary):
        raise RunnerError("SUMMARY_REQUIRED_FIELDS_MISSING")
    if summary.get("schema") != RUN_SCHEMA:
        raise RunnerError("SUMMARY_SCHEMA_MISMATCH")
    if summary.get("mode") not in MODES:
        raise RunnerError("SUMMARY_MODE_INVALID")
    if summary.get("serial_recorded") is not False:
        raise RunnerError("SUMMARY_SERIAL_FLAG_INVALID")
    report_path = summary.get("report_path")
    if report_path is not None:
        if not isinstance(report_path, str):
            raise RunnerError("SUMMARY_REPORT_PATH_INVALID")
        if not report_path or report_path.startswith("/"):
            raise RunnerError("SUMMARY_REPORT_PATH_INVALID")
        if "/" in report_path or "\\" in report_path:
            raise RunnerError("SUMMARY_REPORT_PATH_INVALID")


def _external_validator() -> Path | None:
    candidate = REPO_ROOT / VALIDATOR_RELATIVE
    return candidate if candidate.is_file() else None


def _run_external_validator(
    validator: Path, flag: str, data: bytes, run_dir: Path
) -> bool:
    pending = run_dir / f".validate-{uuid.uuid4().hex}.json"
    descriptor = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            result = subprocess.run(
                [sys.executable, str(validator), flag, str(pending)],
                capture_output=True,
                check=False,
                timeout=60,
            )
        except (OSError, subprocess.TimeoutExpired):
            return False
        if len(result.stdout or b"") + len(result.stderr or b"") > MAX_SCHEMA_OUTPUT:
            return False
        return result.returncode == 0
    finally:
        try:
            pending.unlink()
        except FileNotFoundError:
            pass


def _validation_state(
    validator: Path | None, status: str
) -> dict:
    if validator is None:
        return {
            "validator": "local-required-keys",
            "full_schema_validation": False,
            "status": "LOCAL_ONLY" if status == "PASS" else status,
            "note": "tools/fastlane-schema/validate.py not present",
        }
    return {
        "validator": "tools/fastlane-schema/validate.py",
        "full_schema_validation": True,
        "status": status,
    }


def _readme_text(
    plan: Plan,
    outcome: str,
    reason: str | None,
    validation: dict,
    report_present: bool,
) -> bytes:
    reason_line = reason if reason is not None else "none"
    report_line = "report.json (canonical copy)" if report_present else "absent"
    if validation["full_schema_validation"]:
        schema_line = (
            "full schema validation executed with tools/fastlane-schema/validate.py"
        )
    else:
        schema_line = (
            "full schema validation not executed (local required-key checks only); "
            "tools/fastlane-schema/validate.py not present"
        )
    lines = [
        "Orchestella Fast Lane DV-01B host run",
        f"Mode: {plan.mode}",
        f"QA mode: {plan.qa_mode}",
        f"Outcome: {outcome}",
        f"Reason: {reason_line}",
        f"Report: {report_line}",
        f"Schema: {schema_line}",
        (
            "Redaction: serial, run token, raw adb output, logcat and local "
            "absolute paths are not stored here."
        ),
        (
            "The run token appears only in this directory name; commands.txt "
            "uses <token> and <serial> placeholders."
        ),
        "",
    ]
    return "\n".join(lines).encode("utf-8")


def _sha256sums(run_dir: Path) -> bytes:
    paths = []
    for relative in ("report.json", "run-summary.json", "commands.txt"):
        candidate = run_dir / relative
        if candidate.is_file():
            paths.append(candidate)
    artifacts = run_dir / "artifacts"
    if artifacts.is_dir():
        paths.extend(sorted(path for path in artifacts.rglob("*") if path.is_file()))
    lines = []
    for path in sorted(paths, key=lambda item: item.relative_to(run_dir).as_posix()):
        relative = path.relative_to(run_dir).as_posix()
        lines.append(f"{sha256_file(path)}  {relative}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def _success_outcome(plan: Plan, report: dict) -> str:
    if plan.mode == "qualification":
        return SUCCESS_OUTCOMES[plan.mode]
    return str(report.get("outcome"))


def _report_reason(plan: Plan, report: dict) -> str | None:
    if plan.mode == "preflight" and report.get("outcome") != "PREFLIGHT_TERMINAL_OK":
        terminal = report.get("terminal")
        if isinstance(terminal, dict):
            code = terminal.get("reason_code")
            if isinstance(code, str) and code != "PREFLIGHT_TERMINAL_OK":
                return code
        stop = report.get("stop")
        if stop == "UNCONFIRMED":
            return "STOP_UNCONFIRMED"
        if stop == "ALREADY_STOPPED":
            return "STOP_ALREADY_STOPPED"
        return "PREFLIGHT_TERMINAL_BLOCKED"
    return None


def execute(plan: Plan) -> int:
    started_at = utc_now()
    run_dir = _prepare_run_dir(plan, started_at[:10].replace("-", ""))
    adb = Adb(plan)
    report_bytes: bytes | None = None
    report_sha: str | None = None
    outcome = BLOCKED_OUTCOMES[plan.mode]
    reason: str | None = None
    exit_code = EXIT_RUN_FAILED
    validation_status = "NOT_RUN"

    try:
        result = adb.run(
            ["install", "-r", "-g", str(plan.apk)], INSTALL_TIMEOUT_SECONDS
        )
        if result.returncode != 0 or "Success" not in _output(result):
            raise RunnerError("APK_INSTALL_FAILED")

        result = adb.run(
            ["install", "-r", "-g", str(plan.test_apk)], INSTALL_TIMEOUT_SECONDS
        )
        if result.returncode != 0 or "Success" not in _output(result):
            raise RunnerError("TEST_APK_INSTALL_FAILED")

        grants_failed = False
        for permission in PERMISSIONS:
            result = adb.run(
                ["shell", "pm", "grant", plan.qa_package, permission],
                ADB_TIMEOUT_SECONDS,
            )
            if _grant_failed(result):
                grants_failed = True
        if grants_failed:
            raise RunnerError("PERMISSION_GRANT_FAILED")

        commands = _planned_commands(plan)
        result = adb.run(commands[4], INSTRUMENT_TIMEOUT_SECONDS)
        instrument_text = _output(result)
        if (
            result.returncode != 0
            or "INSTRUMENTATION_FAILED" in instrument_text
            or "Process crashed" in instrument_text
        ):
            raise RunnerError("INSTRUMENTATION_FAILED")

        result = adb.run(commands[5], ADB_TIMEOUT_SECONDS)
        if result.returncode != 0:
            raise RunnerError("REPORT_PULL_FAILED")
        report_bytes = result.stdout or b""
        if not report_bytes.strip():
            raise RunnerError("REPORT_MISSING")
        if plan.token.encode("ascii") in report_bytes:
            raise RunnerError("REPORT_CONTAINS_TOKEN")
        if plan.serial is not None and plan.serial.encode("utf-8") in report_bytes:
            raise RunnerError("REPORT_CONTAINS_SERIAL")

        report = _parse_json(report_bytes)
        _validate_report(report, plan)
        validation_status = "PASS"

        validator = _external_validator()
        if validator is not None and not _run_external_validator(
            validator, "--report", report_bytes, run_dir
        ):
            validation_status = "FAIL"
            raise RunnerError("SCHEMA_VALIDATION_FAILED")

        report_sha = sha256_bytes(report_bytes)
        _atomic_write(run_dir / "report.json", report_bytes)

        outcome = _success_outcome(plan, report)
        reason = _report_reason(plan, report)
        exit_code = (
            EXIT_DEVICE_BLOCKED
            if plan.mode == "preflight" and outcome != "PREFLIGHT_TERMINAL_OK"
            else EXIT_OK
        )
    except RunnerError as failure:
        outcome = BLOCKED_OUTCOMES[plan.mode]
        reason = failure.reason_code
        exit_code = EXIT_RUN_FAILED
        report_bytes = None
        report_sha = None

    commands_text = ("\n".join(adb.executed) + "\n") if adb.executed else ""
    _atomic_write(run_dir / "commands.txt", commands_text.encode("utf-8"))

    validator = _external_validator()
    validation = _validation_state(validator, validation_status)
    summary = {
        "schema": RUN_SCHEMA,
        "mode": plan.mode,
        "qa_mode": plan.qa_mode,
        "alias": plan.alias,
        "outcome": outcome,
        "reason_code": reason,
        "report_path": "report.json" if report_sha is not None else None,
        "report_sha256": report_sha,
        "apk_sha256": plan.apk_sha256,
        "test_apk_sha256": plan.test_apk_sha256,
        "toolbox_sha256": plan.toolbox_sha or "not-tested",
        "started_at_utc": started_at,
        "finished_at_utc": utc_now(),
        "adb_invoked": bool(adb.executed),
        "serial_recorded": False,
        "schema_validation": validation,
        "report_present": report_sha is not None,
    }
    try:
        _validate_summary_local(summary)
    except RunnerError as failure:
        summary["outcome"] = BLOCKED_OUTCOMES[plan.mode]
        summary["reason_code"] = failure.reason_code
        summary["schema_validation"] = _validation_state(validator, "FAIL")
        exit_code = EXIT_RUN_FAILED
    summary_bytes = _json_bytes(summary)

    if validator is not None and not _run_external_validator(
        validator, "--summary", summary_bytes, run_dir
    ):
        summary["outcome"] = BLOCKED_OUTCOMES[plan.mode]
        summary["reason_code"] = "SCHEMA_VALIDATION_FAILED"
        summary["schema_validation"] = _validation_state(validator, "FAIL")
        summary_bytes = _json_bytes(summary)
        exit_code = EXIT_RUN_FAILED

    _atomic_write(run_dir / "run-summary.json", summary_bytes)
    _atomic_write(
        run_dir / "README.txt",
        _readme_text(
            plan,
            summary["outcome"],
            summary["reason_code"],
            summary["schema_validation"],
            summary["report_present"],
        ),
    )
    _atomic_write(run_dir / "SHA256SUMS", _sha256sums(run_dir))
    print(f"RESULT={summary['outcome']}")
    print(f"RUN_DIR={run_dir.relative_to(plan.artifact_root).as_posix()}")
    return exit_code


def main(argv: list[str] | None = None) -> int:
    args = _parse_arguments(argv)
    try:
        plan = _build_plan(args)
    except RunnerError as failure:
        print(f"ERROR={failure.reason_code}", file=sys.stderr)
        return (
            EXIT_ARGUMENTS
            if failure.reason_code in ARGUMENT_REASONS
            else EXIT_RUN_FAILED
        )

    if plan.dry_run:
        for command in _planned_commands(plan):
            print(_display(plan, _base_argv(plan, command)))
        return EXIT_OK

    try:
        return execute(plan)
    except RunnerError as failure:
        print(f"ERROR={failure.reason_code}", file=sys.stderr)
        return (
            EXIT_ARGUMENTS
            if failure.reason_code in ARGUMENT_REASONS
            else EXIT_RUN_FAILED
        )
    except OSError:
        print("ERROR=ARTIFACT_WRITE_FAILED", file=sys.stderr)
        return EXIT_RUN_FAILED


if __name__ == "__main__":
    sys.exit(main())
