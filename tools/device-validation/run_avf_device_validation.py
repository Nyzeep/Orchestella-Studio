#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""One-command AVF device-validation harness.

The harness defines the twelve stages from the device-gate checklist in
``docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md`` §3.3 and
emits one versioned JSON report plus a Markdown summary.

This ticket is zero-device only:

- ``--dry-run`` never contacts a device and marks every stage ``not-tested``.
- ``--device <serial>`` is a reserved argument; device execution is not
  implemented, so every stage is ``blocked`` and adb is never invoked.

The harness never installs, grants, roots, unlocks, runs a VM or records raw
device output. Serial/IMEI/MAC/token/screen content is never written.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True

import avf_preflight
from avf_evidence_io import EvidenceStore, Rejected, SafeParser, parse_json

SCHEMA_VERSION = 1
KIND = "avf_device_validation"
ALLOWED_STATUSES = ("pass", "fail", "blocked", "not-tested")
REPORT_KEYS = {
    "schema_version",
    "kind",
    "mode",
    "device_independent",
    "generated_at_utc",
    "device_alias",
    "stages",
    "summary",
    "redaction",
    "real_avf_samples",
    "verdict",
    "device_mode",
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
DRY_RUN_REASON = "DRY_RUN_NO_DEVICE"
DEVICE_MODE_REASON = "DEVICE_MODE_NOT_IMPLEMENTED"
ALIAS_RE = re.compile(r"[a-z][a-z0-9-]{2,39}")
TIMESTAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z")

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


# Exactly the twelve ordered steps from research §3.3. `checks` are planned
# observations, not results; no entry is evidence that a step ran.
STAGES = (
    Stage(
        "platform_declaration",
        "feature / ABI / SDK 与 hypervisor 声明",
        "shell",
        _PREFLIGHT_DECLARATION_CHECKS,
    ),
    Stage(
        "capabilities",
        "getCapabilities bits（App 与 shell/UserService 分别记录）",
        "app+shell",
        (
            "App actor: VirtualMachineManager.getCapabilities()",
            "shell/UserService actor: same call, separate row",
            "record raw bit mask and protected/non-protected decode",
        ),
    ),
    Stage(
        "permissions",
        "MANAGE_VIRTUAL_MACHINE / USE_CUSTOM_VIRTUAL_MACHINE",
        "app",
        (
            "checkSelfPermission for both permissions",
            "record granted/denied/unknown; never grant from this tool",
        ),
    ),
    Stage(
        "protected_mode",
        "setProtectedVm(false) 可构造",
        "app",
        (
            "construct VirtualMachineConfig with setProtectedVm(false)",
            "record UnsupportedOperationException as a typed refusal",
        ),
    ),
    Stage(
        "create",
        "App 私有 kernel/initrd/root raw create",
        "app",
        (
            "kernel and initrd exist in app-private storage",
            "root raw volume exists in app-private storage",
            "create returns a VM handle; no user disk is touched",
        ),
    ),
    Stage(
        "run_returned",
        "run 返回（不等于 guest ready）",
        "app",
        (
            "run() returns without throwing",
            "record that return is not proof of guest readiness",
        ),
    ),
    Stage(
        "vsock_handshake",
        "vsock 9100/9101 Bridge/终端握手",
        "app",
        (
            "connect to the control and terminal vsock ports",
            "complete the authenticated Bridge/terminal handshake",
            "record bytes/channel identity, never terminal text",
        ),
    ),
    Stage(
        "stop_cycles",
        "正常 stop 与 20 次启停收敛",
        "app",
        (
            "request a normal stop and confirm STOPPED",
            "repeat create/run/stop twenty times",
            "record failures and orphan observations",
        ),
    ),
    Stage(
        "network",
        "网络：无网络 / 静态 / DHCP / Android 17 GpuConfig workaround",
        "app",
        (
            "boot once without useNetwork",
            "boot with the headless 2D GPU workaround",
            "record guest eth0, DHCP/static address, DNS and apt reachability",
            "classify status 35 as NETWORK_BACKEND_UNAVAILABLE",
        ),
    ),
    Stage(
        "share_display",
        "9p 共享与 RFB 显示实际连通",
        "app",
        (
            "guest display-relay.py --transport vsock",
            "read-only 9p share listing and read",
            "record frame/input evidence without screen content",
        ),
    ),
    Stage(
        "app_death",
        "App 进程死亡/强制停止后 VM 收敛",
        "shell",
        (
            "force-stop the app process",
            "observe the VM state after process death",
            "record whether an orphan remains",
        ),
    ),
    Stage(
        "qemu_comparison",
        "同资源 QEMU 对照入口（仅定义，不执行）",
        "app",
        (
            "same 1 vCPU / RAM / rootfs",
            "record AVF and QEMU outcomes separately",
            "no performance claim from this harness",
        ),
    ),
)


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
    """Builds a report for a mode; device execution is deliberately absent."""
    if mode not in ("dry-run", "device"):
        raise Rejected("MODE_REJECTED")
    if device_alias is not None and not ALIAS_RE.fullmatch(device_alias):
        raise Rejected("ALIAS_REJECTED")
    if mode == "dry-run":
        status, reason = "not-tested", DRY_RUN_REASON
        verdict = "NOT_TESTED"
        implemented, adb_invoked = False, False
    else:
        status, reason = "blocked", DEVICE_MODE_REASON
        verdict = "BLOCKED_DEVICE_MODE_NOT_IMPLEMENTED"
        implemented, adb_invoked = False, False
    stages = [_stage(stage, status, reason) for stage in STAGES]
    return {
        "schema_version": SCHEMA_VERSION,
        "kind": KIND,
        "mode": mode,
        "device_independent": True,
        "generated_at_utc": _now_utc(now),
        "device_alias": device_alias,
        "stages": stages,
        "summary": _summary(stages),
        "redaction": {key: False for key in sorted(REDACTION_KEYS)},
        "real_avf_samples": 0,
        "verdict": verdict,
        "device_mode": {
            "implemented": implemented,
            "adb_invoked": adb_invoked,
            "serial_recorded": False,
        },
    }


def _reject_schema():
    raise Rejected("REPORT_SCHEMA_REJECTED")


def _check_exact_keys(value, expected, label):
    if not isinstance(value, dict) or set(value) != expected:
        _reject_schema()


def validate_report(report):
    """Strict schema check; it also refuses fabricated dry-run passes."""
    _check_exact_keys(report, REPORT_KEYS, "report")
    if report["schema_version"] != SCHEMA_VERSION or report["kind"] != KIND:
        _reject_schema()
    mode = report["mode"]
    if mode not in ("dry-run", "device"):
        _reject_schema()
    if report["device_independent"] is not True or report["real_avf_samples"] != 0:
        _reject_schema()
    if not isinstance(report["generated_at_utc"], str) or not TIMESTAMP_RE.fullmatch(
            report["generated_at_utc"]):
        _reject_schema()
    alias = report["device_alias"]
    if alias is not None and (not isinstance(alias, str) or not ALIAS_RE.fullmatch(alias)):
        _reject_schema()
    _check_exact_keys(report["redaction"], REDACTION_KEYS, "redaction")
    if any(report["redaction"].values()):
        _reject_schema()
    _check_exact_keys(report["device_mode"], DEVICE_MODE_KEYS, "device_mode")
    if report["device_mode"]["implemented"] is not False:
        _reject_schema()
    if report["device_mode"]["adb_invoked"] is not False:
        _reject_schema()
    if report["device_mode"]["serial_recorded"] is not False:
        _reject_schema()
    stages = report["stages"]
    if not isinstance(stages, list) or len(stages) != len(STAGES):
        _reject_schema()
    for index, (actual, expected) in enumerate(zip(stages, STAGES)):
        _check_exact_keys(actual, STAGE_KEYS, "stage")
        if actual["id"] != expected.stage_id or actual["title"] != expected.title:
            _reject_schema()
        if actual["actor"] != expected.actor:
            _reject_schema()
        if actual["status"] not in ALLOWED_STATUSES:
            _reject_schema()
        _check_exact_keys(actual["evidence"], EVIDENCE_KEYS, "evidence")
        _check_exact_keys(actual["rom_binding"], ROM_BINDING_KEYS, "rom_binding")
        if actual["evidence"]["state"] != "not-collected":
            _reject_schema()
        if actual["rom_binding"]["state"] != "not-collected":
            _reject_schema()
        if actual["evidence"]["path"] is not None or actual["evidence"]["sha256"] is not None:
            _reject_schema()
        if actual["rom_binding"]["digest"] is not None:
            _reject_schema()
        if actual["sampled_at_utc"] is not None:
            _reject_schema()
        if not isinstance(actual["checks"], list) or actual["checks"] != list(expected.checks):
            _reject_schema()
        if mode == "dry-run":
            if actual["status"] != "not-tested" or actual["reason_code"] != DRY_RUN_REASON:
                _reject_schema()
        else:
            if actual["status"] != "blocked" or actual["reason_code"] != DEVICE_MODE_REASON:
                _reject_schema()
    if report["summary"] != _summary(stages):
        _reject_schema()
    expected_verdict = "NOT_TESTED" if mode == "dry-run" else "BLOCKED_DEVICE_MODE_NOT_IMPLEMENTED"
    if report["verdict"] != expected_verdict:
        _reject_schema()
    return report


def render_markdown(report):
    """Human-readable summary; it never upgrades not-tested into a success claim."""
    lines = [
        "# AVF 设备验收报告（" + report["mode"] + "）",
        "",
        "- 模式：" + report["mode"],
        "- 设备无关：是",
        "- 生成时间（UTC）：" + report["generated_at_utc"],
        "- 设备别名：" + (report["device_alias"] or "未记录"),
        "- 真实 AVF 样本：0",
        "- 判定：" + report["verdict"],
        "",
    ]
    if report["mode"] == "dry-run":
        lines.append("本报告由 `--dry-run` 生成：未连接设备、未调用 adb，所有阶段均为 `not-tested`，不代表真机通过。")
    else:
        lines.append("设备模式尚未实现：未调用 adb，所有阶段均为 `blocked`；本报告不包含任何真机结果。")
    lines += [
        "",
        "| # | 阶段 | actor | 状态 | 原因 | 证据 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for index, stage in enumerate(report["stages"], 1):
        lines.append("| " + str(index) + " | " + stage["title"] + " | " + stage["actor"]
                     + " | " + stage["status"] + " | " + stage["reason_code"]
                     + " | " + stage["evidence"]["state"] + " |")
    lines += [
        "",
        "## 汇总",
        "",
        "- pass：" + str(report["summary"]["pass"]),
        "- fail：" + str(report["summary"]["fail"]),
        "- blocked：" + str(report["summary"]["blocked"]),
        "- not-tested：" + str(report["summary"]["not-tested"]),
        "",
        "## 边界",
        "",
        "- 本工具不安装 APK、不 grant、不 root、不解锁、不启动真实 VM。",
        "- 不记录 serial/IMEI/MAC/账号/token/用户根盘/屏幕内容。",
        "- 真机 create/run/vsock/stop/network/9p/display/audio/clipboard/App death 仍未验证。",
        "",
    ]
    return "\n".join(lines)


def write_report(out_dir, report):
    """Writes JSON + Markdown under an explicit absolute root, never overwriting."""
    root = Path(str(out_dir))
    if not root.is_absolute():
        raise Rejected("ROOT_REJECTED")
    store = EvidenceStore(str(root), str(root))
    stamp = report["generated_at_utc"].replace("-", "").replace(":", "")
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


def main(argv=None):
    parser = SafeParser(prog="run_avf_device_validation", description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true",
                      help="run without a device; every stage is not-tested")
    mode.add_argument("--device", metavar="SERIAL",
                      help="reserved device mode; this build reports blocked without adb")
    mode.add_argument("--validate", metavar="REPORT",
                      help="validate an existing JSON report schema")
    parser.add_argument("--out", required=True, help="explicit absolute output root")
    parser.add_argument("--device-alias", help="public alias; never a serial")
    args = parser.parse_args(argv)

    try:
        if not Path(args.out).is_absolute():
            raise Rejected("ROOT_REJECTED")
        if args.device_alias is not None and not ALIAS_RE.fullmatch(args.device_alias):
            raise Rejected("ALIAS_REJECTED")
        if args.validate is not None:
            validate_report(_load_report(args.validate))
            print(json.dumps({"status": "VALID", "schema_version": SCHEMA_VERSION}, sort_keys=True))
            return 0
        report = build_report("dry-run" if args.dry_run else "device", args.device_alias)
        validate_report(report)
        paths = write_report(args.out, report)
        print(json.dumps({
            "status": report["verdict"],
            "mode": report["mode"],
            "schema_version": SCHEMA_VERSION,
            "summary": report["summary"],
            **paths,
        }, sort_keys=True))
        return 0 if args.dry_run else 2
    except (Rejected, OSError) as error:
        print(json.dumps({"error": str(error) if isinstance(error, Rejected) else "IO_ERROR"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
