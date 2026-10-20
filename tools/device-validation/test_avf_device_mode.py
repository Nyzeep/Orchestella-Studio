# SPDX-License-Identifier: GPL-3.0-or-later
"""Zero-device tests for the real adb device path.

Every test runs a generated fake adb executable in an external scratch root.
No real device, adb server, APK or VM is touched. The fake probe JSON is the
only source of device facts; the host must never invent a launch result.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import unittest
import uuid

sys.dont_write_bytecode = True
MODULE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(MODULE_DIR))

SERIAL = "FIXTURESERIAL123"
ALIAS = "qa-device-01"
QA_PACKAGE = "io.github.nyzeep.orchestella.qa.unittest"
QA_TEST_PACKAGE = QA_PACKAGE + ".test"
PROBE_STAGES = (
    "create", "run_returned", "vsock_handshake", "stop_cycles",
    "network", "share_display", "app_death", "qemu_comparison",
)

FAKE_ADB = r'''#!/usr/bin/env python3
import json
import os
import sys
import time

args = sys.argv[1:]
scenario = os.environ.get("FAKE_ADB_SCENARIO", "ok")
serial = os.environ["FAKE_SERIAL"]
qa_package = os.environ["FAKE_QA_PACKAGE"]
log_path = os.environ.get("FAKE_ARGV_LOG")
marker = os.environ.get("FAKE_GRANT_MARKER")
state_path = os.environ.get("FAKE_MODE_STATE")

if log_path:
    with open(log_path, "a", encoding="utf-8") as stream:
        stream.write(json.dumps(args) + "\n")

def finish(code=0):
    sys.exit(code)

def recorded_mode():
    if not state_path or not os.path.exists(state_path):
        return "qualification"
    return open(state_path, encoding="utf-8").read().strip()

def probe_file():
    mode = recorded_mode()
    launch = os.environ.get("FAKE_PROBE_JSON_LAUNCH")
    if mode != "qualification" and launch and os.path.exists(launch):
        return launch
    if marker and os.path.exists(marker):
        return os.environ.get("FAKE_PROBE_JSON_AFTER")
    return os.environ.get("FAKE_PROBE_JSON_BEFORE")

if args == ["devices", "-l"]:
    if scenario == "timeout":
        time.sleep(5)
    print("List of devices attached")
    if scenario == "two-devices":
        print(serial + "\tdevice product:fixture model:fixture device:fixture")
        print("OTHERSERIAL\tdevice product:fixture model:fixture device:fixture")
    elif scenario == "no-device":
        pass
    elif scenario == "unauthorized":
        print(serial + "\tunauthorized")
    else:
        print(serial + "\tdevice product:fixture model:fixture device:fixture")
    finish()

rest = args
if args[:1] == ["-s"]:
    if args[1] != serial:
        finish(2)
    rest = args[2:]

if rest[:1] == ["install"]:
    if scenario == "install-fail":
        finish(1)
    print("Success")
    finish()

if rest[:1] == ["exec-out"]:
    if scenario == "probe-missing":
        finish(1)
    path = probe_file()
    if not path or not os.path.exists(path):
        finish(1)
    sys.stdout.write(open(path, encoding="utf-8").read())
    finish()

if rest[:1] != ["shell"]:
    finish(3)
command = rest[1:]

if command[:1] == ["getprop"]:
    values = {
        "ro.build.id": "FIXTURE_BUILD",
        "ro.build.version.release": "16",
        "ro.build.version.sdk": "36",
        "ro.build.fingerprint": "fixture/fingerprint",
        "ro.product.cpu.abilist": "arm64-v8a,armeabi-v7a",
        "ro.boot.hypervisor.vm.supported": "1",
        "ro.boot.hypervisor.protected_vm.supported": "1",
    }
    print(values.get(command[1], ""))
    finish()

if command[:1] == ["getconf"] and command[1:2] == ["PAGE_SIZE"]:
    print("16384")
    finish()

if command[:3] == ["cmd", "package", "has-feature"]:
    print("true")
    finish()

if command[:4] == ["cmd", "package", "list", "packages"]:
    if command[-1] == "com.android.virt":
        print("package:com.android.virt versionCode:360000000")
    finish()

if command[:2] == ["pm", "path"]:
    if scenario == "package-missing":
        finish()
    print("package:/data/app/fixture/base.apk")
    finish()

if command[:2] == ["pm", "grant"]:
    if scenario == "grant-fail":
        finish(1)
    if marker:
        open(marker, "w", encoding="utf-8").write("granted")
    finish()

if command[:2] == ["am", "force-stop"]:
    finish()

if command[:2] == ["am", "instrument"]:
    if scenario == "instrument-fail":
        print("INSTRUMENTATION_FAILED: fixture")
        finish(1)
    mode = "qualification"
    for index, value in enumerate(command):
        if value == "qaMode" and index + 1 < len(command):
            mode = command[index + 1]
    if state_path:
        open(state_path, "w", encoding="utf-8").write(mode)
    print("INSTRUMENTATION_RESULT: qa_probe_path=fixture")
    print("INSTRUMENTATION_CODE: -1")
    finish()

finish(4)
'''


def require_root():
    configured = os.environ.get("AVF_VALIDATION_TEST_ROOT")
    if not configured or not Path(configured).is_absolute():
        raise AssertionError(
            "AVF_VALIDATION_TEST_ROOT must be an explicit absolute external directory")
    return Path(configured) / uuid.uuid4().hex


class DeviceCliTests(unittest.TestCase):
    def setUp(self):
        self.root = require_root()
        self.root.mkdir(parents=True, exist_ok=True)
        self.fake_adb = self.root / "adb"
        self.fake_adb.write_text(FAKE_ADB, encoding="utf-8")
        self.fake_adb.chmod(0o755)
        self.out = self.root / "out"
        self.argv_log = self.root / "argv.jsonl"
        self.marker = self.root / "granted"
        self.mode_state = self.root / "mode.txt"
        self.probe_before = self.root / "probe-before.json"
        self.probe_after = self.root / "probe-after.json"
        self.probe_launch = self.root / "probe-launch.json"
        self.probe_before.write_text(json.dumps(self.probe()), encoding="utf-8")
        self.probe_after.write_text(json.dumps(self.probe()), encoding="utf-8")

    def probe(self, *, mode="qualification", access="AVAILABLE", non_protected=True,
              protected=True, manage="GRANTED", custom="GRANTED",
              protected_mode=None, stop_confirmed=True, overrides=None, omit=None):
        bits = (1 if protected else 0) | (2 if non_protected else 0)
        if protected_mode is None:
            protected_mode = "REJECTED" if protected and not non_protected else "ACCEPTED"
        stages = {}
        for stage in PROBE_STAGES:
            if mode == "qualification":
                stages[stage] = {"status": "blocked",
                                 "reason_code": "QA_LAUNCH_NOT_REQUESTED"}
            else:
                stages[stage] = {"status": "pass", "reason_code": stage.upper() + "_OK"}
        stages["stop_cycles"]["reason_code"] = "STOP_CONFIRMED"
        stages["stop_cycles"]["stop_confirmed"] = stop_confirmed
        for stage, value in (overrides or {}).items():
            stages[stage] = value
        if omit:
            stages.pop(omit, None)
        return {
            "schema_version": 1,
            "kind": "avf_qa_probe",
            "qa_package": QA_PACKAGE,
            "mode": mode,
            "device_facts": {
                "sdk_int": 36,
                "abis": ["arm64-v8a", "armeabi-v7a"],
                "feature": True,
                "build_id": "FIXTURE_BUILD",
                "release": "16",
                "fingerprint_sha256": hashlib.sha256(b"fixture").hexdigest(),
            },
            "qualification": {
                "access_state": access,
                "exemption_state": "INSTALLED",
                "manager_present": True,
                "non_protected": non_protected,
                "protected": protected,
                "capabilities_raw": bits,
                "manage_permission": manage,
                "custom_permission": custom,
                "protected_mode": protected_mode,
                "protected_mode_exception": (
                    "UnsupportedOperationException"
                    if protected_mode == "REJECTED" else None),
            },
            "stage_results": stages,
        }

    def run_cli(self, *extra, scenario="ok", before=None, after=None, launch=None,
                timeout="2"):
        env = os.environ.copy()
        env.update({
            "FAKE_ADB_SCENARIO": scenario,
            "FAKE_SERIAL": SERIAL,
            "FAKE_QA_PACKAGE": QA_PACKAGE,
            "FAKE_QA_TEST_PACKAGE": QA_TEST_PACKAGE,
            "FAKE_ARGV_LOG": str(self.argv_log),
            "FAKE_GRANT_MARKER": str(self.marker),
            "FAKE_MODE_STATE": str(self.mode_state),
            "FAKE_PROBE_JSON_BEFORE": str(self.probe_before),
            "FAKE_PROBE_JSON_AFTER": str(self.probe_after),
            "FAKE_PROBE_JSON_LAUNCH": str(self.probe_launch),
        })
        if before is not None:
            self.probe_before.write_text(json.dumps(before), encoding="utf-8")
        if after is not None:
            self.probe_after.write_text(json.dumps(after), encoding="utf-8")
        if launch is not None:
            self.probe_launch.write_text(json.dumps(launch), encoding="utf-8")
        arguments = [
            sys.executable, "-B", str(MODULE_DIR / "run_avf_device_validation.py"),
            "--device", SERIAL, "--device-alias", ALIAS,
            "--qa-package", QA_PACKAGE, "--qa-test-package", QA_TEST_PACKAGE,
            "--adb", str(self.fake_adb), "--adb-timeout", timeout,
            "--out", str(self.out),
        ]
        arguments.extend(extra)
        return subprocess.run(arguments, stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=40, shell=False, env=env)

    def report_from(self, process):
        self.assertIn(process.returncode, (0, 2),
                      process.stderr.decode("utf-8", "replace"))
        summary = json.loads(process.stdout)
        self.assertIn("report_json", summary, summary)
        return json.loads(Path(summary["report_json"]).read_bytes())

    def stage(self, report, stage_id):
        return next(stage for stage in report["stages"] if stage["id"] == stage_id)

    def commands(self):
        if not self.argv_log.exists():
            return []
        return [json.loads(line)
                for line in self.argv_log.read_text(encoding="utf-8").splitlines()]

    def instrument_commands(self):
        return [command for command in self.commands()
                if command[2:5] == ["shell", "am", "instrument"]]

    def test_qualification_on_protected_only_counts_platform_sample_without_vm(self):
        process = self.run_cli(before=self.probe(non_protected=False, protected=True,
                                                 protected_mode="REJECTED"))
        self.assertEqual(process.returncode, 2, process.stdout + process.stderr)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "capabilities")["status"], "pass")
        self.assertEqual(self.stage(report, "capabilities")["reason_code"],
                         "PLATFORM_QUALIFIED_PROTECTED_ONLY")
        self.assertEqual(self.stage(report, "permissions")["status"], "pass")
        self.assertEqual(self.stage(report, "protected_mode")["status"], "pass")
        self.assertEqual(self.stage(report, "protected_mode")["reason_code"],
                         "NON_PROTECTED_CONFIG_REFUSED")
        self.assertEqual(self.stage(report, "create")["status"], "blocked")
        self.assertEqual(self.stage(report, "create")["reason_code"],
                         "QA_LAUNCH_NOT_REQUESTED")
        self.assertTrue(report["device_facts"]["protected_only"])
        self.assertEqual(report["platform_qualification_samples"], 1)
        self.assertEqual(report["protected_microdroid_samples"], 0)
        self.assertEqual(report["custom_linux_samples"], 0)
        self.assertEqual(report["real_avf_samples"], 0)
        self.assertEqual(len(self.instrument_commands()), 1)

    def test_protected_microdroid_lifecycle_counts_only_that_sample(self):
        launch = self.probe(mode="protected-microdroid", non_protected=False,
                            protected=True, protected_mode="REJECTED")
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so",
                               before=self.probe(non_protected=False, protected=True,
                                                 protected_mode="REJECTED"),
                               launch=launch)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        report = self.report_from(process)
        for stage_id in ("create", "run_returned", "vsock_handshake", "stop_cycles"):
            self.assertEqual(self.stage(report, stage_id)["status"], "pass", stage_id)
        self.assertEqual(report["platform_qualification_samples"], 1)
        self.assertEqual(report["protected_microdroid_samples"], 1)
        self.assertEqual(report["custom_linux_samples"], 0)
        self.assertEqual(report["real_avf_samples"], 1)
        self.assertEqual(report["qa_mode"], "protected-microdroid")
        instruments = self.instrument_commands()
        self.assertEqual(len(instruments), 2)
        self.assertIn("protected-microdroid", instruments[1])
        self.assertIn("qaMicrodroidPayload", instruments[1])
        self.assertIn("qaStopDeadlineMs", instruments[1])

    def test_protected_microdroid_without_payload_never_creates_a_vm(self):
        launch = self.probe(
            mode="protected-microdroid", non_protected=False, protected=True,
            overrides={
                "create": {"status": "blocked",
                           "reason_code": "QA_MICRODROID_PAYLOAD_MISSING"},
                "run_returned": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED"},
                "vsock_handshake": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED"},
                "stop_cycles": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED",
                                "stop_confirmed": False},
            })
        process = self.run_cli("--qa-mode", "protected-microdroid",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "create")["status"], "blocked")
        self.assertEqual(self.stage(report, "create")["reason_code"],
                         "QA_MICRODROID_PAYLOAD_MISSING")
        self.assertEqual(report["protected_microdroid_samples"], 0)
        self.assertNotIn("qaMicrodroidPayload", self.instrument_commands()[1])

    def test_custom_linux_on_protected_only_is_blocked_without_launch(self):
        process = self.run_cli("--qa-mode", "custom-linux",
                               before=self.probe(non_protected=False, protected=True,
                                                 protected_mode="REJECTED"))
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "capabilities")["status"], "pass")
        self.assertEqual(self.stage(report, "create")["status"], "blocked")
        self.assertEqual(self.stage(report, "create")["reason_code"],
                         "NO_NON_PROTECTED_VM")
        self.assertEqual(report["custom_linux_samples"], 0)
        self.assertEqual(report["real_avf_samples"], 0)
        self.assertEqual(len(self.instrument_commands()), 1)

    def test_custom_linux_missing_artifacts_is_not_a_sample(self):
        launch = self.probe(
            mode="custom-linux", non_protected=True, protected=True,
            overrides={
                "create": {"status": "blocked",
                           "reason_code": "QA_CUSTOM_ARTIFACTS_MISSING"},
                "run_returned": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED"},
                "vsock_handshake": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED"},
                "stop_cycles": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED",
                                "stop_confirmed": False},
            })
        process = self.run_cli("--qa-mode", "custom-linux",
                               before=self.probe(non_protected=True, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "create")["status"], "blocked")
        self.assertEqual(self.stage(report, "create")["reason_code"],
                         "QA_CUSTOM_ARTIFACTS_MISSING")
        self.assertEqual(report["custom_linux_samples"], 0)
        self.assertEqual(report["real_avf_samples"], 0)

    def test_custom_linux_lifecycle_counts_only_custom(self):
        launch = self.probe(mode="custom-linux", non_protected=True, protected=True)
        process = self.run_cli("--qa-mode", "custom-linux",
                               before=self.probe(non_protected=True, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        report = self.report_from(process)
        self.assertEqual(report["protected_microdroid_samples"], 0)
        self.assertEqual(report["custom_linux_samples"], 1)
        self.assertEqual(report["real_avf_samples"], 1)

    def test_no_capability_bit_fails_platform_qualification(self):
        process = self.run_cli(before=self.probe(access="MODE_UNSUPPORTED",
                                                 non_protected=False, protected=False,
                                                 protected_mode="ACCEPTED"))
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "capabilities")["status"], "fail")
        self.assertEqual(self.stage(report, "capabilities")["reason_code"],
                         "NO_VM_CAPABILITY")
        self.assertEqual(report["platform_qualification_samples"], 0)
        self.assertEqual(report["real_avf_samples"], 0)

    def test_permission_denied_without_grant_never_calls_pm_grant(self):
        process = self.run_cli(before=self.probe(access="PERMISSION_DENIED",
                                                 manage="DENIED", custom="DENIED"))
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "capabilities")["status"], "blocked")
        self.assertEqual(self.stage(report, "permissions")["status"], "blocked")
        self.assertEqual(self.stage(report, "permissions")["reason_code"],
                         "GRANT_NOT_AUTHORIZED")
        self.assertEqual(report["real_avf_samples"], 0)
        self.assertFalse(any(command[:2] == ["-s", SERIAL]
                             and command[2:5] == ["shell", "pm", "grant"]
                             for command in self.commands()))

    def test_hidden_api_blocked_is_not_a_platform_sample(self):
        process = self.run_cli(before=self.probe(access="HIDDEN_API_BLOCKED"))
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "capabilities")["status"], "blocked")
        self.assertEqual(self.stage(report, "capabilities")["reason_code"],
                         "HIDDEN_API_BLOCKED")
        self.assertEqual(report["platform_qualification_samples"], 0)

    def test_set_protected_vm_false_rejection_is_recorded_as_expected(self):
        process = self.run_cli(before=self.probe(non_protected=False, protected=True,
                                                 protected_mode="REJECTED"))
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(report["device_facts"]["protected_mode"], "REJECTED")
        self.assertEqual(report["device_facts"]["protected_mode_exception"],
                         "UnsupportedOperationException")
        self.assertEqual(self.stage(report, "protected_mode")["status"], "pass")

    def test_explicit_grant_targets_only_the_qa_package_and_rechecks(self):
        after = self.probe()
        process = self.run_cli("--grant",
                               before=self.probe(access="PERMISSION_DENIED",
                                                 manage="DENIED", custom="DENIED"),
                               after=after)
        self.assertEqual(process.returncode, 2, process.stdout + process.stderr)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "permissions")["status"], "pass")
        self.assertEqual(report["platform_qualification_samples"], 1)
        self.assertEqual(report["real_avf_samples"], 0)
        grants = [command for command in self.commands()
                  if command[2:5] == ["shell", "pm", "grant"]]
        self.assertEqual(len(grants), 2)
        for command in grants:
            self.assertEqual(command[5], QA_PACKAGE)
            self.assertNotEqual(command[5], "io.github.nyzeep.orchestella")
        self.assertTrue(any(command[2:5] == ["shell", "am", "force-stop"]
                            for command in self.commands()))
        self.assertIn(b"pm grant", process.stderr)
        self.assertNotIn(SERIAL.encode(), process.stderr)

    def test_grant_failure_is_reported_and_stops(self):
        process = self.run_cli("--grant", scenario="grant-fail",
                               before=self.probe(access="PERMISSION_DENIED",
                                                 manage="DENIED", custom="DENIED"))
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "permissions")["status"], "blocked")
        self.assertEqual(self.stage(report, "permissions")["reason_code"],
                         "PERMISSIONS_GRANT_FAILED")
        self.assertEqual(report["real_avf_samples"], 0)

    def test_microdroid_create_failure_stops_run_and_stop(self):
        launch = self.probe(
            mode="protected-microdroid", non_protected=False, protected=True,
            overrides={
                "create": {"status": "fail", "reason_code": "QA_VM_CREATE_FAILED"},
                "run_returned": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED"},
                "vsock_handshake": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED"},
                "stop_cycles": {"status": "blocked", "reason_code": "PRIOR_STAGE_BLOCKED",
                                "stop_confirmed": False},
            })
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "create")["status"], "fail")
        self.assertEqual(self.stage(report, "create")["reason_code"], "CREATE_FAILED")
        self.assertEqual(self.stage(report, "run_returned")["status"], "blocked")
        self.assertEqual(report["protected_microdroid_samples"], 0)

    def test_run_returned_without_vsock_is_not_guest_ready_but_stop_counts(self):
        launch = self.probe(
            mode="protected-microdroid", non_protected=False, protected=True,
            overrides={
                "vsock_handshake": {"status": "fail", "reason_code": "VSOCK_TIMEOUT"},
            })
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "run_returned")["status"], "pass")
        self.assertEqual(self.stage(report, "vsock_handshake")["status"], "fail")
        self.assertEqual(self.stage(report, "vsock_handshake")["reason_code"],
                         "VSOCK_TIMEOUT")
        self.assertEqual(self.stage(report, "stop_cycles")["status"], "pass")
        self.assertEqual(report["protected_microdroid_samples"], 1)

    def test_stop_unconfirmed_is_not_a_pass(self):
        launch = self.probe(mode="protected-microdroid", non_protected=False,
                            protected=True, stop_confirmed=False)
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "stop_cycles")["status"], "fail")
        self.assertEqual(self.stage(report, "stop_cycles")["reason_code"],
                         "STOP_UNCONFIRMED")
        self.assertEqual(report["protected_microdroid_samples"], 0)
        self.assertEqual(report["real_avf_samples"], 0)

    def test_missing_qa_package_is_blocked_before_instrumentation(self):
        process = self.run_cli(scenario="package-missing")
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(report["summary"]["blocked"], 8)
        self.assertEqual(report["real_avf_samples"], 0)
        self.assertFalse(self.instrument_commands())

    def test_no_device_is_recorded_without_grant_or_instrument(self):
        process = self.run_cli(scenario="no-device")
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "platform_declaration")["reason_code"],
                         "NO_DEVICE")
        self.assertEqual(report["summary"]["blocked"], 8)
        self.assertEqual(report["real_avf_samples"], 0)
        self.assertIsNone(report["probe"])
        self.assertIsNone(report["device_facts"])
        self.assertFalse(any(command[2:5] == ["shell", "pm", "grant"]
                             for command in self.commands()))

    def test_multiple_devices_is_blocked(self):
        process = self.run_cli(scenario="two-devices")
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "platform_declaration")["reason_code"],
                         "DEVICE_COUNT_NOT_ONE")
        self.assertEqual(report["summary"]["pass"], 0)
        self.assertEqual(report["real_avf_samples"], 0)

    def test_adb_timeout_is_bounded_and_not_retried(self):
        started = time.monotonic()
        process = self.run_cli(scenario="timeout", timeout="1")
        elapsed = time.monotonic() - started
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "platform_declaration")["reason_code"],
                         "ADB_TIMEOUT")
        self.assertEqual(len(report["commands"]), 1)
        self.assertLess(elapsed, 15)

    def test_report_and_stdout_do_not_contain_the_serial(self):
        launch = self.probe(mode="protected-microdroid", non_protected=False,
                            protected=True)
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 0)
        report_bytes = Path(json.loads(process.stdout)["report_json"]).read_bytes()
        combined = process.stdout + process.stderr + report_bytes
        self.assertNotIn(SERIAL.encode(), combined)
        self.assertIn(b"<device>", report_bytes)

    def test_stage_selection_marks_unrequested_stages_not_tested(self):
        process = self.run_cli("--stage", "create,run_returned")
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "create")["status"], "blocked")
        self.assertEqual(self.stage(report, "create")["reason_code"],
                         "QA_LAUNCH_NOT_REQUESTED")
        self.assertEqual(self.stage(report, "run_returned")["status"], "blocked")
        self.assertEqual(self.stage(report, "vsock_handshake")["status"], "not-tested")
        self.assertEqual(self.stage(report, "stop_cycles")["status"], "not-tested")
        self.assertEqual(report["real_avf_samples"], 0)

    def test_all_stages_keep_missing_probe_results_blocked(self):
        launch = self.probe(mode="protected-microdroid", non_protected=False,
                            protected=True, omit="network")
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so", "--stage", "all",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        self.assertEqual(self.stage(report, "network")["status"], "blocked")
        self.assertEqual(self.stage(report, "network")["reason_code"],
                         "QA_STAGE_RESULT_MISSING")
        self.assertEqual(report["protected_microdroid_samples"], 1)

    def test_cycles_are_passed_to_the_qa_probe(self):
        launch = self.probe(mode="protected-microdroid", non_protected=False,
                            protected=True)
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so", "--cycles", "20",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 0)
        instruments = self.instrument_commands()
        self.assertEqual(len(instruments), 2)
        self.assertIn("20", instruments[1])
        self.assertIn("qaCycles", instruments[1])

    def test_production_package_is_rejected_before_adb(self):
        arguments = [
            sys.executable, "-B", str(MODULE_DIR / "run_avf_device_validation.py"),
            "--device", SERIAL, "--device-alias", ALIAS,
            "--qa-package", "io.github.nyzeep.orchestella",
            "--adb", str(self.fake_adb), "--out", str(self.out),
        ]
        process = subprocess.run(arguments, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 timeout=20, shell=False)
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stdout), {"error": "QA_PACKAGE_REQUIRED"})
        self.assertFalse(self.argv_log.exists())

    def test_apk_path_is_redacted_in_command_log(self):
        apk = self.root / "qa.apk"
        apk.write_bytes(b"fixture apk bytes")
        test_apk = self.root / "qa-test.apk"
        test_apk.write_bytes(b"fixture test bytes")
        process = self.run_cli("--apk", str(apk), "--test-apk", str(test_apk))
        self.assertEqual(process.returncode, 2)
        report = self.report_from(process)
        installs = [command for command in report["commands"]
                    if command["id"] == "install"]
        self.assertEqual(len(installs), 2)
        for command in installs:
            self.assertIn("<apk>", command["argv"])
            self.assertNotIn(str(apk), command["argv"])
            self.assertNotIn(str(test_apk), command["argv"])

    def test_device_report_can_be_validated(self):
        process = self.run_cli()
        self.assertEqual(process.returncode, 2)
        report_path = json.loads(process.stdout)["report_json"]
        validate = subprocess.run(
            [sys.executable, "-B", str(MODULE_DIR / "run_avf_device_validation.py"),
             "--validate", report_path, "--out", str(self.out / "validate")],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=20, shell=False)
        self.assertEqual(validate.returncode, 0, validate.stdout + validate.stderr)
        self.assertEqual(json.loads(validate.stdout),
                         {"status": "VALID", "schema_version": 1})

    def test_validate_rejects_fabricated_device_pass(self):
        launch = self.probe(mode="protected-microdroid", non_protected=False,
                            protected=True)
        process = self.run_cli("--qa-mode", "protected-microdroid", "--microdroid-payload", "libqa_payload.so",
                               before=self.probe(non_protected=False, protected=True),
                               launch=launch)
        self.assertEqual(process.returncode, 0)
        report_path = Path(json.loads(process.stdout)["report_json"])
        report = json.loads(report_path.read_bytes())
        create = next(stage for stage in report["stages"] if stage["id"] == "create")
        create["evidence"] = {"state": "not-collected", "path": None, "sha256": None}
        tampered = self.root / "tampered.json"
        tampered.write_bytes((json.dumps(report) + "\n").encode("utf-8"))
        validate = subprocess.run(
            [sys.executable, "-B", str(MODULE_DIR / "run_avf_device_validation.py"),
             "--validate", str(tampered), "--out", str(self.out / "validate2")],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=20, shell=False)
        self.assertEqual(validate.returncode, 2)
        self.assertEqual(json.loads(validate.stdout),
                         {"error": "REPORT_SCHEMA_REJECTED"})

    def test_dry_run_keeps_twelve_not_tested_and_never_counts_samples(self):
        process = self.run_cli("--dry-run")
        # --device and --dry-run are mutually exclusive; use a direct invocation.
        self.assertEqual(process.returncode, 2)
        direct = subprocess.run(
            [sys.executable, "-B", str(MODULE_DIR / "run_avf_device_validation.py"),
             "--dry-run", "--out", str(self.out / "dry")],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=20, shell=False)
        self.assertEqual(direct.returncode, 0, direct.stdout + direct.stderr)
        report = json.loads(Path(json.loads(direct.stdout)["report_json"]).read_bytes())
        self.assertEqual(report["summary"],
                         {"pass": 0, "fail": 0, "blocked": 0, "not-tested": 12})
        self.assertEqual(report["real_avf_samples"], 0)
        self.assertEqual(report["platform_qualification_samples"], 0)
        self.assertEqual(report["protected_microdroid_samples"], 0)
        self.assertEqual(report["custom_linux_samples"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
