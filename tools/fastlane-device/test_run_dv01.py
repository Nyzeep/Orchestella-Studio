#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fake-adb tests for the DV-01B device-independent host runner.

The tests never contact a device.  They exercise the exact adb argv sequence,
the artifact layout, redaction and blocked-run summaries with a generated
executable that impersonates adb.
"""
from __future__ import annotations

import io
import json
import os
import re
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_dv01

SERIAL = "FAKE-SERIAL-9f3a"
TOKEN = "deadbeefdeadbeef"
OTHER_TOKEN = "feedfacefeedface"
TOOLBOX_SHA = "a" * 64
QA_PACKAGE = "io.github.nyzeep.orchestella.qa.dv01"
RUNNER = run_dv01.QA_RUNNER

FAKE_ADB = """#!/usr/bin/env python3
import json
import os
import sys

args = sys.argv[1:]
with open(os.environ["FAKE_ADB_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps(args) + "\\n")

if args[:1] == ["-s"]:
    args = args[2:]

fail = set(filter(None, os.environ.get("FAKE_ADB_FAIL", "").split(",")))

if args[:1] == ["install"]:
    apk = args[-1]
    key = "test_install" if "test" in os.path.basename(apk) else "app_install"
    if key in fail:
        print("Failure [INSTALL_FAILED_FAKE]", file=sys.stderr)
        sys.exit(1)
    print("Success")
    sys.exit(0)

if args[:3] == ["shell", "pm", "grant"]:
    permission = args[-1]
    key = "manage_grant" if permission.endswith("MANAGE_VIRTUAL_MACHINE") else "custom_grant"
    if key in fail:
        print("Error: not allowed", file=sys.stderr)
        sys.exit(1)
    sys.exit(0)

if args[:2] == ["shell", "am"]:
    if "instrument" in fail:
        print("INSTRUMENTATION_FAILED: fake", file=sys.stderr)
        sys.exit(1)
    print("INSTRUMENTATION_CODE: -1")
    sys.exit(0)

if args[:2] in (["exec-out", "run-as"], ["shell", "run-as"]):
    if "pull" in fail:
        print("error: closed", file=sys.stderr)
        sys.exit(1)
    report = os.environ.get("FAKE_ADB_REPORT")
    if report:
        with open(report, "r", encoding="utf-8") as stream:
            sys.stdout.write(stream.read())
    sys.exit(0)

sys.exit(0)
"""


def preflight_report(alias: str = "qa-device", **overrides) -> dict:
    stages = {
        name: {"status": "pass", "reason_code": name.upper() + "_OK"}
        for name in run_dv01.PREFLIGHT_STAGES
    }
    report = {
        "schema": run_dv01.PREFLIGHT_SCHEMA,
        "mode": "fastlane-dv01-preflight",
        "device_alias": alias,
        "sdk_int": 36,
        "abi": "arm64-v8a",
        "virtualization_framework": True,
        "protected_bit": True,
        "permission": "GRANTED",
        "apk_sha256": "b" * 64,
        "payload_sha256": "c" * 64,
        "toolbox_manifest_sha256": TOOLBOX_SHA,
        "stage_results": stages,
        "terminal": {
            "status": "pass",
            "reason_code": "PREFLIGHT_TERMINAL_OK",
            "fln1_code": -1,
            "exec_stage": "not-tested",
            "errno": -1,
        },
        "stop": "CONFIRMED",
        "outcome": "PREFLIGHT_TERMINAL_OK",
        "started_at_utc": "2026-10-22T00:00:00Z",
        "finished_at_utc": "2026-10-22T00:00:01Z",
        "not_tested": list(run_dv01.PREFLIGHT_NOT_TESTED),
    }
    report.update(overrides)
    return report


def qualification_report(alias: str = "qa-device", **overrides) -> dict:
    report = {
        "schema_version": 1,
        "kind": "avf_qa_probe",
        "qa_package": QA_PACKAGE,
        "mode": "qualification",
        "device_facts": {"sdk_int": 36, "abis": ["arm64-v8a"]},
        "qualification": {"access_state": "AVAILABLE"},
        "stage_results": {},
    }
    report.update(overrides)
    return report


def full_report(alias: str = "qa-device", **overrides) -> dict:
    report = {
        "schema": run_dv01.FULL_SCHEMA,
        "device_alias": alias,
        "sdk_int": 36,
        "build_id": "test-build",
        "apk_sha256": "b" * 64,
        "payload_sha256": "c" * 64,
        "toolbox_manifest_sha256": TOOLBOX_SHA,
        "auth_token": "empty",
        "stage_results": {},
        "cycles": [],
        "stop_timings_ms": [],
        "failure_codes": {},
        "negative_cases": [],
        "not_tested": [],
        "outcome": "PASS",
        "started_at_utc": "2026-10-22T00:00:00Z",
        "finished_at_utc": "2026-10-22T00:00:01Z",
    }
    report.update(overrides)
    return report


class RunnerTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        # Tests intentionally run inside the system temp root; production runs
        # reject it. Keep the real roots for the dedicated rejection test.
        self._original_temp_roots = run_dv01.TEMP_ROOTS
        run_dv01.TEMP_ROOTS = ()
        self.artifact_root = self.root / "external-artifacts"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.fake_adb = self.bin / "fake-adb"
        self.fake_adb.write_text(FAKE_ADB, encoding="utf-8")
        self.fake_adb.chmod(0o755)
        self.log = self.root / "adb-calls.jsonl"
        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"fake app apk\n")
        self.test_apk = self.root / "test-apk.apk"
        self.test_apk.write_bytes(b"fake test apk\n")
        self.report_file = self.root / "device-report.json"

    def tearDown(self) -> None:
        run_dv01.TEMP_ROOTS = self._original_temp_roots
        self._temp.cleanup()

    def args(
        self,
        mode: str = "preflight",
        alias: str = "qa-device",
        token: str | None = TOKEN,
        serial: str | None = SERIAL,
        extra: tuple[str, ...] = (),
    ) -> list[str]:
        result = ["--mode", mode, "--alias", alias]
        if token is not None:
            result += ["--token", token]
        result += [
            "--apk",
            str(self.apk),
            "--test-apk",
            str(self.test_apk),
            "--toolbox-sha",
            TOOLBOX_SHA,
            "--artifact-root",
            str(self.artifact_root),
            "--adb",
            str(self.fake_adb),
        ]
        if serial is not None:
            result += ["--serial", serial]
        result += list(extra)
        return result

    def write_report(self, report) -> None:
        if isinstance(report, str):
            self.report_file.write_text(report, encoding="utf-8")
        else:
            self.report_file.write_text(json.dumps(report), encoding="utf-8")

    def invoke(self, args: list[str], env: dict | None = None, report=None):
        if report is not None:
            self.write_report(report)
        environment = {
            "FAKE_ADB_LOG": str(self.log),
            "FAKE_ADB_REPORT": str(self.report_file),
            "ANDROID_SERIAL": "",
        }
        if env:
            environment.update(env)
        stdout = io.StringIO()
        stderr = io.StringIO()
        with (
            mock.patch.dict(os.environ, environment, clear=False),
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            code = run_dv01.main(args)
        return code, stdout.getvalue(), stderr.getvalue()

    def calls(self) -> list[list[str]]:
        if not self.log.exists():
            return []
        return [
            json.loads(line)
            for line in self.log.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def run_dirs(self) -> list[Path]:
        base = self.artifact_root / "protected-fastlane"
        if not base.is_dir():
            return []
        return sorted(path for path in base.glob("*/*/*") if path.is_dir())

    def run_dir(self, token: str = TOKEN) -> Path:
        matches = [path for path in self.run_dirs() if path.name == token]
        self.assertEqual(len(matches), 1, matches)
        return matches[0]

    def summary(self, token: str = TOKEN) -> dict:
        return json.loads(
            (self.run_dir(token) / "run-summary.json").read_text(encoding="utf-8")
        )

    def commands(self, token: str = TOKEN) -> list[str]:
        text = (self.run_dir(token) / "commands.txt").read_text(encoding="utf-8")
        return text.splitlines()

    def assert_clean_run_dir(self, token: str = TOKEN) -> None:
        run_dir = self.run_dir(token)
        self._assert_no_secret(run_dir, token)
        for name in ("run-summary.json", "commands.txt", "README.txt", "SHA256SUMS"):
            self.assertTrue((run_dir / name).is_file(), name)

    def _assert_no_secret(self, run_dir: Path, token: str) -> None:
        for path in run_dir.rglob("*"):
            if not path.is_file():
                continue
            data = path.read_bytes()
            self.assertNotIn(SERIAL.encode("utf-8"), data, path.name)
            self.assertNotIn(token.encode("ascii"), data, path.name)
            self.assertNotIn(str(self.root).encode("utf-8"), data, path.name)

    def test_qualification_command_sequence_and_artifacts(self) -> None:
        code, stdout, stderr = self.invoke(
            self.args(mode="qualification"), report=qualification_report()
        )
        self.assertEqual(code, 0, stderr)
        self.assertIn("RESULT=QUALIFICATION_OBSERVED", stdout)

        calls = self.calls()
        self.assertEqual(
            calls[0], ["-s", SERIAL, "install", "-r", "-g", str(self.apk)]
        )
        self.assertEqual(
            calls[1], ["-s", SERIAL, "install", "-r", "-g", str(self.test_apk)]
        )
        self.assertEqual(
            calls[2],
            [
                "-s",
                SERIAL,
                "shell",
                "pm",
                "grant",
                QA_PACKAGE,
                "android.permission.MANAGE_VIRTUAL_MACHINE",
            ],
        )
        self.assertEqual(
            calls[3],
            [
                "-s",
                SERIAL,
                "shell",
                "pm",
                "grant",
                QA_PACKAGE,
                "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
            ],
        )
        instrument = calls[4]
        self.assertIn("qaMode", instrument)
        self.assertEqual(instrument[instrument.index("qaMode") + 1], "qualification")
        self.assertEqual(instrument[instrument.index("qaRunToken") + 1], TOKEN)
        self.assertEqual(instrument[instrument.index("qaDeviceAlias") + 1], "qa-device")
        self.assertNotIn("qaToolboxSha", instrument)
        self.assertEqual(instrument[-1], f"{QA_PACKAGE}.test/{RUNNER}")
        self.assertEqual(
            calls[5],
            [
                "-s",
                SERIAL,
                "shell",
                "run-as",
                QA_PACKAGE,
                "cat",
                f"files/avf-qa/probe-{TOKEN}.json",
            ],
        )

        run_dir = self.run_dir()
        self.assertEqual(run_dir.parent.name, "qualification")
        self.assertRegex(run_dir.parent.parent.name, r"^[0-9]{8}$")
        self.assertEqual(run_dir.parent.parent.parent.name, "protected-fastlane")
        for name in (
            "report.json",
            "run-summary.json",
            "commands.txt",
            "SHA256SUMS",
            "README.txt",
        ):
            self.assertTrue((run_dir / name).is_file(), name)
        report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(report["kind"], "avf_qa_probe")
        summary = self.summary()
        self.assertEqual(summary["schema"], run_dv01.RUN_SCHEMA)
        self.assertEqual(summary["mode"], "qualification")
        self.assertEqual(summary["qa_mode"], "qualification")
        self.assertEqual(summary["outcome"], "QUALIFICATION_OBSERVED")
        self.assertIsNone(summary["reason_code"])
        self.assertEqual(summary["report_path"], "report.json")
        self.assertFalse(summary["serial_recorded"])
        self.assertTrue(summary["adb_invoked"])
        self.assertFalse(summary["schema_validation"]["full_schema_validation"])
        self.assertEqual(summary["schema_validation"]["status"], "LOCAL_ONLY")
        self.assert_clean_run_dir()

    def test_preflight_command_sequence(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report=preflight_report())
        self.assertEqual(code, 0, stderr)
        calls = self.calls()
        instrument = calls[4]
        self.assertEqual(
            instrument[instrument.index("qaMode") + 1], "fastlane-dv01-preflight"
        )
        self.assertEqual(
            instrument[instrument.index("qaToolboxSha") + 1], TOOLBOX_SHA
        )
        self.assertEqual(
            calls[5][-1], f"files/avf-qa/fastlane-dv01-preflight-{TOKEN}.json"
        )
        summary = self.summary()
        self.assertEqual(summary["outcome"], "PREFLIGHT_TERMINAL_OK")
        self.assertEqual(summary["qa_mode"], "fastlane-dv01-preflight")
        self.assertTrue((self.run_dir() / "report.json").is_file())

    def test_full_command_sequence(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(mode="full"), report=full_report()
        )
        self.assertEqual(code, 0, stderr)
        calls = self.calls()
        instrument = calls[4]
        self.assertEqual(instrument[instrument.index("qaMode") + 1], "fastlane-dv01")
        self.assertEqual(calls[5][-1], f"files/avf-qa/fastlane-dv01-{TOKEN}.json")
        summary = self.summary()
        self.assertEqual(summary["mode"], "full")
        self.assertEqual(summary["outcome"], "PASS")
        self.assertEqual(summary["qa_mode"], "fastlane-dv01")

    def test_alias_default_and_override(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report=preflight_report())
        self.assertEqual(code, 0, stderr)
        instrument = self.calls()[4]
        self.assertEqual(
            instrument[instrument.index("qaDeviceAlias") + 1], "qa-device"
        )

        self.log.unlink()
        code, _stdout, stderr = self.invoke(
            self.args(alias="qa-alt-02", token=OTHER_TOKEN),
            report=preflight_report(alias="qa-alt-02"),
        )
        self.assertEqual(code, 0, stderr)
        instrument = self.calls()[4]
        self.assertEqual(
            instrument[instrument.index("qaDeviceAlias") + 1], "qa-alt-02"
        )
        self.assertEqual(self.summary(OTHER_TOKEN)["alias"], "qa-alt-02")

    def test_invalid_alias_is_rejected(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(alias="bad alias"), report=preflight_report()
        )
        self.assertEqual(code, 2)
        self.assertIn("ERROR=ALIAS_REJECTED", stderr)
        self.assertEqual(self.run_dirs(), [])

    def test_generated_token_is_16_lowercase_hex(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(token=None, mode="qualification"),
            report=qualification_report(),
        )
        self.assertEqual(code, 0, stderr)
        run_dir = self.run_dirs()[0]
        self.assertRegex(run_dir.name, r"^[0-9a-f]{16}$")

    def test_invalid_tokens_are_rejected(self) -> None:
        for token in (
            "ABCDEF0123456789",
            "0123456789abcde",
            "0" * 65,
        ):
            with self.subTest(token=token):
                code, _stdout, stderr = self.invoke(
                    self.args(token=token), report=preflight_report()
                )
                self.assertEqual(code, 2)
                self.assertIn("ERROR=TOKEN_REJECTED", stderr)
                self.assertEqual(self.run_dirs(), [])

    def test_generated_token_collision_retries(self) -> None:
        date_dir = (
            self.artifact_root
            / "protected-fastlane"
            / run_dv01.utc_date()
            / "preflight"
        )
        (date_dir / "deadbeefdeadbeef").mkdir(parents=True)
        with mock.patch(
            "run_dv01.secrets.token_hex",
            side_effect=["deadbeefdeadbeef", "cafebabecafebabe"],
        ):
            code, _stdout, stderr = self.invoke(
                self.args(token=None), report=preflight_report()
            )
        self.assertEqual(code, 0, stderr)
        self.assertTrue(
            any(path.name == "cafebabecafebabe" for path in self.run_dirs())
        )

    def test_explicit_token_conflict_does_not_touch_history(self) -> None:
        existing = (
            self.artifact_root
            / "protected-fastlane"
            / run_dv01.utc_date()
            / "preflight"
            / TOKEN
        )
        existing.mkdir(parents=True)
        marker = existing / "marker.txt"
        marker.write_text("keep\n", encoding="utf-8")
        code, _stdout, stderr = self.invoke(
            self.args(), report=preflight_report()
        )
        self.assertEqual(code, 1)
        self.assertIn("ERROR=ARTIFACT_CONFLICT", stderr)
        self.assertEqual(marker.read_text(encoding="utf-8"), "keep\n")
        self.assertEqual(sorted(path.name for path in existing.iterdir()), ["marker.txt"])
        self.assertFalse(self.log.exists())

    def test_install_failure_writes_blocked_summary(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(), env={"FAKE_ADB_FAIL": "app_install"}, report=preflight_report()
        )
        self.assertEqual(code, 1, stderr)
        summary = self.summary()
        self.assertEqual(summary["outcome"], "PREFLIGHT_BLOCKED_EXTERNAL")
        self.assertEqual(summary["reason_code"], "APK_INSTALL_FAILED")
        self.assertFalse(summary["report_present"])
        self.assertIsNone(summary["report_path"])
        self.assertIsNone(summary["report_sha256"])
        self.assertTrue(summary["adb_invoked"])
        self.assertFalse((self.run_dir() / "report.json").exists())
        self.assertEqual(len(self.commands()), 1)
        self.assertFalse(summary["schema_validation"]["full_schema_validation"])
        self.assert_clean_run_dir()
        readme = (self.run_dir() / "README.txt").read_text(encoding="utf-8")
        self.assertIn("full schema validation not executed", readme)

    def test_test_apk_install_failure_is_distinct(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(), env={"FAKE_ADB_FAIL": "test_install"}, report=preflight_report()
        )
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "TEST_APK_INSTALL_FAILED")
        self.assertEqual(len(self.commands()), 2)

    def test_permission_failure_blocks_before_instrumentation(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(), env={"FAKE_ADB_FAIL": "manage_grant"}, report=preflight_report()
        )
        self.assertEqual(code, 1, stderr)
        summary = self.summary()
        self.assertEqual(summary["outcome"], "PREFLIGHT_BLOCKED_EXTERNAL")
        self.assertEqual(summary["reason_code"], "PERMISSION_GRANT_FAILED")
        calls = self.calls()
        self.assertEqual(len(calls), 4)
        self.assertTrue(any("pm" in call for call in calls))
        self.assertFalse(any("instrument" in call for call in calls))
        self.assertEqual(len(self.commands()), 4)
        self.assert_clean_run_dir()

    def test_custom_permission_failure_blocks(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(), env={"FAKE_ADB_FAIL": "custom_grant"}, report=preflight_report()
        )
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "PERMISSION_GRANT_FAILED")
        self.assertFalse(any("instrument" in call for call in self.calls()))

    def test_report_missing_blocks(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report="")
        self.assertEqual(code, 1, stderr)
        summary = self.summary()
        self.assertEqual(summary["reason_code"], "REPORT_MISSING")
        self.assertFalse((self.run_dir() / "report.json").exists())
        self.assertEqual(len(self.commands()), 6)

    def test_report_pull_failure_blocks(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(), env={"FAKE_ADB_FAIL": "pull"}, report=preflight_report()
        )
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_PULL_FAILED")
        self.assertFalse((self.run_dir() / "report.json").exists())

    def test_invalid_json_blocks(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report="{not json")
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_JSON_INVALID")
        self.assertFalse((self.run_dir() / "report.json").exists())

    def test_missing_required_keys_block(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report={})
        self.assertEqual(code, 1, stderr)
        self.assertEqual(
            self.summary()["reason_code"], "REPORT_REQUIRED_FIELDS_MISSING"
        )

    def test_alias_mismatch_blocks(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(), report=preflight_report(alias="other-device")
        )
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_ALIAS_MISMATCH")
        self.assertFalse((self.run_dir() / "report.json").exists())

    def test_token_in_report_is_rejected(self) -> None:
        report = preflight_report()
        report["host_note"] = TOKEN
        code, _stdout, stderr = self.invoke(self.args(), report=report)
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_CONTAINS_TOKEN")
        self.assert_clean_run_dir()

    def test_serial_in_report_is_rejected(self) -> None:
        report = preflight_report()
        report["host_note"] = SERIAL
        code, _stdout, stderr = self.invoke(self.args(), report=report)
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_CONTAINS_SERIAL")
        self.assert_clean_run_dir()

    def test_commands_are_redacted(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report=preflight_report())
        self.assertEqual(code, 0, stderr)
        text = (self.run_dir() / "commands.txt").read_text(encoding="utf-8")
        self.assertNotIn(SERIAL, text)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(str(self.root), text)
        self.assertNotIn("Success", text)
        self.assertNotIn("INSTRUMENTATION_CODE", text)
        for placeholder in (
            "<serial>",
            "<apk>",
            "<test-apk>",
            "<token>",
            "<report-path>",
            "<alias>",
        ):
            self.assertIn(placeholder, text)
        self.assertEqual(len(text.splitlines()), 6)
        self.assert_clean_run_dir()

    def test_sha256sums_cover_required_files(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report=preflight_report())
        self.assertEqual(code, 0, stderr)
        run_dir = self.run_dir()
        lines = (run_dir / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
        parsed = {}
        for line in lines:
            match = re.fullmatch(r"([0-9a-f]{64})  (\S+)", line)
            self.assertIsNotNone(match, line)
            parsed[match.group(2)] = match.group(1)
        self.assertEqual(
            set(parsed), {"report.json", "run-summary.json", "commands.txt"}
        )
        self.assertNotIn("SHA256SUMS", parsed)
        self.assertNotIn("README.txt", parsed)
        for relative, digest in parsed.items():
            self.assertEqual(run_dv01.sha256_file(run_dir / relative), digest)
        self.assertEqual(stat.S_IMODE((run_dir / "report.json").stat().st_mode), 0o600)

    def test_artifact_path_has_no_device_dimension(self) -> None:
        code, _stdout, stderr = self.invoke(self.args(), report=preflight_report())
        self.assertEqual(code, 0, stderr)
        relative = self.run_dir().relative_to(self.artifact_root)
        self.assertEqual(relative.parts[0], "protected-fastlane")
        self.assertRegex(relative.parts[1], r"^[0-9]{8}$")
        self.assertEqual(relative.parts[2], "preflight")
        self.assertEqual(relative.parts[3], TOKEN)
        self.assertEqual(len(relative.parts), 4)

    def test_dry_run_makes_no_device_or_artifact_changes(self) -> None:
        code, stdout, stderr = self.invoke(
            self.args(extra=("--dry-run",)), report=preflight_report()
        )
        self.assertEqual(code, 0, stderr)
        self.assertEqual(len(stdout.splitlines()), 6)
        self.assertIn("<token>", stdout)
        self.assertIn("<serial>", stdout)
        self.assertNotIn(SERIAL, stdout)
        self.assertFalse(self.log.exists())
        self.assertFalse(self.artifact_root.exists())

    def test_no_serial_omits_device_selection(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(serial=None, mode="qualification"),
            report=qualification_report(),
        )
        self.assertEqual(code, 0, stderr)
        first = self.calls()[0]
        self.assertEqual(first[:1], ["install"])
        commands = self.commands()
        self.assertTrue(commands[0].startswith("adb install -r -g <apk>"))
        self.assertFalse(self.summary()["serial_recorded"])

    def test_serial_from_environment_is_not_persisted(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(serial=None, mode="qualification"),
            env={"ANDROID_SERIAL": "ENV-SERIAL-1"},
            report=qualification_report(),
        )
        self.assertEqual(code, 0, stderr)
        self.assertEqual(self.calls()[0][:3], ["-s", "ENV-SERIAL-1", "install"])
        self.assertNotIn("ENV-SERIAL-1", (self.run_dir() / "commands.txt").read_text())
        self.assertNotIn("ENV-SERIAL-1", (self.run_dir() / "run-summary.json").read_text())

    def test_alias_matching_a_command_word_keeps_command_words(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(alias="install", token=OTHER_TOKEN),
            report=preflight_report(alias="install"),
        )
        self.assertEqual(code, 0, stderr)
        commands = self.commands(OTHER_TOKEN)
        self.assertIn(" install -r -g <apk>", commands[0])
        self.assertIn("qaDeviceAlias <alias>", commands[4])
        self.assertNotIn("<alias> -r", commands[0])

    def test_temp_artifact_root_is_rejected(self) -> None:
        if not self._original_temp_roots:
            self.skipTest("no system temp roots configured")
        run_dv01.TEMP_ROOTS = self._original_temp_roots
        args = self.args()
        index = args.index("--artifact-root") + 1
        args[index] = str(
            self._original_temp_roots[0] / "fastlane-device-temp-root"
        )
        code, _stdout, stderr = self.invoke(args, report=preflight_report())
        self.assertEqual(code, 2)
        self.assertIn("ERROR=ARTIFACT_ROOT_REJECTED", stderr)
        self.assertEqual(self.run_dirs(), [])

    def test_preflight_rejects_non_dv01_package(self) -> None:
        args = self.args() + [
            "--qa-package",
            "io.github.nyzeep.orchestella.qa.other",
        ]
        code, _stdout, stderr = self.invoke(args, report=preflight_report())
        self.assertEqual(code, 2)
        self.assertIn("ERROR=QA_PACKAGE_REJECTED", stderr)
        self.assertEqual(self.run_dirs(), [])

    def test_full_report_missing_contract_key_blocks(self) -> None:
        report = full_report()
        del report["auth_token"]
        code, _stdout, stderr = self.invoke(self.args(mode="full"), report=report)
        self.assertEqual(code, 1, stderr)
        self.assertEqual(
            self.summary()["reason_code"], "REPORT_REQUIRED_FIELDS_MISSING"
        )
        self.assertFalse((self.run_dir() / "report.json").exists())

    def test_preflight_rejects_extra_terminal_fields(self) -> None:
        report = preflight_report()
        report["terminal"]["message"] = "raw"
        code, _stdout, stderr = self.invoke(self.args(), report=report)
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_TERMINAL_INVALID")

    def test_external_validator_runs_when_present(self) -> None:
        validator = self.root / "validate.py"
        validator.write_text("import sys\nsys.exit(0)\n", encoding="utf-8")
        with mock.patch("run_dv01._external_validator", return_value=validator):
            code, _stdout, stderr = self.invoke(
                self.args(), report=preflight_report()
            )
        self.assertEqual(code, 0, stderr)
        summary = self.summary()
        self.assertTrue(summary["schema_validation"]["full_schema_validation"])
        self.assertEqual(summary["schema_validation"]["status"], "PASS")

    def test_external_validator_failure_blocks(self) -> None:
        validator = self.root / "validate.py"
        validator.write_text("import sys\nsys.exit(1)\n", encoding="utf-8")
        with mock.patch("run_dv01._external_validator", return_value=validator):
            code, _stdout, stderr = self.invoke(
                self.args(), report=preflight_report()
            )
        self.assertEqual(code, 1, stderr)
        self.assertEqual(
            self.summary()["reason_code"], "SCHEMA_VALIDATION_FAILED"
        )
        self.assertFalse((self.run_dir() / "report.json").exists())

    def test_sha256sums_include_artifacts(self) -> None:
        run_dir = self.root / "run"
        for name, data in (
            ("report.json", b"report"),
            ("run-summary.json", b"summary"),
            ("commands.txt", b"commands"),
            ("artifacts/example.apk", b"apk"),
        ):
            path = run_dir / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        lines = run_dv01._sha256sums(run_dir).decode("utf-8").splitlines()
        parsed = {
            line.split("  ", 1)[1]: line.split("  ", 1)[0] for line in lines
        }
        self.assertEqual(
            set(parsed),
            {
                "report.json",
                "run-summary.json",
                "commands.txt",
                "artifacts/example.apk",
            },
        )
        self.assertEqual(
            parsed["artifacts/example.apk"],
            run_dv01.sha256_file(run_dir / "artifacts/example.apk"),
        )

    def test_artifact_root_inside_repo_is_rejected(self) -> None:
        args = self.args()
        index = args.index("--artifact-root") + 1
        args[index] = str(run_dv01.REPO_ROOT / "artifacts")
        code, _stdout, stderr = self.invoke(args, report=preflight_report())
        self.assertEqual(code, 2)
        self.assertIn("ERROR=ARTIFACT_ROOT_REJECTED", stderr)

    def test_relative_artifact_root_is_rejected(self) -> None:
        args = self.args()
        index = args.index("--artifact-root") + 1
        args[index] = "relative-artifacts"
        code, _stdout, stderr = self.invoke(args, report=preflight_report())
        self.assertEqual(code, 2)
        self.assertIn("ERROR=ARTIFACT_ROOT_REJECTED", stderr)

    def test_preflight_blocked_report_is_archived_with_exit_three(self) -> None:
        report = preflight_report(
            outcome="PREFLIGHT_TERMINAL_BLOCKED",
            terminal={
                "status": "blocked",
                "reason_code": "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM",
                "fln1_code": 7,
                "exec_stage": "execve",
                "errno": 13,
            },
        )
        code, _stdout, stderr = self.invoke(self.args(), report=report)
        self.assertEqual(code, 3, stderr)
        summary = self.summary()
        self.assertEqual(summary["outcome"], "PREFLIGHT_TERMINAL_BLOCKED")
        self.assertEqual(
            summary["reason_code"], "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM"
        )
        self.assertTrue((self.run_dir() / "report.json").is_file())
        self.assertEqual(len(self.commands()), 6)

    def test_preflight_external_blocked_report_is_archived_with_exit_three(
        self,
    ) -> None:
        report = preflight_report(
            outcome="PREFLIGHT_BLOCKED_EXTERNAL",
            permission="DENIED",
            terminal={
                "status": "blocked",
                "reason_code": "PERMISSION_DENIED",
                "fln1_code": -1,
                "exec_stage": "not-tested",
                "errno": -1,
            },
            stop="NOT_TESTED",
        )
        code, _stdout, stderr = self.invoke(self.args(), report=report)
        self.assertEqual(code, 3, stderr)
        summary = self.summary()
        self.assertEqual(summary["outcome"], "PREFLIGHT_BLOCKED_EXTERNAL")
        self.assertEqual(summary["reason_code"], "PERMISSION_DENIED")
        self.assertTrue((self.run_dir() / "report.json").is_file())

    def test_instrumentation_failure_blocks_before_pull(self) -> None:
        code, _stdout, stderr = self.invoke(
            self.args(), env={"FAKE_ADB_FAIL": "instrument"}, report=preflight_report()
        )
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "INSTRUMENTATION_FAILED")
        self.assertEqual(len(self.commands()), 5)
        self.assertFalse((self.run_dir() / "report.json").exists())

    def test_qa_package_override_flows_into_commands(self) -> None:
        package = "io.github.nyzeep.orchestella.qa.other"
        args = self.args(mode="qualification") + ["--qa-package", package]
        code, _stdout, stderr = self.invoke(
            args, report=qualification_report(qa_package=package)
        )
        self.assertEqual(code, 0, stderr)
        calls = self.calls()
        self.assertIn(package, calls[2])
        self.assertEqual(calls[4][-1], f"{package}.test/{RUNNER}")
        self.assertEqual(calls[5][4], package)
        self.assertIn(package, (self.run_dir() / "commands.txt").read_text())

    def test_preflight_ok_requires_confirmed_stop(self) -> None:
        report = preflight_report(stop="UNCONFIRMED")
        code, _stdout, stderr = self.invoke(self.args(), report=report)
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_OUTCOME_INVALID")
        self.assertFalse((self.run_dir() / "report.json").exists())

    def test_full_accepts_contract_blocked_outcome(self) -> None:
        report = full_report(outcome="DV01_CORE_PASS_TERMINAL_BLOCKED")
        code, _stdout, stderr = self.invoke(self.args(mode="full"), report=report)
        self.assertEqual(code, 0, stderr)
        self.assertEqual(
            self.summary()["outcome"], "DV01_CORE_PASS_TERMINAL_BLOCKED"
        )
        self.assertTrue((self.run_dir() / "report.json").is_file())

    def test_full_report_invalid_outcome_blocks(self) -> None:
        report = full_report(outcome="PASS\nRUN_DIR=injected")
        code, _stdout, stderr = self.invoke(self.args(mode="full"), report=report)
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_OUTCOME_INVALID")

    def test_preflight_reason_code_cannot_carry_a_path(self) -> None:
        report = preflight_report()
        report["terminal"]["reason_code"] = "/data/local/tmp/raw"
        code, _stdout, stderr = self.invoke(self.args(), report=report)
        self.assertEqual(code, 1, stderr)
        self.assertEqual(self.summary()["reason_code"], "REPORT_TERMINAL_INVALID")

    def test_missing_toolbox_sha_is_rejected_for_preflight(self) -> None:
        args = self.args()
        index = args.index("--toolbox-sha")
        del args[index : index + 2]
        code, _stdout, stderr = self.invoke(args, report=preflight_report())
        self.assertEqual(code, 2)
        self.assertIn("ERROR=TOOLBOX_SHA_REJECTED", stderr)


if __name__ == "__main__":
    unittest.main()
