#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""One-command AVF device-validation harness.

The harness defines the twelve stages from the device-gate checklist in
docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md section 3.3 and
emits one versioned JSON report plus a Markdown summary.

Modes:

- '--dry-run' never contacts a device and marks every stage 'not-tested'.
- '--device <serial>' runs the host-side sequence through adb with per-command
  timeouts and a whole-run deadline. It never records a serial, raw adb output,
  token, user path or screen content. The device-side half is a QA-only
  instrumentation probe; absent observations stay blocked, never pass.

The harness never roots, unlocks, changes system policy, installs a production
package, or runs a VM outside a QA package. A 'pass' here is only the status of
one observed stage; it is not a product-wide AVF support claim.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True

import avf_device_mode as device
import avf_preflight
from avf_evidence_io import EvidenceStore, Rejected, SafeParser, parse_json

SCHEMA_VERSION = 1
KIND = "avf_device_validation"
ALLOWED_STATUSES = ("pass", "fail", "blocked", "not-tested")
REPORT_KEYS = {
    "schema_version",
    "kind",
    "mode",
    "qa_mode",
    "device_independent",
    "generated_at_utc",
    "device_alias",
    "stages",
    "summary",
    "redaction",
    "platform_qualification_samples",
    "protected_microdroid_samples",
    "custom_linux_samples",
    "real_avf_samples",
    "verdict",
    "device_mode",
    "commands",
    "device_facts",
    "probe",
}
STAGE_KEYS = {
    "id",
    "title",
    "actor",
    "status",
    "reason_code",
    "evidence",
    "sampled_at_utc",
    "rom_binding",
    "checks",
}
EVIDENCE_KEYS = {"state", "path", "sha256"}
ROM_BINDING_KEYS = {"state", "digest"}
DEVICE_MODE_KEYS = {"implemented", "adb_invoked", "serial_recorded"}
REDACTION_KEYS = {
    "serials_recorded",
    "raw_device_output_recorded",
    "imei_recorded",
    "mac_recorded",
    "tokens_recorded",
    "screen_content_recorded",
}
REPORT_DEVICE_FACT_KEYS = {
    "sdk",
    "abis",
    "feature",
    "rom",
    "capabilities",
    "access_state",
    "exemption_state",
    "manager_present",
    "protected_only",
    "permissions",
    "protected_mode",
    "protected_mode_exception",
}
REPORT_ROM_KEYS = {"build_id", "release", "fingerprint_sha256"}
REPORT_CAPABILITY_KEYS = {"raw", "protected", "non_protected", "source", "actor"}
REPORT_PERMISSION_KEYS = {"manage", "custom"}
REPORT_PROBE_KEYS = {"qa_package", "qa_test_package", "runner", "run_token_sha256",
                     "schema_version", "mode"}
DRY_RUN_REASON = "DRY_RUN_NO_DEVICE"
TIMESTAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z")
ALIAS_RE = re.compile(r"[a-z][a-z0-9-]{2,39}")

# Reuse the approved read-only preflight vocabulary instead of duplicating
# device commands. These are planned checks; dry-run never executes them.
_PREFLIGHT_DECLARATION_CHECKS = tuple(
    " ".join(avf_preflight.COMMANDS[key])
    for key in ("avf_feature", "api", "abi", "declared_nonprotected", "declared_protected"))


@dataclass(frozen=True)
class Stage:
    stage_id: str
    title: str
    actor: str
    checks: tuple[str, ...]


STAGES = (
    Stage("platform_declaration", "feature / ABI / SDK 与 hypervisor 声明", "shell",
          _PREFLIGHT_DECLARATION_CHECKS),
    Stage("capabilities", "getCapabilities bits（App 与 shell/UserService 分别记录）", "app+shell",
          ("App actor: VirtualMachineManager.getCapabilities()",
           "shell/UserService actor: same call, separate row",
           "record raw bit mask and protected/non-protected decode")),
    Stage("permissions", "MANAGE_VIRTUAL_MACHINE / USE_CUSTOM_VIRTUAL_MACHINE", "app",
          ("checkSelfPermission for both permissions",
           "record granted/denied/unknown; only an explicit --grant may change it")),
    Stage("protected_mode", "setProtectedVm(false) 可构造", "app",
          ("construct VirtualMachineConfig with setProtectedVm(false)",
           "record UnsupportedOperationException as a typed refusal")),
    Stage("create", "App 私有 kernel/initrd/root raw create", "app",
          ("kernel and initrd exist in QA-private storage",
           "root raw volume exists in QA-private storage",
           "create returns a VM handle; no user disk is touched")),
    Stage("run_returned", "run 返回（不等于 guest ready）", "app",
          ("run() returns without throwing",
           "record that return is not proof of guest readiness")),
    Stage("vsock_handshake", "vsock 9100/9101 Bridge/终端握手", "app",
          ("connect to the control and terminal vsock ports",
           "complete the authenticated Bridge/terminal handshake",
           "record bytes/channel identity, never terminal text")),
    Stage("stop_cycles", "正常 stop 与 20 次启停收敛", "app",
          ("request a normal stop and confirm STOPPED",
           "repeat create/run/stop up to twenty times",
           "record failures and orphan observations")),
    Stage("network", "网络：无网络 / 静态 / DHCP / Android 17 GpuConfig workaround", "app",
          ("boot once without useNetwork",
           "boot with the headless 2D GPU workaround",
           "record guest eth0, DHCP/static address, DNS and apt reachability",
           "classify status 35 as NETWORK_BACKEND_UNAVAILABLE")),
    Stage("share_display", "9p 共享与 RFB 显示实际连通", "app",
          ("guest display-relay.py --transport vsock",
           "read-only 9p share listing and read",
           "record frame/input evidence without screen content")),
    Stage("app_death", "App 进程死亡/强制停止后 VM 收敛", "shell",
          ("force-stop the QA app process",
           "observe the VM state after process death",
           "record whether an orphan remains")),
    Stage("qemu_comparison", "同资源 QEMU 对照入口（仅定义，不执行）", "app",
          ("same 1 vCPU / RAM / rootfs",
           "record AVF and QEMU outcomes separately",
           "no performance claim from this harness")),
)
if tuple(stage.stage_id for stage in STAGES) != device.STAGE_IDS:
    raise RuntimeError("stage table drifted from avf_device_mode")


def _now_utc(now=None):
    value = datetime.now(timezone.utc) if now is None else now
    return value.strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _stage(stage, status, reason_code, actor=None):
    return {
        "id": stage.stage_id,
        "title": stage.title,
        "actor": actor or stage.actor,
        "status": status,
        "reason_code": reason_code,
        "evidence": {"state": "not-collected", "path": None, "sha256": None},
        "sampled_at_utc": None,
        "rom_binding": {"state": "not-collected", "digest": None},
        "checks": list(stage.checks),
    }


def _summary(stages):
    counts = {status: 0 for status in ALLOWED_STATUSES}
    for stage in stages:
        counts[stage["status"]] += 1
    return {
        "pass": counts["pass"],
        "fail": counts["fail"],
        "blocked": counts["blocked"],
        "not-tested": counts["not-tested"],
    }


def build_report(mode, device_alias=None, now=None):
    """Builds the dry-run report; device reports come from avf_device_mode."""
    if mode != "dry-run":
        raise Rejected("MODE_REJECTED")
    if device_alias is not None and not ALIAS_RE.fullmatch(device_alias):
        raise Rejected("ALIAS_REJECTED")
    stages = [_stage(stage, "not-tested", DRY_RUN_REASON) for stage in STAGES]
    return {
        "schema_version": SCHEMA_VERSION,
        "kind": KIND,
        "mode": "dry-run",
        "qa_mode": "qualification",
        "device_independent": True,
        "generated_at_utc": _now_utc(now),
        "device_alias": device_alias,
        "stages": stages,
        "summary": _summary(stages),
        "redaction": {key: False for key in sorted(REDACTION_KEYS)},
        "platform_qualification_samples": 0,
        "protected_microdroid_samples": 0,
        "custom_linux_samples": 0,
        "real_avf_samples": 0,
        "verdict": "NOT_TESTED",
        "device_mode": {"implemented": False, "adb_invoked": False,
                        "serial_recorded": False},
        "commands": [],
        "device_facts": None,
        "probe": None,
    }


def _reject_schema():
    raise Rejected("REPORT_SCHEMA_REJECTED")


def _check_exact_keys(value, expected):
    if not isinstance(value, dict) or set(value) != expected:
        _reject_schema()


def _valid_alias(alias):
    return isinstance(alias, str) and bool(ALIAS_RE.fullmatch(alias))


def _check_command_log(commands, adb_invoked):
    if not isinstance(commands, list):
        _reject_schema()
    for record in commands:
        _check_exact_keys(record, device.COMMAND_KEYS)
        if not isinstance(record["id"], str) or not record["id"]:
            _reject_schema()
        if (not isinstance(record["argv"], list)
                or any(not isinstance(argument, str) for argument in record["argv"])):
            _reject_schema()
        if not isinstance(record["timeout_seconds"], (int, float)) \
                or record["timeout_seconds"] <= 0:
            _reject_schema()
        if record["exit_code"] is not None and type(record["exit_code"]) is not int:
            _reject_schema()
        if not isinstance(record["duration_ms"], (int, float)) or record["duration_ms"] < 0:
            _reject_schema()
    if adb_invoked and not commands:
        _reject_schema()


def _check_device_facts(facts):
    if facts is None:
        return
    _check_exact_keys(facts, REPORT_DEVICE_FACT_KEYS)
    if type(facts["sdk"]) is not int or not 1 <= facts["sdk"] <= 1000:
        _reject_schema()
    if (not isinstance(facts["abis"], list) or not facts["abis"]
            or any(abi not in device.ABI_ALLOWLIST for abi in facts["abis"])):
        _reject_schema()
    if type(facts["feature"]) is not bool or type(facts["manager_present"]) is not bool \
            or type(facts["protected_only"]) is not bool:
        _reject_schema()
    _check_exact_keys(facts["rom"], REPORT_ROM_KEYS)
    if not isinstance(facts["rom"]["build_id"], str) \
            or not isinstance(facts["rom"]["release"], str) \
            or not isinstance(facts["rom"]["fingerprint_sha256"], str) \
            or not re.fullmatch(r"[0-9a-f]{64}", facts["rom"]["fingerprint_sha256"]):
        _reject_schema()
    _check_exact_keys(facts["capabilities"], REPORT_CAPABILITY_KEYS)
    if type(facts["capabilities"]["raw"]) is not int \
            or facts["capabilities"]["raw"] < -1 \
            or type(facts["capabilities"]["protected"]) is not bool \
            or type(facts["capabilities"]["non_protected"]) is not bool:
        _reject_schema()
    _check_exact_keys(facts["permissions"], REPORT_PERMISSION_KEYS)
    if facts["permissions"]["manage"] not in device.PERMISSION_STATES \
            or facts["permissions"]["custom"] not in device.PERMISSION_STATES:
        _reject_schema()
    if facts["access_state"] not in device.ACCESS_STATES \
            or facts["exemption_state"] not in device.EXEMPTION_STATES \
            or facts["protected_mode"] not in device.PROTECTED_MODES:
        _reject_schema()


def _check_probe(probe):
    if probe is None:
        return
    _check_exact_keys(probe, REPORT_PROBE_KEYS)
    if probe["schema_version"] != 1:
        _reject_schema()
    if not device.is_qa_package(probe["qa_package"]):
        _reject_schema()
    if probe["qa_test_package"] != probe["qa_package"] + ".test":
        _reject_schema()
    if probe["mode"] not in device.QA_MODES:
        _reject_schema()
    if probe["runner"] != device.QA_RUNNER:
        _reject_schema()
    if not isinstance(probe["run_token_sha256"], str) \
            or not re.fullmatch(r"[0-9a-f]{64}", probe["run_token_sha256"]):
        _reject_schema()


def validate_report(report):
    """Strict schema check; it also refuses fabricated passes."""
    _check_exact_keys(report, REPORT_KEYS)
    if report["schema_version"] != SCHEMA_VERSION or report["kind"] != KIND:
        _reject_schema()
    mode = report["mode"]
    if mode not in ("dry-run", "device"):
        _reject_schema()
    if report["qa_mode"] not in device.QA_MODES:
        _reject_schema()
    if not isinstance(report["generated_at_utc"], str) \
            or not TIMESTAMP_RE.fullmatch(report["generated_at_utc"]):
        _reject_schema()
    alias = report["device_alias"]
    if alias is not None and not _valid_alias(alias):
        _reject_schema()
    _check_exact_keys(report["redaction"], REDACTION_KEYS)
    if any(report["redaction"].values()):
        _reject_schema()
    _check_exact_keys(report["device_mode"], DEVICE_MODE_KEYS)
    if report["device_mode"]["serial_recorded"] is not False:
        _reject_schema()
    if report["device_mode"]["implemented"] not in (True, False) \
            or report["device_mode"]["adb_invoked"] not in (True, False):
        _reject_schema()
    _check_command_log(report["commands"], report["device_mode"]["adb_invoked"])
    _check_device_facts(report["device_facts"])
    _check_probe(report["probe"])
    stages = report["stages"]
    if not isinstance(stages, list) or len(stages) != len(STAGES):
        _reject_schema()
    for index, (actual, expected) in enumerate(zip(stages, STAGES)):
        _check_exact_keys(actual, STAGE_KEYS)
        if actual["id"] != expected.stage_id or actual["title"] != expected.title:
            _reject_schema()
        if actual["actor"] != expected.actor:
            _reject_schema()
        if actual["status"] not in ALLOWED_STATUSES:
            _reject_schema()
        _check_exact_keys(actual["evidence"], EVIDENCE_KEYS)
        _check_exact_keys(actual["rom_binding"], ROM_BINDING_KEYS)
        if actual["evidence"]["state"] not in ("not-collected", "collected"):
            _reject_schema()
        if actual["rom_binding"]["state"] not in ("not-collected", "collected"):
            _reject_schema()
        if actual["evidence"]["state"] == "collected":
            if not isinstance(actual["evidence"]["path"], str) \
                    or not re.fullmatch(r"[0-9a-f]{64}", actual["evidence"]["sha256"] or ""):
                _reject_schema()
            if not isinstance(actual["sampled_at_utc"], str) \
                    or not TIMESTAMP_RE.fullmatch(actual["sampled_at_utc"]):
                _reject_schema()
            if actual["rom_binding"]["state"] != "collected":
                _reject_schema()
        else:
            if actual["evidence"]["path"] is not None \
                    or actual["evidence"]["sha256"] is not None:
                _reject_schema()
            if actual["sampled_at_utc"] is not None:
                _reject_schema()
        if actual["status"] == "pass" and actual["evidence"]["state"] != "collected":
            _reject_schema()
        if not isinstance(actual["checks"], list) or actual["checks"] != list(expected.checks):
            _reject_schema()
        if mode == "dry-run":
            if actual["status"] != "not-tested" or actual["reason_code"] != DRY_RUN_REASON:
                _reject_schema()
    if report["summary"] != _summary(stages):
        _reject_schema()
    for counter in device.SAMPLE_COUNTERS:
        if type(report[counter]) is not int or report[counter] not in (0, 1):
            _reject_schema()
    required = ("create", "run_returned", "stop_cycles")
    status_by_id = {stage["id"]: stage["status"] for stage in stages}
    lifecycle = all(status_by_id[stage] == "pass" for stage in required)
    if report["real_avf_samples"] == 1 and not lifecycle:
        _reject_schema()
    if report["real_avf_samples"] != (
            report["protected_microdroid_samples"] + report["custom_linux_samples"]):
        _reject_schema()
    if report["protected_microdroid_samples"] == 1 and report["qa_mode"] != "protected-microdroid":
        _reject_schema()
    if report["custom_linux_samples"] == 1 and report["qa_mode"] != "custom-linux":
        _reject_schema()
    if mode == "dry-run":
        if report["device_independent"] is not True or report["real_avf_samples"] != 0:
            _reject_schema()
        if any(report[counter] for counter in device.SAMPLE_COUNTERS):
            _reject_schema()
        if report["device_mode"] != {"implemented": False, "adb_invoked": False,
                                     "serial_recorded": False}:
            _reject_schema()
        if report["commands"] or report["device_facts"] is not None \
                or report["probe"] is not None:
            _reject_schema()
        if report["verdict"] != "NOT_TESTED":
            _reject_schema()
    else:
        if report["device_independent"] is not False:
            _reject_schema()
        if not _valid_alias(alias):
            _reject_schema()
        if report["device_mode"]["implemented"] is not True:
            _reject_schema()
        if report["probe"] is not None and not report["device_mode"]["adb_invoked"]:
            _reject_schema()
        if report["probe"] is None or report["device_facts"] is None:
            if report["summary"]["pass"] or any(
                    report[counter] for counter in device.SAMPLE_COUNTERS):
                _reject_schema()
        counts = report["summary"]
        expected = ("FAILED" if counts["fail"] else
                    "BLOCKED" if counts["blocked"] else
                    "PASS" if counts["pass"] else "NOT_TESTED")
        if report["verdict"] != expected:
            _reject_schema()
    return report


def render_markdown(report):
    """Human-readable summary; it never upgrades not-tested into a success claim."""
    lines = [
        "# AVF 设备验收报告（" + report["mode"] + "）",
        "",
        "- 模式：" + report["mode"],
        "- QA 模式：" + report["qa_mode"],
        "- 设备无关：" + ("是" if report["device_independent"] else "否"),
        "- 生成时间（UTC）：" + report["generated_at_utc"],
        "- 设备别名：" + (report["device_alias"] or "未记录"),
        "- 平台资格样本：" + str(report["platform_qualification_samples"]),
        "- protected Microdroid 样本：" + str(report["protected_microdroid_samples"]),
        "- custom Linux 样本：" + str(report["custom_linux_samples"]),
        "- 真实 AVF 样本：" + str(report["real_avf_samples"]),
        "- 判定：" + report["verdict"],
        "",
    ]
    if report["mode"] == "dry-run":
        lines.append("本报告由 --dry-run 生成：未连接设备、未调用 adb，所有阶段均为 not-tested，不代表真机通过。")
    else:
        lines.append("本报告由设备模式生成：已执行 adb；QA 探针仅在设备就绪后运行。"
                     "未执行的阶段保持 not-tested 或 blocked，run 返回不等于 guest ready。")
    lines += ["", "| # | 阶段 | actor | 状态 | 原因 | 证据 |", "| --- | --- | --- | --- | --- | --- |"]
    for index, stage in enumerate(report["stages"], 1):
        lines.append("| " + str(index) + " | " + stage["title"] + " | " + stage["actor"]
                     + " | " + stage["status"] + " | " + stage["reason_code"]
                     + " | " + stage["evidence"]["state"] + " |")
    lines += ["", "## 汇总", ""]
    for key in ("pass", "fail", "blocked", "not-tested"):
        lines.append("- " + key + "：" + str(report["summary"][key]))
    if report["mode"] == "device" and report["device_facts"] is not None:
        facts = report["device_facts"]
        lines += [
            "", "## 设备事实（脱敏）", "",
            "- SDK：" + str(facts["sdk"]),
            "- ABI：" + ", ".join(facts["abis"]),
            "- AVF feature：" + str(facts["feature"]).lower(),
            "- capability bits：" + str(facts["capabilities"]["raw"]),
            "- protected-only：" + str(facts["protected_only"]).lower(),
            "- non-protected：" + str(facts["capabilities"]["non_protected"]).lower(),
            "- 权限：manage=" + facts["permissions"]["manage"]
            + ", custom=" + facts["permissions"]["custom"],
            "- setProtectedVm(false)：" + facts["protected_mode"],
            "- adb 命令数：" + str(len(report["commands"])),
        ]
    lines += [
        "", "## 边界", "",
        "- 本工具不 root、不解锁、不改 SELinux/hidden API 全局策略、不安装生产包。",
        "- 只对 QA 包执行安装和 grant；grant 必须由显式 --grant 触发。",
        "- 不记录 serial/IMEI/MAC/账号/token/用户根盘/屏幕内容/raw adb 输出。",
        "- 一个阶段的 pass 只代表该阶段被观察到；不是 AVF 产品支持声明。",
        "",
    ]
    return "\n".join(lines)


def _enrich_stages(report):
    for actual, expected in zip(report["stages"], STAGES):
        actual["title"] = expected.title
        actual["actor"] = expected.actor
        actual["checks"] = list(expected.checks)
    return report


def write_report(out_dir, report, evidence_payloads=None):
    """Writes evidence, JSON and Markdown under an explicit absolute root."""
    root = Path(str(out_dir))
    if not root.is_absolute():
        raise Rejected("ROOT_REJECTED")
    store = EvidenceStore(str(root), str(root))
    stamp = report["generated_at_utc"].replace("-", "").replace(":", "")
    if evidence_payloads:
        for stage_id, payload in evidence_payloads.items():
            relative = "device-evidence/" + stamp + "/" + stage_id + ".json"
            receipt = store.save_json(relative, payload)
            for stage in report["stages"]:
                if stage["id"] == stage_id:
                    stage["evidence"] = {
                        "state": "collected",
                        "path": receipt["path"],
                        "sha256": receipt["sha256"],
                    }
                    stage["rom_binding"] = {"state": "collected", "digest": None}
                    break
    base = "avf-device-validation-" + stamp
    json_receipt = store.save_json(base + ".json", report)
    markdown_receipt = store.save(base + ".md", render_markdown(report).encode("utf-8"))
    return {
        "report_json": str(store.output_path(json_receipt["path"])),
        "report_markdown": str(store.output_path(markdown_receipt["path"])),
        "report_schema_version": SCHEMA_VERSION,
    }


def redact(text):
    """Fixed-pattern redaction for any future diagnostic string."""
    if not isinstance(text, str):
        return text
    cleaned = re.sub(r"(?i)\bAuthorization\s*[:=]\s*Bearer\s+\S+",
                     "Authorization=<redacted>", text)
    cleaned = re.sub(
        r"(?i)\b(serial|imei|android[_-]?id|mac|token|password)\b\s*[:=]\s*[^\s,;]+",
        r"\1=<redacted>", cleaned)
    cleaned = re.sub(r"(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b", "<redacted>", cleaned)
    cleaned = re.sub(r"(?i)\b[0-9a-f]{64}\b", "<redacted>", cleaned)
    return cleaned


def _load_report(path):
    try:
        data = Path(path).read_bytes()
    except OSError:
        raise Rejected("INPUT_REJECTED") from None
    return parse_json(data)


def _parse_stages(value):
    if value is None or value == "minimal":
        return device.MINIMAL_STAGES
    if value == "all":
        return device.STAGE_IDS
    selected = []
    for item in value.split(","):
        item = item.strip()
        if not item or item not in device.STAGE_IDS:
            raise Rejected("STAGE_REJECTED")
        if item not in selected:
            selected.append(item)
    if not selected:
        raise Rejected("STAGE_REJECTED")
    return tuple(selected)


def main(argv=None):
    parser = SafeParser(prog="run_avf_device_validation", description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true",
                      help="run without a device; every stage is not-tested")
    mode.add_argument("--device", metavar="SERIAL",
                      help="run the QA device sequence through adb")
    mode.add_argument("--validate", metavar="REPORT",
                      help="validate an existing JSON report schema")
    parser.add_argument("--out", required=True, help="explicit absolute output root")
    parser.add_argument("--device-alias", help="public alias; never a serial")
    parser.add_argument("--adb", help="absolute path to adb")
    parser.add_argument("--qa-package", help="QA channel package (must contain .qa.)")
    parser.add_argument("--qa-test-package", help="QA instrumentation package")
    parser.add_argument("--apk", help="QA app APK to install")
    parser.add_argument("--test-apk", help="QA instrumentation APK to install")
    parser.add_argument("--grant", action="store_true",
                        help="explicitly grant both AVF permissions to the QA package")
    parser.add_argument("--stage", help="minimal, all, or comma-separated stage ids")
    parser.add_argument("--qa-mode", choices=device.QA_MODES, default="qualification",
                        help="qualification, protected-microdroid, or custom-linux")
    parser.add_argument("--microdroid-payload",
                        help="payload .so name inside the QA APK")
    parser.add_argument("--vsock-port", type=int, default=0,
                        help="optional vsock port; 0 leaves the handshake not requested")
    parser.add_argument("--stop-deadline-ms", type=int,
                        default=device.DEFAULT_STOP_DEADLINE_MS,
                        help="bounded stop deadline, 1000..60000 ms")
    parser.add_argument("--custom-kernel", help="QA-private custom kernel path")
    parser.add_argument("--custom-initrd", help="QA-private custom initrd path")
    parser.add_argument("--custom-root", help="QA-private custom root image path")
    parser.add_argument("--cycles", type=int, default=1, help="stop cycles, 1..20")
    parser.add_argument("--adb-timeout", type=float, default=device.ADB_TIMEOUT_SECONDS,
                        help="per-command adb timeout in seconds")
    parser.add_argument("--deadline-seconds", type=float, default=600.0,
                        help="whole-run deadline in seconds")
    args = parser.parse_args(argv)

    try:
        if not Path(args.out).is_absolute():
            raise Rejected("ROOT_REJECTED")
        if args.device_alias is not None and not ALIAS_RE.fullmatch(args.device_alias):
            raise Rejected("ALIAS_REJECTED")
        if args.validate is not None:
            validate_report(_load_report(args.validate))
            print(json.dumps({"status": "VALID", "schema_version": SCHEMA_VERSION},
                             sort_keys=True))
            return 0
        if args.dry_run:
            if args.grant or args.apk or args.test_apk or args.device \
                    or args.qa_mode != "qualification" or args.microdroid_payload \
                    or args.vsock_port or args.custom_kernel or args.custom_initrd \
                    or args.custom_root:
                raise Rejected("DEVICE_OPTION_WITH_DRY_RUN")
            report = build_report("dry-run", args.device_alias)
            validate_report(report)
            paths = write_report(args.out, report)
            print(json.dumps({
                "status": report["verdict"],
                "mode": report["mode"],
                "schema_version": SCHEMA_VERSION,
                "summary": report["summary"],
                **paths,
            }, sort_keys=True))
            return 0
        if args.device_alias is None or args.qa_package is None:
            raise Rejected("DEVICE_ARGUMENTS_REQUIRED")
        if not device.is_qa_package(args.qa_package):
            raise Rejected("QA_PACKAGE_REQUIRED")
        qa_test_package = args.qa_test_package or (args.qa_package + ".test")
        if not device.is_qa_package(qa_test_package):
            raise Rejected("QA_TEST_PACKAGE_REQUIRED")
        stages = _parse_stages(args.stage)
        report, evidence = device.run_device(
            adb_path=args.adb,
            serial=args.device,
            alias=args.device_alias,
            qa_package=args.qa_package,
            qa_test_package=qa_test_package,
            apk=args.apk,
            test_apk=args.test_apk,
            grant=args.grant,
            cycles=args.cycles,
            stages=stages,
            qa_mode=args.qa_mode,
            microdroid_payload=args.microdroid_payload,
            vsock_port=args.vsock_port,
            stop_deadline_ms=args.stop_deadline_ms,
            custom_kernel=args.custom_kernel,
            custom_initrd=args.custom_initrd,
            custom_root=args.custom_root,
            now=_now_utc(),
            timeout_seconds=args.adb_timeout,
            deadline_seconds=args.deadline_seconds,
        )
        _enrich_stages(report)
        paths = write_report(args.out, report, evidence)
        validate_report(report)
        print(json.dumps({
            "status": report["verdict"],
            "mode": report["mode"],
            "schema_version": SCHEMA_VERSION,
            "summary": report["summary"],
            "real_avf_samples": report["real_avf_samples"],
            "device_mode": report["device_mode"],
            **paths,
        }, sort_keys=True))
        return 0 if report["verdict"] == "PASS" else 2
    except (Rejected, OSError) as error:
        print(json.dumps({"error": str(error) if isinstance(error, Rejected) else "IO_ERROR"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
