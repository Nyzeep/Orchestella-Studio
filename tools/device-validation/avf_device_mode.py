# SPDX-License-Identifier: GPL-3.0-or-later
"""Host-side AVF device execution.

This module is the real '--device' path behind
run_avf_device_validation.py. It invokes adb through one small runner with
per-command timeouts and a whole-run deadline, and it records only fixed
command metadata. Raw stdout, serials, tokens, user paths and screen content
never reach the report.

The device-side half is a QA-only instrumentation probe
(AvfQaProbeInstrumentation). The probe writes one JSON document to the QA
app's private files directory; the host pulls it with run-as. It supports
three explicit QA modes: qualification (read-only), protected-microdroid
(system payload, protected VM), and custom-linux (non-protected QA image).
The host never invents a result when the probe is absent, and qualification
samples are counted separately from real VM lifecycle samples.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True

import avf_preflight
import avf_qa_staging
from avf_evidence_io import Rejected, parse_json

ADB_TIMEOUT_SECONDS = 20.0
INSTALL_TIMEOUT_SECONDS = 180.0
INSTRUMENT_TIMEOUT_SECONDS = 180.0
MAX_ADB_OUTPUT = 256 * 1024
QA_RUNNER = "io.github.nyzeep.orchestella.AvfQaProbeInstrumentation"
PERMISSIONS = (
    "android.permission.MANAGE_VIRTUAL_MACHINE",
    "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
)
QA_MODES = ("qualification", "protected-microdroid", "custom-linux")
SAMPLE_COUNTERS = (
    "platform_qualification_samples",
    "protected_microdroid_samples",
    "custom_linux_samples",
    "real_avf_samples",
)
PAYLOAD_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\.so")
DEFAULT_STOP_DEADLINE_MS = 15000
DEFAULT_CUSTOM_MEMORY_MB = 1024
DEFAULT_VSOCK_CONTROL_PORT = 9100
DEFAULT_VSOCK_TERMINAL_PORT = 9101
DEFAULT_VSOCK_TIMEOUT_MS = 120000
MAX_VSOCK_TIMEOUT_MS = 300000
DEFAULT_CUSTOM_KERNEL = "files/avf-qa/custom/kernel"
DEFAULT_CUSTOM_INITRD = "files/avf-qa/custom/initrd"
DEFAULT_CUSTOM_ROOT = "files/avf-qa/custom/root.img"
STAGE_IDS = (
    "platform_declaration",
    "capabilities",
    "permissions",
    "protected_mode",
    "create",
    "run_returned",
    "vsock_handshake",
    "stop_cycles",
    "network",
    "share_display",
    "app_death",
    "qemu_comparison",
)
MINIMAL_STAGES = STAGE_IDS[:8]
PROBE_STAGE_IDS = (
    "create",
    "run_returned",
    "vsock_handshake",
    "stop_cycles",
    "network",
    "share_display",
    "app_death",
    "qemu_comparison",
)
PERMISSION_STATES = {"GRANTED", "DENIED", "UNKNOWN"}
ACCESS_STATES = {
    "AVAILABLE",
    "HIDDEN_API_BLOCKED",
    "PERMISSION_DENIED",
    "REFLECTION_UNAVAILABLE",
    "MODE_UNSUPPORTED",
    "MODE_UNKNOWN",
}
EXEMPTION_STATES = {"NOT_ATTEMPTED", "INSTALLED", "FAILED"}
PROTECTED_MODES = {"ACCEPTED", "REJECTED", "UNREADABLE"}
EXCEPTION_NAMES = {
    "UnsupportedOperationException",
    "SecurityException",
    "NoSuchMethodException",
    "ClassNotFoundException",
    "NoClassDefFoundError",
    "Other",
}
STAGE_STATUSES = {"pass", "fail", "blocked"}
REASON_RE = re.compile(r"[A-Z][A-Z0-9_]{2,63}")
PACKAGE_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+")
DEVICE_FACT_KEYS = {
    "sdk_int",
    "abis",
    "feature",
    "build_id",
    "release",
    "fingerprint_sha256",
}
QUALIFICATION_KEYS = {
    "access_state",
    "exemption_state",
    "manager_present",
    "non_protected",
    "protected",
    "capabilities_raw",
    "manage_permission",
    "custom_permission",
    "protected_mode",
    "protected_mode_exception",
}
PROBE_KEYS = {
    "schema_version",
    "kind",
    "qa_package",
    "mode",
    "device_facts",
    "qualification",
    "stage_results",
}
STAGE_RESULT_KEYS = {"status", "reason_code"}
STOP_RESULT_KEYS = {"status", "reason_code", "stop_confirmed"}
COMMAND_KEYS = {"id", "argv", "timeout_seconds", "exit_code", "duration_ms"}
ABI_ALLOWLIST = {"arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "riscv64"}


class DeviceStop(Exception):
    """One typed, non-retryable stop in the device sequence."""

    def __init__(self, reason_code, stage_id="platform_declaration", detail=None):
        super().__init__(reason_code)
        self.reason_code = reason_code
        self.stage_id = stage_id
        self.detail = detail or {}


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


def is_qa_package(package):
    """The QA channel is the only package this tool may install or grant to."""
    return bool(PACKAGE_RE.fullmatch(package)) and ".qa." in package


def resolve_adb(explicit):
    if explicit:
        return avf_preflight.adb_value(explicit)
    for candidate in (shutil.which("adb"), "/opt/android-sdk/platform-tools/adb"):
        if candidate and Path(candidate).is_file():
            try:
                return avf_preflight.adb_value(candidate)
            except Rejected:
                continue
    raise Rejected("ADB_UNAVAILABLE")


def _checked_text(value):
    if len(value) > MAX_ADB_OUTPUT:
        raise Rejected("ADB_OUTPUT_REJECTED")
    try:
        return value.decode("utf-8", errors="strict")
    except UnicodeError:
        raise Rejected("ADB_ENCODING_REJECTED") from None


class AdbRunner:
    """The only production command runner. Fake runners are test-only."""

    def __init__(self, adb, serial, *, timeout_seconds=ADB_TIMEOUT_SECONDS,
                 deadline_seconds=600, clock=time.monotonic):
        self.adb = avf_preflight.adb_value(adb)
        self.serial = avf_preflight.serial_value(serial)
        self.timeout_seconds = float(timeout_seconds)
        self.deadline = clock() + float(deadline_seconds)
        self.clock = clock
        self.commands = []

    def _remaining(self):
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise Rejected("DEVICE_DEADLINE_EXCEEDED")
        return remaining

    def _redacted_argv(self, argv):
        cleaned = []
        for index, argument in enumerate(argv):
            if index == 0:
                cleaned.append("<adb>")
            elif argument == self.serial:
                cleaned.append("<device>")
            elif index > 0 and argv[index - 1] in ("qaRunToken", "qaBridgeToken"):
                cleaned.append("<token>")
            elif isinstance(argument, str) and argument.startswith("files/avf-qa/probe-"):
                cleaned.append("files/avf-qa/probe-<token>.json")
            elif isinstance(argument, str) and argument.lower().endswith(".apk"):
                cleaned.append("<apk>")
            elif index > 0 and argv[index - 1] == "push":
                cleaned.append("<artifact>")
            elif isinstance(argument, str):
                cleaned.append(re.sub(r"/data/local/tmp/avf-qa-stage-[0-9a-f]+",
                                      "<staging>", argument))
            else:
                cleaned.append(argument)
        return cleaned

    def _command_id(self, argv):
        tail = argv[3:] if len(argv) > 3 and argv[1] == "-s" else argv[1:]
        if tail[:1] == ["shell"]:
            fixed = tail[1:]
            if fixed[:1] == ["getprop"] and len(fixed) == 2:
                return "preflight." + fixed[1]
            if fixed[:3] == ["cmd", "package", "has-feature"]:
                return "preflight.feature"
            if fixed[:4] == ["cmd", "package", "list", "packages"]:
                return "preflight.apex"
            if fixed[:1] == ["pm"]:
                return "package." + "_".join(fixed[:2])
            if fixed[:1] == ["am"]:
                return "instrument"
        if tail[:1] == ["devices"]:
            return "discover"
        if tail[:1] == ["install"]:
            return "install"
        if tail[:1] == ["exec-out"]:
            return "pull_probe"
        return "adb"

    def run(self, argv, *, timeout_seconds=None, allow_failure=False):
        remaining = self._remaining()
        timeout = min(float(timeout_seconds or self.timeout_seconds), remaining)
        started = self.clock()
        environment = os.environ.copy()
        environment["MSYS_NO_PATHCONV"] = "1"
        try:
            process = subprocess.run(
                argv,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                shell=False,
                env=environment,
            )
        except subprocess.TimeoutExpired:
            self.commands.append({
                "id": self._command_id(argv),
                "argv": self._redacted_argv(argv),
                "timeout_seconds": round(timeout, 3),
                "exit_code": None,
                "duration_ms": round((self.clock() - started) * 1000.0, 3),
            })
            raise Rejected("ADB_TIMEOUT") from None
        except OSError:
            raise Rejected("ADB_UNAVAILABLE") from None
        stdout = _checked_text(process.stdout)
        stderr = _checked_text(process.stderr)
        self.commands.append({
            "id": self._command_id(argv),
            "argv": self._redacted_argv(argv),
            "timeout_seconds": round(timeout, 3),
            "exit_code": process.returncode,
            "duration_ms": round((self.clock() - started) * 1000.0, 3),
        })
        if process.returncode != 0 and not allow_failure:
            raise Rejected("ADB_QUERY_FAILED")
        return CommandResult(process.returncode, stdout, stderr)

    def __call__(self, argv):
        return self.run(argv).stdout


def parse_device_list(raw, target_serial):
    """Counts only; the serial is compared but never returned or recorded."""
    lines = raw.splitlines()
    if not lines or lines[0].strip() != "List of devices attached":
        raise Rejected("DISCOVERY_FORMAT_REJECTED")
    states = []
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split()
        serial = parts[0] if parts else ""
        state = parts[1] if len(parts) >= 2 else "other"
        states.append((serial, state))
    if not states:
        raise Rejected("NO_DEVICE")
    if any(state == "unauthorized" for _, state in states):
        raise Rejected("DEVICE_UNAUTHORIZED")
    if any(state == "offline" for _, state in states):
        raise Rejected("DEVICE_OFFLINE")
    ready = [serial for serial, state in states if state == "device"]
    if len(ready) != 1:
        raise Rejected("DEVICE_COUNT_NOT_ONE")
    if ready[0] != target_serial:
        raise Rejected("TARGET_DEVICE_MISMATCH")
    return {"ready": 1}


def _check_keys(value, keys):
    if not isinstance(value, dict) or set(value) != keys:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")


def _check_reason(value):
    if not isinstance(value, str) or not REASON_RE.fullmatch(value):
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")


def validate_probe(probe, expected_package):
    _check_keys(probe, PROBE_KEYS)
    if probe["schema_version"] != 1 or probe["kind"] != "avf_qa_probe":
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if probe["mode"] not in QA_MODES:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if probe["qa_package"] != expected_package:
        raise Rejected("QA_PROBE_PACKAGE_MISMATCH")
    facts = probe["device_facts"]
    _check_keys(facts, DEVICE_FACT_KEYS)
    if (type(facts["sdk_int"]) is not int or not 1 <= facts["sdk_int"] <= 1000
            or type(facts["feature"]) is not bool):
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    abis = facts["abis"]
    if (not isinstance(abis, list) or not 1 <= len(abis) <= len(ABI_ALLOWLIST)
            or len(set(abis)) != len(abis) or any(a not in ABI_ALLOWLIST for a in abis)):
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if (not isinstance(facts["build_id"], str)
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,95}", facts["build_id"])):
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if (not isinstance(facts["release"], str)
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,31}", facts["release"])):
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if (not isinstance(facts["fingerprint_sha256"], str)
            or not re.fullmatch(r"[0-9a-f]{64}", facts["fingerprint_sha256"])):
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    qual = probe["qualification"]
    _check_keys(qual, QUALIFICATION_KEYS)
    if qual["access_state"] not in ACCESS_STATES:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if qual["exemption_state"] not in EXEMPTION_STATES:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if type(qual["manager_present"]) is not bool or type(qual["non_protected"]) is not bool \
            or type(qual["protected"]) is not bool:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if type(qual["capabilities_raw"]) is not int or qual["capabilities_raw"] < -1:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if qual["manage_permission"] not in PERMISSION_STATES \
            or qual["custom_permission"] not in PERMISSION_STATES:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if qual["protected_mode"] not in PROTECTED_MODES:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    if qual["protected_mode_exception"] is not None \
            and qual["protected_mode_exception"] not in EXCEPTION_NAMES:
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    results = probe["stage_results"]
    if not isinstance(results, dict) or any(key not in PROBE_STAGE_IDS for key in results):
        raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    for stage_id, result in results.items():
        expected_keys = STOP_RESULT_KEYS if stage_id == "stop_cycles" else STAGE_RESULT_KEYS
        _check_keys(result, expected_keys)
        if result["status"] not in STAGE_STATUSES:
            raise Rejected("QA_PROBE_SCHEMA_REJECTED")
        _check_reason(result["reason_code"])
        if stage_id == "stop_cycles" and type(result["stop_confirmed"]) is not bool:
            raise Rejected("QA_PROBE_SCHEMA_REJECTED")
    return probe


def _stage_result(report, stage_id):
    for stage in report["stages"]:
        if stage["id"] == stage_id:
            return stage
    raise KeyError(stage_id)


def _set_stage(report, stage_id, status, reason_code, evidence_payload, observed=None):
    stage = _stage_result(report, stage_id)
    stage["status"] = status
    stage["reason_code"] = reason_code
    if status == "not-tested":
        stage["evidence"] = {"state": "not-collected", "path": None, "sha256": None}
        stage["sampled_at_utc"] = None
        stage["rom_binding"] = {"state": "not-collected", "digest": None}
        return
    stage["evidence"] = {"state": "pending", "path": None, "sha256": None}
    stage["sampled_at_utc"] = report["generated_at_utc"]
    stage["rom_binding"] = {"state": "collected", "digest": None}
    evidence_payload[stage_id] = {
        "schema_version": 1,
        "kind": "avf_device_stage_evidence",
        "stage": stage_id,
        "status": status,
        "reason_code": reason_code,
        "observed": observed or {},
    }


def _prior_status(report, stage_id):
    prior = STAGE_IDS[:STAGE_IDS.index(stage_id)]
    for candidate in prior:
        # vsock is optional for a protected Microdroid lifecycle; it must not
        # prevent the bounded stop from being attempted or recorded.
        if stage_id == "stop_cycles" and candidate == "vsock_handshake":
            continue
        status = _stage_result(report, candidate)["status"]
        if status == "fail":
            return "fail"
        if status == "blocked":
            return "blocked"
    return "pass"


def _apply_probe_stage(report, stage_id, evidence_payload, probe):
    prior = _prior_status(report, stage_id)
    if prior != "pass":
        _set_stage(report, stage_id, "blocked",
                   "PRIOR_STAGE_FAILED" if prior == "fail" else "PRIOR_STAGE_BLOCKED",
                   evidence_payload, {"prior": prior})
        return
    result = probe["stage_results"].get(stage_id)
    if result is None:
        _set_stage(report, stage_id, "blocked", "QA_STAGE_RESULT_MISSING",
                   evidence_payload, {"probe_reason": None})
        return
    if stage_id == "stop_cycles" and result["status"] == "pass" \
            and not result["stop_confirmed"]:
        _set_stage(report, stage_id, "fail", "STOP_UNCONFIRMED", evidence_payload,
                   {"probe_reason": result["reason_code"], "stop_confirmed": False})
        return
    if result["status"] == "pass":
        _set_stage(report, stage_id, "pass", result["reason_code"], evidence_payload,
                   {"probe_reason": result["reason_code"]})
    elif result["status"] == "fail":
        fixed = {
            "create": "CREATE_FAILED",
            "run_returned": "RUN_FAILED",
            "vsock_handshake": ("VSOCK_TIMEOUT"
                                if "TIMEOUT" in result["reason_code"]
                                else "VSOCK_HANDSHAKE_FAILED"),
            "stop_cycles": "STOP_UNCONFIRMED",
        }.get(stage_id, "QA_STAGE_FAILED")
        _set_stage(report, stage_id, "fail", fixed, evidence_payload,
                   {"probe_reason": result["reason_code"]})
    else:
        _set_stage(report, stage_id, "blocked", result["reason_code"], evidence_payload,
                   {"probe_reason": result["reason_code"]})


def _preflight_value(preflight, field):
    entry = preflight.get("fields", {}).get(field)
    if not isinstance(entry, dict) or entry.get("state") != "observed":
        return None
    return entry.get("value")


def _report_stub(mode, device_alias, now, stages, qa_mode="qualification"):
    return {
        "schema_version": 1,
        "kind": "avf_device_validation",
        "mode": mode,
        "qa_mode": qa_mode,
        "device_independent": mode == "dry-run",
        "generated_at_utc": now,
        "device_alias": device_alias,
        "stages": stages,
        "summary": {"pass": 0, "fail": 0, "blocked": 0, "not-tested": 0},
        "redaction": {
            key: False for key in (
                "serials_recorded",
                "raw_device_output_recorded",
                "imei_recorded",
                "mac_recorded",
                "tokens_recorded",
                "screen_content_recorded",
            )
        },
        "platform_qualification_samples": 0,
        "protected_microdroid_samples": 0,
        "custom_linux_samples": 0,
        "real_avf_samples": 0,
        "verdict": "NOT_TESTED",
        "device_mode": {
            "implemented": mode == "device",
            "adb_invoked": False,
            "serial_recorded": False,
        },
        "commands": [],
        "device_facts": None,
        "probe": None,
    }


def _refresh_summary(report):
    counts = {"pass": 0, "fail": 0, "blocked": 0, "not-tested": 0}
    for stage in report["stages"]:
        counts[stage["status"]] += 1
    report["summary"] = counts
    if counts["fail"]:
        report["verdict"] = "FAILED"
    elif counts["blocked"]:
        report["verdict"] = "BLOCKED"
    elif counts["pass"]:
        report["verdict"] = "PASS"
    else:
        report["verdict"] = "NOT_TESTED"


def _lifecycle_passed(report):
    required = ("create", "run_returned", "stop_cycles")
    return all(_stage_result(report, stage)["status"] == "pass"
               for stage in required)


def _sample_counts(report, qa_mode):
    qualification = 1 if (
        _stage_result(report, "platform_declaration")["status"] == "pass"
        and _stage_result(report, "capabilities")["status"] == "pass"
    ) else 0
    lifecycle = _lifecycle_passed(report)
    protected = 1 if qa_mode == "protected-microdroid" and lifecycle else 0
    custom = 1 if qa_mode == "custom-linux" and lifecycle else 0
    return {
        "platform_qualification_samples": qualification,
        "protected_microdroid_samples": protected,
        "custom_linux_samples": custom,
        "real_avf_samples": protected + custom,
    }


def _blocked_report(mode, alias, now, stages, reason_code, commands,
                    qa_mode="qualification"):
    report = _report_stub(mode, alias, now, stages, qa_mode)
    report["device_mode"]["adb_invoked"] = bool(commands)
    report["commands"] = commands
    for stage in report["stages"]:
        if stage["status"] == "not-tested" and stage["reason_code"] == "STAGE_NOT_REQUESTED":
            continue
        stage["status"] = "blocked"
        stage["reason_code"] = reason_code
    _refresh_summary(report)
    return report


def _announce(runner, argv):
    """Prints the redacted command before an explicit device action."""
    print("COMMAND: " + " ".join(runner._redacted_argv(argv)), file=sys.stderr)


def _install_package(runner, adb, serial, package, apk, test_only):
    if apk is None:
        path = runner.run([adb, "-s", serial, "shell", "pm", "path", package],
                          allow_failure=True)
        if "package:" not in path.stdout:
            raise DeviceStop("QA_PACKAGE_NOT_INSTALLED", "platform_declaration")
        return None
    apk_path = Path(apk)
    if not apk_path.is_absolute() or not apk_path.is_file():
        raise DeviceStop("QA_APK_REJECTED", "platform_declaration")
    digest = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    argv = [adb, "-s", serial, "install", "-r"]
    if test_only:
        argv.append("-t")
    argv.append(str(apk_path))
    _announce(runner, argv)
    result = runner.run(argv, timeout_seconds=INSTALL_TIMEOUT_SECONDS, allow_failure=True)
    if result.returncode != 0 or "Success" not in result.stdout:
        raise DeviceStop("QA_INSTALL_FAILED", "platform_declaration")
    return digest


def _probe(runner, adb, serial, qa_package, test_package, token, stages, cycles,
           *, qa_mode="qualification", payload=None, vsock_port=0,
           stop_deadline_ms=DEFAULT_STOP_DEADLINE_MS, custom_kernel=None,
           custom_initrd=None, custom_root=None, bridge_token=None,
           vsock_terminal_port=DEFAULT_VSOCK_TERMINAL_PORT,
           custom_memory_mb=DEFAULT_CUSTOM_MEMORY_MB,
           vsock_timeout_ms=DEFAULT_VSOCK_TIMEOUT_MS):
    argv = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "qaRunToken", token,
        "-e", "qaMode", qa_mode,
        "-e", "qaStages", ",".join(stages),
        "-e", "qaCycles", str(cycles),
        "-e", "qaStopDeadlineMs", str(stop_deadline_ms),
    ]
    if qa_mode == "protected-microdroid" and payload:
        argv += ["-e", "qaMicrodroidPayload", payload]
    if qa_mode == "custom-linux":
        control_port = vsock_port or DEFAULT_VSOCK_CONTROL_PORT
        argv += [
            "-e", "qaVsockPort", str(control_port),
            "-e", "qaVsockTerminalPort", str(vsock_terminal_port),
            "-e", "qaVsockTimeoutMs", str(vsock_timeout_ms),
            "-e", "qaCustomMemoryMb", str(custom_memory_mb),
        ]
        if bridge_token:
            argv += ["-e", "qaBridgeToken", bridge_token]
        argv += [
            "-e", "qaCustomKernel", custom_kernel or DEFAULT_CUSTOM_KERNEL,
            "-e", "qaCustomInitrd", custom_initrd or DEFAULT_CUSTOM_INITRD,
            "-e", "qaCustomRoot", custom_root or DEFAULT_CUSTOM_ROOT,
        ]
    elif vsock_port:
        argv += ["-e", "qaVsockPort", str(vsock_port)]
    argv.append(test_package + "/" + QA_RUNNER)
    _announce(runner, argv)
    instrument_timeout = max(
        INSTRUMENT_TIMEOUT_SECONDS, (vsock_timeout_ms / 1000.0) + 60.0)
    result = runner.run(argv, timeout_seconds=instrument_timeout,
                        allow_failure=True)
    if ("INSTRUMENTATION_FAILED" in result.stdout
            or "INSTRUMENTATION_ABORTED" in result.stdout
            or result.returncode != 0):
        raise DeviceStop("QA_INSTRUMENTATION_FAILED", "capabilities")
    relative = "files/avf-qa/probe-" + token + ".json"
    pull_argv = [adb, "-s", serial, "exec-out", "run-as", qa_package, "cat", relative]
    _announce(runner, pull_argv)
    pull = runner.run(pull_argv, allow_failure=True)
    if pull.returncode != 0 or not pull.stdout.strip():
        raise DeviceStop("QA_PROBE_UNREADABLE", "capabilities")
    try:
        probe = parse_json(pull.stdout.encode("utf-8"))
        return validate_probe(probe, qa_package)
    except Rejected as error:
        raise DeviceStop(str(error), "capabilities") from None
    except (UnicodeError, json.JSONDecodeError):
        raise DeviceStop("QA_PROBE_SCHEMA_REJECTED", "capabilities") from None


def run_device(*, adb_path, serial, alias, qa_package, qa_test_package, apk, test_apk,
               grant, cycles, stages, now, qa_mode="qualification",
               microdroid_payload=None, vsock_port=0,
               stop_deadline_ms=DEFAULT_STOP_DEADLINE_MS,
               custom_kernel=None, custom_initrd=None, custom_root=None,
               stage_custom=False, custom_plan=None, custom_distro=None,
               custom_apk=None, custom_root_bytes=None,
               custom_memory_mb=DEFAULT_CUSTOM_MEMORY_MB,
               vsock_terminal_port=DEFAULT_VSOCK_TERMINAL_PORT,
               vsock_timeout_ms=DEFAULT_VSOCK_TIMEOUT_MS,
               timeout_seconds=ADB_TIMEOUT_SECONDS, deadline_seconds=600):
    """Runs the device sequence and returns (report, evidence, staging receipt)."""
    if not is_qa_package(qa_package):
        raise Rejected("QA_PACKAGE_REQUIRED")
    if not is_qa_package(qa_test_package):
        raise Rejected("QA_TEST_PACKAGE_REQUIRED")
    if qa_test_package != qa_package + ".test":
        raise Rejected("QA_TEST_PACKAGE_REQUIRED")
    if qa_mode not in QA_MODES:
        raise Rejected("QA_MODE_REJECTED")
    if stage_custom and qa_mode != "custom-linux":
        raise Rejected("CUSTOM_STAGING_WITHOUT_CUSTOM_MODE")
    if stage_custom and (custom_plan is None or custom_distro is None):
        raise Rejected("CUSTOM_STAGING_ARGUMENTS_REQUIRED")
    if type(custom_memory_mb) is not int or not 512 <= custom_memory_mb <= 4096:
        raise Rejected("CUSTOM_MEMORY_REJECTED")
    if type(vsock_terminal_port) is not int or not 0 <= vsock_terminal_port <= 65535:
        raise Rejected("VSOCK_PORT_REJECTED")
    if type(vsock_timeout_ms) is not int or not 1000 <= vsock_timeout_ms <= MAX_VSOCK_TIMEOUT_MS:
        raise Rejected("VSOCK_TIMEOUT_REJECTED")
    if type(cycles) is not int or not 1 <= cycles <= 20:
        raise Rejected("CYCLES_REJECTED")
    if type(vsock_port) is not int or not 0 <= vsock_port <= 65535:
        raise Rejected("VSOCK_PORT_REJECTED")
    if type(stop_deadline_ms) is not int or not 1000 <= stop_deadline_ms <= 60000:
        raise Rejected("STOP_DEADLINE_REJECTED")
    if microdroid_payload is not None and not PAYLOAD_RE.fullmatch(microdroid_payload):
        raise Rejected("MICRODROID_PAYLOAD_REJECTED")
    for value in (custom_kernel, custom_initrd, custom_root):
        if value is not None and (not isinstance(value, str)
                                  or not re.fullmatch(r"[A-Za-z0-9_./-]{1,200}", value)
                                  or ".." in value or value.startswith("/")):
            raise Rejected("CUSTOM_ARTIFACT_REJECTED")
    if not 0 < float(timeout_seconds) <= 600 or not 0 < float(deadline_seconds) <= 7200:
        raise Rejected("TIMEOUT_REJECTED")
    unknown = [stage for stage in stages if stage not in STAGE_IDS]
    if unknown:
        raise Rejected("STAGE_REJECTED")
    adb = resolve_adb(adb_path)
    runner = AdbRunner(adb, serial, timeout_seconds=timeout_seconds,
                       deadline_seconds=deadline_seconds)
    base_stages = [
        {
            "id": stage_id,
            "title": "",
            "actor": "",
            "status": "blocked" if stage_id in stages else "not-tested",
            "reason_code": "PENDING" if stage_id in stages else "STAGE_NOT_REQUESTED",
            "evidence": {"state": "not-collected", "path": None, "sha256": None},
            "sampled_at_utc": None,
            "rom_binding": {"state": "not-collected", "digest": None},
            "checks": [],
        }
        for stage_id in STAGE_IDS
    ]
    # The caller replaces titles/actors/checks with the canonical STAGES table.
    report = _report_stub("device", alias, now, base_stages, qa_mode)
    report["device_mode"]["adb_invoked"] = False
    evidence = {}
    staging_receipt = None

    try:
        discovery = runner.run([adb, "devices", "-l"], allow_failure=True)
        if discovery.returncode != 0:
            raise DeviceStop("ADB_QUERY_FAILED", "platform_declaration")
        parse_device_list(discovery.stdout, serial)
        report["device_mode"]["adb_invoked"] = True
        report["commands"] = list(runner.commands)
    except Rejected as error:
        report = _blocked_report("device", alias, now, base_stages, str(error),
                                 list(runner.commands), qa_mode)
        return report, {}, staging_receipt

    try:
        preflight = avf_preflight.collect(adb, serial, alias, runner=runner)
    except Rejected as error:
        preflight = {"status": "PARTIAL_PREFLIGHT", "fields": {}, "error": str(error)}

    def blocked(reason_code):
        report = _blocked_report("device", alias, now, base_stages, reason_code,
                                 list(runner.commands), qa_mode)
        report["commands"] = list(runner.commands)
        return report, {}, staging_receipt

    try:
        _install_package(runner, adb, serial, qa_package, apk, test_only=False)
        _install_package(runner, adb, serial, qa_test_package, test_apk, test_only=True)
    except (DeviceStop, Rejected) as stop:
        return blocked(getattr(stop, "reason_code", str(stop)))

    token = uuid.uuid4().hex
    try:
        probe = _probe(runner, adb, serial, qa_package, qa_test_package, token,
                       stages, cycles, qa_mode="qualification",
                       stop_deadline_ms=stop_deadline_ms)
    except (DeviceStop, Rejected) as stop:
        return blocked(getattr(stop, "reason_code", str(stop)))

    if grant:
        manage = probe["qualification"]["manage_permission"]
        custom = probe["qualification"]["custom_permission"]
        if manage != "GRANTED" or custom != "GRANTED":
            try:
                for permission in PERMISSIONS:
                    grant_argv = [adb, "-s", serial, "shell", "pm", "grant",
                                  qa_package, permission]
                    _announce(runner, grant_argv)
                    result = runner.run(grant_argv, allow_failure=True)
                    if result.returncode != 0:
                        return blocked("PERMISSIONS_GRANT_FAILED")
                force_argv = [adb, "-s", serial, "shell", "am", "force-stop", qa_package]
                _announce(runner, force_argv)
                runner.run(force_argv, allow_failure=True)
                token = uuid.uuid4().hex
                probe = _probe(runner, adb, serial, qa_package, qa_test_package, token,
                               stages, cycles, qa_mode="qualification",
                               stop_deadline_ms=stop_deadline_ms)
            except (DeviceStop, Rejected) as stop:
                return blocked(getattr(stop, "reason_code", str(stop)))

    facts = probe["device_facts"]
    qual = probe["qualification"]
    report["device_facts"] = {
        "sdk": facts["sdk_int"],
        "abis": list(facts["abis"]),
        "feature": facts["feature"],
        "rom": {
            "build_id": facts["build_id"],
            "release": facts["release"],
            "fingerprint_sha256": facts["fingerprint_sha256"],
        },
        "capabilities": {
            "raw": qual["capabilities_raw"],
            "protected": qual["protected"],
            "non_protected": qual["non_protected"],
            "source": "VirtualMachineManager.getCapabilities",
            "actor": "app",
        },
        "access_state": qual["access_state"],
        "exemption_state": qual["exemption_state"],
        "manager_present": qual["manager_present"],
        "protected_only": bool(qual["protected"] and not qual["non_protected"]),
        "permissions": {
            "manage": qual["manage_permission"],
            "custom": qual["custom_permission"],
        },
        "protected_mode": qual["protected_mode"],
        "protected_mode_exception": qual["protected_mode_exception"],
    }
    report["probe"] = {
        "qa_package": qa_package,
        "qa_test_package": qa_test_package,
        "runner": QA_RUNNER,
        "run_token_sha256": hashlib.sha256(token.encode("ascii")).hexdigest(),
        "schema_version": 1,
        "mode": probe["mode"],
    }
    report["commands"] = list(runner.commands)
    report["device_mode"]["adb_invoked"] = True

    api = _preflight_value(preflight, "api")
    abi = _preflight_value(preflight, "abi")
    feature = _preflight_value(preflight, "avf_feature")
    declared_nonprotected = _preflight_value(preflight, "declared_nonprotected")
    declared_protected = _preflight_value(preflight, "declared_protected")
    preflight_complete = (preflight.get("status") == "PREFLIGHT_ONLY"
                          and isinstance(api, int) and isinstance(abi, list)
                          and feature is not None)
    declaration_observed = {
        "api": api,
        "abi": abi,
        "feature": feature,
        "declared_nonprotected": declared_nonprotected,
        "declared_protected": declared_protected,
        "preflight_status": preflight.get("status"),
    }
    if not preflight_complete:
        _set_stage(report, "platform_declaration", "blocked",
                   "PLATFORM_DECLARATION_INCOMPLETE", evidence, declaration_observed)
    elif api < 33:
        _set_stage(report, "platform_declaration", "fail", "API_TOO_LOW", evidence,
                   declaration_observed)
    elif "arm64-v8a" not in abi:
        _set_stage(report, "platform_declaration", "fail", "ABI_MISSING", evidence,
                   declaration_observed)
    elif feature is not True:
        _set_stage(report, "platform_declaration", "fail", "FEATURE_MISSING", evidence,
                   declaration_observed)
    elif declared_nonprotected is None and declared_protected is None:
        _set_stage(report, "platform_declaration", "blocked",
                   "HYPERVISOR_DECLARATION_MISSING", evidence, declaration_observed)
    else:
        _set_stage(report, "platform_declaration", "pass", "PLATFORM_DECLARED",
                   evidence, declaration_observed)

    qual_observed = {
        "access_state": qual["access_state"],
        "exemption_state": qual["exemption_state"],
        "manager_present": qual["manager_present"],
        "capabilities_raw": qual["capabilities_raw"],
        "protected": qual["protected"],
        "non_protected": qual["non_protected"],
    }
    if qual["access_state"] in ("AVAILABLE", "MODE_UNSUPPORTED") \
            and (qual["protected"] or qual["non_protected"]):
        if qual["non_protected"] and qual["protected"]:
            reason = "PLATFORM_QUALIFIED_DUAL"
        elif qual["non_protected"]:
            reason = "PLATFORM_QUALIFIED_NON_PROTECTED"
        else:
            reason = "PLATFORM_QUALIFIED_PROTECTED_ONLY"
        _set_stage(report, "capabilities", "pass", reason, evidence, qual_observed)
    elif qual["access_state"] in ("AVAILABLE", "MODE_UNSUPPORTED") \
            and not qual["protected"] and not qual["non_protected"]:
        _set_stage(report, "capabilities", "fail", "NO_VM_CAPABILITY", evidence,
                   qual_observed)
    elif qual["access_state"] == "HIDDEN_API_BLOCKED":
        _set_stage(report, "capabilities", "blocked", "HIDDEN_API_BLOCKED", evidence,
                   qual_observed)
    elif qual["access_state"] == "PERMISSION_DENIED":
        _set_stage(report, "capabilities", "blocked", "PERMISSION_DENIED", evidence,
                   qual_observed)
    elif qual["access_state"] == "REFLECTION_UNAVAILABLE":
        _set_stage(report, "capabilities", "blocked", "REFLECTION_UNAVAILABLE", evidence,
                   qual_observed)
    else:
        _set_stage(report, "capabilities", "blocked", "CAPABILITIES_UNREADABLE", evidence,
                   qual_observed)

    permissions_observed = {
        "manage": qual["manage_permission"],
        "custom": qual["custom_permission"],
        "grant_requested": bool(grant),
    }
    if qual["manage_permission"] == "GRANTED" and qual["custom_permission"] == "GRANTED":
        _set_stage(report, "permissions", "pass", "PERMISSIONS_GRANTED", evidence,
                   permissions_observed)
    elif grant:
        _set_stage(report, "permissions", "fail", "PERMISSIONS_GRANT_FAILED", evidence,
                   permissions_observed)
    elif qual["manage_permission"] == "DENIED" or qual["custom_permission"] == "DENIED":
        _set_stage(report, "permissions", "blocked", "GRANT_NOT_AUTHORIZED", evidence,
                   permissions_observed)
    else:
        _set_stage(report, "permissions", "blocked", "PERMISSIONS_UNREADABLE", evidence,
                   permissions_observed)

    protected_observed = {
        "protected_mode": qual["protected_mode"],
        "exception": qual["protected_mode_exception"],
    }
    if qual["protected_mode"] == "ACCEPTED":
        _set_stage(report, "protected_mode", "pass", "NON_PROTECTED_CONFIG_ACCEPTED",
                   evidence, protected_observed)
    elif qual["protected_mode"] == "REJECTED":
        _set_stage(report, "protected_mode", "pass", "NON_PROTECTED_CONFIG_REFUSED",
                   evidence, protected_observed)
    else:
        _set_stage(report, "protected_mode", "blocked", "PROTECTED_MODE_UNREADABLE",
                   evidence, protected_observed)

    launch_probe = None
    launch_block_reason = None
    staging_failure = None
    bridge_token = None
    if qa_mode == "custom-linux" and qual["non_protected"] and stage_custom:
        try:
            staging_plan = avf_qa_staging.load_plan(
                custom_plan, custom_distro, apk_path=custom_apk,
                root_bytes=custom_root_bytes)
            staging_receipt = avf_qa_staging.stage(
                runner, adb, serial, qa_package, staging_plan)
            bridge_token = staging_plan.bridge_token
        except (Rejected, OSError) as error:
            staging_failure = (str(error) if isinstance(error, Rejected)
                               else "CUSTOM_STAGING_FAILED")
    if qa_mode == "qualification":
        launch_block_reason = "QA_LAUNCH_NOT_REQUESTED"
    elif qa_mode == "protected-microdroid" and not qual["protected"]:
        launch_block_reason = "PROTECTED_VM_UNAVAILABLE"
    elif qa_mode == "custom-linux" and not qual["non_protected"]:
        launch_block_reason = "NO_NON_PROTECTED_VM"
    elif staging_failure is not None:
        launch_block_reason = staging_failure
    elif _prior_status(report, "create") != "pass":
        launch_block_reason = "PRIOR_STAGE_BLOCKED"

    if launch_block_reason is None:
        launch_payload = microdroid_payload
        try:
            launch_token = uuid.uuid4().hex
            launch_probe = _probe(
                runner, adb, serial, qa_package, qa_test_package, launch_token,
                stages, cycles, qa_mode=qa_mode, payload=launch_payload,
                vsock_port=vsock_port, stop_deadline_ms=stop_deadline_ms,
                custom_kernel=custom_kernel, custom_initrd=custom_initrd,
                custom_root=custom_root, bridge_token=bridge_token,
                vsock_terminal_port=vsock_terminal_port,
                custom_memory_mb=custom_memory_mb,
                vsock_timeout_ms=vsock_timeout_ms)
            report["probe"]["run_token_sha256"] = hashlib.sha256(
                launch_token.encode("ascii")).hexdigest()
            report["probe"]["mode"] = qa_mode
        except (DeviceStop, Rejected) as stop:
            launch_block_reason = getattr(stop, "reason_code", str(stop))

    for stage_id in PROBE_STAGE_IDS:
        if stage_id not in stages:
            _set_stage(report, stage_id, "not-tested", "STAGE_NOT_REQUESTED", evidence)
            continue
        if launch_probe is not None:
            _apply_probe_stage(report, stage_id, evidence, launch_probe)
        else:
            reason = launch_block_reason or "QA_LAUNCH_NOT_REQUESTED"
            if stage_id == "stop_cycles":
                _set_stage(report, stage_id, "blocked", reason, evidence,
                           {"stop_confirmed": False})
            else:
                _set_stage(report, stage_id, "blocked", reason, evidence, {})

    counters = _sample_counts(report, qa_mode)
    for key, value in counters.items():
        report[key] = value
    report["commands"] = list(runner.commands)
    _refresh_summary(report)
    return report, evidence, staging_receipt
