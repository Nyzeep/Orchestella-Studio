#!/usr/bin/env python3
"""UML/QEMU/proot boot-timeline parser (U0).

Protocol: ``tools/research/uml-boot-bench/README.md``.

The parser is deliberately conservative: when a log does not contain a
field, the metric is reported as unavailable (``unknown`` in text mode,
``null`` plus a reason in JSON), never estimated from another field.
Standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence

PROTOCOL = "uml-boot-bench/1"
FIXTURES = Path(__file__).resolve().parent / "fixtures"

# Duration metrics. T0 is an anchor (absolute host monotonic value), not a
# duration, and is therefore never included in the median summary.
DURATION_METRICS: tuple[str, ...] = (
    "t1_kernel_init_ms",
    "t2_rootfs_mounted_ms",
    "t3_first_command_ms",
    "t4_service_ready_ms",
    "t5_warm_resume_ms",
    "p0_proot_exec_ms",
    "q0_qemu_boot_ms",
    "duration_ms",
    "t3_from_spawn_ms",
)

LANE_METRICS: dict[str, tuple[str, ...]] = {
    "uml": (
        "t1_kernel_init_ms",
        "t2_rootfs_mounted_ms",
        "t3_first_command_ms",
        "t4_service_ready_ms",
        "t5_warm_resume_ms",
        "duration_ms",
        "t3_from_spawn_ms",
    ),
    "qemu": (
        "t1_kernel_init_ms",
        "t2_rootfs_mounted_ms",
        "t3_first_command_ms",
        "t4_service_ready_ms",
        "q0_qemu_boot_ms",
        "duration_ms",
        "t3_from_spawn_ms",
    ),
    "proot": (
        "p0_proot_exec_ms",
        "duration_ms",
    ),
}

LANE_NOT_APPLICABLE: dict[str, dict[str, str]] = {
    "uml": {
        "p0_proot_exec_ms": "not applicable: P0 is a proot lane metric",
        "q0_qemu_boot_ms": "not applicable: Q0 is a QEMU lane metric",
    },
    "qemu": {
        "p0_proot_exec_ms": "not applicable: P0 is a proot lane metric",
        "t5_warm_resume_ms": "not applicable: QEMU lane has no snapshot/warm-resume protocol",
    },
    "proot": {
        "t1_kernel_init_ms": "not applicable: proot has no guest kernel stage",
        "t2_rootfs_mounted_ms": "not applicable: proot does not mount a guest rootfs",
        "t3_first_command_ms": "not applicable: proot ready is measured as P0, not T3",
        "t4_service_ready_ms": "not applicable: proot has no systemd/service stage",
        "t5_warm_resume_ms": "not applicable: proot has no snapshot/warm-resume protocol",
        "q0_qemu_boot_ms": "not applicable: Q0 is a QEMU lane metric",
        "t3_from_spawn_ms": "not applicable: proot exec is measured as P0",
    },
}

MISSING_HINTS: dict[str, str] = {
    "t1_kernel_init_ms": (
        "no kernel timestamp on an init-start marker; enable printk.time=1 "
        "(or initcall_debug) and keep the 'Run /... as init process' line"
    ),
    "t2_rootfs_mounted_ms": (
        "no guest /proc/uptime marker after rootfs mount; emit "
        "'UMARM_BOOT_OK guest_uptime=<seconds>' after mounting /proc,/sys,/dev"
    ),
    "t3_first_command_ms": (
        "no 'BENCH boot_uptime_ms=' or 'UMARM_WORK_START ... uptime=' marker; "
        "emit one after the first command"
    ),
    "t4_service_ready_ms": (
        "no 'Startup finished in ... = <total>' total and no multi-user.target "
        "marker with a kernel timestamp; run systemd-analyze in the guest or "
        "emit 'BOOT_TIMELINE T4 uptime_s=<seconds>'"
    ),
    "t5_warm_resume_ms": (
        "T5 requires a host resume->READY monotonic pair; no such pair was found"
    ),
    "p0_proot_exec_ms": (
        "no p0_ms= or host t0_monotonic_ms/ready_monotonic_ms pair with "
        "ready_point=P0"
    ),
    "q0_qemu_boot_ms": (
        "Q0 requires a host QEMU spawn->T4 monotonic pair; guest serial logs "
        "alone cannot yield Q0"
    ),
    "duration_ms": "no duration_ms= in the exit file or console exit line",
    "t3_from_spawn_ms": (
        "requires host t0_monotonic_ms and a host READY timestamp on the same "
        "CLOCK_MONOTONIC with ready_point=T3"
    ),
}


@dataclass
class RunResult:
    label: str
    lane: str
    metrics: dict[str, float | None]
    anchors: dict[str, float | None]
    sources: dict[str, str]
    missing: dict[str, str]
    warnings: list[str] = field(default_factory=list)
    fixture: dict[str, Any] = field(default_factory=dict)
    exit: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "lane": self.lane,
            "fixture": self.fixture,
            "anchors": self.anchors,
            "metrics": self.metrics,
            "sources": self.sources,
            "missing": self.missing,
            "warnings": self.warnings,
            "exit": self.exit,
        }


# --------------------------------------------------------------------------
# Format regexes
# --------------------------------------------------------------------------

# Kernel printk / dmesg timestamp. Accepts an optional syslog priority prefix
# (``<6>[    0.000000]``) used by some dmesg captures.
_KERNEL_LINE_RE = re.compile(r"^(?:<\d+>)?\[\s*(\d+(?:\.\d+)?)\]\s?(.*)$")
_BOOT_TIMELINE_RE = re.compile(r"^BOOT_TIMELINE\s+(T[1-5])\s+([a-z_]+)=(\S+)\s*$")
_BENCH_RE = re.compile(r"^BENCH\s+boot_uptime_ms=(\S+)\s*$")
_WORK_START_RE = re.compile(r"^UMARM_WORK_START\b.*?\buptime=(\S+)")
_BOOTPROBE_RE = re.compile(r"^BOOTPROBE\b.*?\buptime=(\S+)")
_BOOT_OK_RE = re.compile(r"^UMARM_BOOT_OK(?:\s+guest_uptime=(\S+))?")
_SHELL_READY_RE = re.compile(r"^UMARM_SHELL_READY\b")
# U0.1 cross-session aliases (U1 fast shell / U2 zero-install). Lower
# priority than every canonical marker above; see _scan_legacy.
_U1_READY_RE = re.compile(r"^U1_READY\b.*?\buptime=(\S+)")
_T2_MOUNTS_READY_RE = re.compile(r"^T2_MOUNTS_READY\b.*?\buptime=(\S+)")
_T3_READY_RE = re.compile(r"^T3_READY\b.*?\buptime=(\S+)")
_T3_READY_LINE_RE = re.compile(r"^T3_READY\b")
_INIT_ZERO_INSTALL_RE = re.compile(r"^INIT_ZERO_INSTALL\b")
_BARE_READY_RE = re.compile(r"^READY$")
_KERNEL_EXITED_RE = re.compile(
    r"\bkernel exited code=(-?\d+) reason=(\S+) duration_ms=(\S+)"
)
_INIT_MARKER_RE = re.compile(r"\bRun \S+ as init process\b")
_MOUNT_MARKERS = (
    re.compile(r"\bVFS: Mounted root\b"),
    re.compile(r"\bEXT4-fs \([^)]*\): mounted filesystem\b"),
    re.compile(r"\bEXT4-fs \([^)]*\): recovery complete\b"),
)
_T4_MARKER_RE = re.compile(r"\bReached target (?:Multi-User System|multi-user\.target)\b")

_STARTUP_RE = re.compile(r"Startup finished in\s+(.+?)\s*$")
_COMPONENT_RE = re.compile(
    r"(?:(?P<min>\d+)\s*min\s*)?"
    r"(?P<val>\d+(?:\.\d+)?)\s*(?P<unit>ms|us|s)\s*"
    r"\((?P<name>firmware|loader|kernel|initrd|userspace)\)"
)
_TOTAL_RE = re.compile(
    r"=\s*(?:(?P<min>\d+)\s*min\s*)?"
    r"(?P<val>\d+(?:\.\d+)?)\s*(?P<unit>ms|us|s)\s*$"
)
_TARGET_RE = re.compile(
    r"(?:graphical|multi-user)\.target reached after\s+"
    r"(?:(?P<min>\d+)\s*min\s*)?"
    r"(?P<val>\d+(?:\.\d+)?)\s*(?P<unit>ms|us|s)"
)

_EXIT_KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
_PAGE_RE = re.compile(r"\bpage=(\d+)")
_PAGESIZE_LINE_RE = re.compile(r"^---\s*pagesize\s*---\s*(\d+)\s*$")
_NPROC_RE = re.compile(r"\bnproc=(\d+)")
_NCPUS_RE = re.compile(r"\bncpus=(\d+)")
_MEM_RE = re.compile(r"\bmem=(\d+)M\b")
_LINUX_VERSION_RE = re.compile(r"\bLinux version (\S+)")
_UNAME_RE = re.compile(r"^Linux \(none\) (\S+)")

_TIMING_NUMERIC_KEYS = {
    "t0_monotonic_ms",
    "ready_monotonic_ms",
    "p0_ms",
    "q0_ms",
    "t5_ms",
    "t3_from_spawn_ms",
    "duration_ms",
}
_TIMING_INT_KEYS = {"page_size", "ncpus", "mem_mb", "rc"}
_TIMING_STRING_KEYS = {"kernel_version", "rootfs_sha256", "defconfig", "host_load"}
_TIMING_FIXTURE_KEYS = _TIMING_INT_KEYS | _TIMING_STRING_KEYS
_TIMING_DIRECT_METRICS = (
    ("p0_proot_exec_ms", "p0_ms"),
    ("q0_qemu_boot_ms", "q0_ms"),
    ("t5_warm_resume_ms", "t5_ms"),
    ("t3_from_spawn_ms", "t3_from_spawn_ms"),
)
_DEFAULT_READY_POINT = {"uml": "T3", "qemu": "Q0", "proot": "P0"}


def _normalize(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _parse_number(raw: str) -> float | None:
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return None
    if value != value or value in (float("inf"), float("-inf")):
        return None
    return value


def _parse_nonnegative(raw: str) -> float | None:
    value = _parse_number(raw)
    if value is None or value < 0:
        return None
    return value


def _round_ms(value: float) -> float:
    return round(float(value), 3)


def _unit_to_ms(value: float, unit: str, minutes: str | None = None) -> float:
    minutes_ms = float(minutes) * 60000.0 if minutes else 0.0
    if unit == "s":
        return minutes_ms + value * 1000.0
    if unit == "ms":
        return minutes_ms + value
    if unit == "us":
        return minutes_ms + value / 1000.0
    if unit == "min":
        return minutes_ms + value * 60000.0
    raise ValueError(f"unsupported duration unit: {unit}")


def _iter_lines(text: str) -> Iterable[tuple[float | None, str]]:
    for raw in _normalize(text).splitlines():
        line = raw.rstrip()
        match = _KERNEL_LINE_RE.match(line)
        if match:
            yield float(match.group(1)), match.group(2).strip()
        else:
            yield None, line.strip()


def _dedupe(items: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item and item not in seen:
            seen.add(item)
            result.append(item)
    return result


# --------------------------------------------------------------------------
# systemd-analyze
# --------------------------------------------------------------------------


def parse_systemd_analyze(text: str) -> dict[str, Any]:
    result: dict[str, Any] = {
        "total_ms": None,
        "components": {},
        "target_userspace_ms": None,
        "warnings": [],
    }
    for raw in _normalize(text).splitlines():
        line = raw.strip()
        if not line:
            continue
        startup = _STARTUP_RE.search(line)
        if startup:
            total = _TOTAL_RE.search(line)
            if total:
                value = _parse_number(total.group("val"))
                if value is not None and result["total_ms"] is None:
                    result["total_ms"] = _round_ms(
                        _unit_to_ms(value, total.group("unit"), total.group("min"))
                    )
            else:
                result["warnings"].append(
                    "systemd-analyze: 'Startup finished in' line has no total; "
                    "T4 is unknown (re-capture the full line)"
                )
            for component in _COMPONENT_RE.finditer(startup.group(1)):
                value = _parse_number(component.group("val"))
                if value is None:
                    continue
                name = component.group("name")
                ms = _unit_to_ms(value, component.group("unit"), component.group("min"))
                result["components"].setdefault(name, _round_ms(ms))
            continue
        target = _TARGET_RE.search(line)
        if target:
            value = _parse_number(target.group("val"))
            if value is not None and result["target_userspace_ms"] is None:
                result["target_userspace_ms"] = _round_ms(
                    _unit_to_ms(value, target.group("unit"), target.group("min"))
                )
    return result


# --------------------------------------------------------------------------
# Host timing log
# --------------------------------------------------------------------------


def _empty_timing(lane: str) -> dict[str, Any]:
    return {
        "lane": lane,
        "label": None,
        "t0_monotonic_ms": None,
        "ready_monotonic_ms": None,
        "ready_point": None,
        "p0_ms": None,
        "q0_ms": None,
        "t5_ms": None,
        "t3_from_spawn_ms": None,
        "duration_ms": None,
        "rc": None,
        "fixture": {},
        "warnings": [],
    }


def parse_timing(text: str, lane: str) -> dict[str, Any]:
    result = _empty_timing(lane)
    if not text.strip():
        return result
    saw_header = False
    for raw in _normalize(text).splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("BOOT_TIMING"):
            saw_header = True
            if not line.startswith("BOOT_TIMING v1"):
                result["warnings"].append(
                    "timing: unsupported BOOT_TIMING header; parsed key=value lines anyway"
                )
            continue
        match = _EXIT_KEY_RE.match(line)
        if not match:
            continue
        key, raw_value = match.group(1), match.group(2).strip()
        if key == "lane":
            if raw_value != lane:
                result["warnings"].append(
                    f"timing: lane={raw_value} does not match --lane {lane}"
                )
        elif key == "label":
            result["label"] = raw_value or None
        elif key == "ready_point":
            point = raw_value.upper()
            if point not in ("P0", "Q0", "T1", "T2", "T3", "T4", "T5"):
                result["warnings"].append(
                    f"timing: invalid ready_point={raw_value}"
                )
            else:
                result["ready_point"] = point
        elif key == "rc":
            try:
                result["rc"] = int(raw_value, 10)
            except ValueError:
                result["warnings"].append(f"timing: invalid rc={raw_value}")
        elif key in _TIMING_NUMERIC_KEYS:
            value = _parse_nonnegative(raw_value)
            if value is None:
                result["warnings"].append(
                    f"timing: invalid {key}={raw_value} "
                    "(expected non-negative milliseconds)"
                )
            else:
                result[key] = value
        elif key in _TIMING_FIXTURE_KEYS:
            if key in _TIMING_INT_KEYS:
                try:
                    result["fixture"][key] = int(raw_value, 10)
                except ValueError:
                    result["warnings"].append(f"timing: invalid {key}={raw_value}")
            else:
                result["fixture"][key] = raw_value

    if not saw_header:
        result["warnings"].append(
            "timing: missing 'BOOT_TIMING v1' header; parsed key=value lines anyway"
        )

    t0 = result["t0_monotonic_ms"]
    ready = result["ready_monotonic_ms"]
    if t0 is not None and ready is None:
        result["warnings"].append(
            "timing: t0_monotonic_ms without ready_monotonic_ms; delta unknown"
        )
    elif ready is not None and t0 is None:
        result["warnings"].append(
            "timing: ready_monotonic_ms without t0_monotonic_ms; delta unknown"
        )
    elif t0 is not None and ready is not None:
        if ready < t0:
            result["warnings"].append(
                "timing: ready_monotonic_ms is before t0_monotonic_ms; pair ignored"
            )
        else:
            point = result["ready_point"] or _DEFAULT_READY_POINT[lane]
            delta = _round_ms(ready - t0)
            if point == "P0":
                result["p0_ms"] = delta
            elif point == "Q0" or (lane == "qemu" and point == "T4"):
                result["q0_ms"] = delta
            elif point == "T3":
                result["t3_from_spawn_ms"] = delta
            elif point == "T5":
                result["t5_ms"] = delta
            else:
                result["warnings"].append(
                    f"timing: ready_point={point} has no protocol metric "
                    f"for lane {lane}"
                )
    return result


# --------------------------------------------------------------------------
# Console / dmesg / exit-file scanning
# --------------------------------------------------------------------------


def _scan_canonical(
    lines: Iterable[tuple[float | None, str]], warnings: list[str], source: str
) -> dict[str, tuple[float, str]]:
    points: dict[str, tuple[float, str]] = {}
    for _timestamp, message in lines:
        match = _BOOT_TIMELINE_RE.match(message)
        if not match:
            continue
        point, key, raw_value = match.groups()
        value = _parse_nonnegative(raw_value)
        if value is None:
            warnings.append(
                f"{source}: invalid BOOT_TIMELINE {point} {key}={raw_value}"
            )
            continue
        if key == "uptime_s":
            ms = value * 1000.0
        elif key in ("uptime_ms", "elapsed_ms"):
            ms = value
        else:
            warnings.append(f"{source}: unsupported BOOT_TIMELINE key {key}")
            continue
        if point == "T5" and key != "elapsed_ms":
            warnings.append(f"{source}: T5 must use elapsed_ms (host clock)")
            continue
        metric = {
            "T1": "t1_kernel_init_ms",
            "T2": "t2_rootfs_mounted_ms",
            "T3": "t3_first_command_ms",
            "T4": "t4_service_ready_ms",
            "T5": "t5_warm_resume_ms",
        }[point]
        points.setdefault(metric, (_round_ms(ms), f"{source}:boot_timeline"))
    return points


def _scan_legacy(
    lines: Iterable[tuple[float | None, str]], warnings: list[str], source: str
) -> tuple[dict[str, tuple[float, str]], dict[str, Any]]:
    found: dict[str, tuple[float, str]] = {}
    meta: dict[str, Any] = {
        "init_seen": False,
        "boot_ok_without_value": False,
        "shell_ready": False,
        "init_zero_install_seen": False,
        "t3_ready_seen": False,
        "bare_ready_seen": False,
        "exit": None,
    }
    boot_ok: tuple[float, str] | None = None
    work_start: tuple[float, str] | None = None
    bootprobe: tuple[float, str] | None = None
    bench: tuple[float, str] | None = None
    mount: tuple[float, str] | None = None
    t4_marker: tuple[float, str] | None = None
    u1_ready: tuple[float, str] | None = None
    t2_mounts_ready: tuple[float, str] | None = None
    t3_ready: tuple[float, str] | None = None

    for timestamp, message in lines:
        if _INIT_MARKER_RE.search(message):
            meta["init_seen"] = True
            if timestamp is not None:
                found.setdefault(
                    "t1_kernel_init_ms",
                    (_round_ms(timestamp * 1000.0), f"{source}:kernel_timestamp"),
                )

        boot_match = _BOOT_OK_RE.match(message)
        if boot_match:
            raw_value = boot_match.group(1)
            if raw_value is None:
                meta["boot_ok_without_value"] = True
            else:
                value = _parse_nonnegative(raw_value)
                if value is None:
                    warnings.append(
                        f"{source}: invalid UMARM_BOOT_OK guest_uptime={raw_value}"
                    )
                elif boot_ok is None:
                    boot_ok = (
                        _round_ms(value * 1000.0),
                        f"{source}:guest_uptime",
                    )

        bench_match = _BENCH_RE.match(message)
        if bench_match:
            raw_value = bench_match.group(1)
            value = _parse_nonnegative(raw_value)
            if value is None:
                warnings.append(
                    f"{source}: invalid BENCH boot_uptime_ms={raw_value}"
                )
            elif bench is None:
                bench = (_round_ms(value), f"{source}:boot_uptime_ms")

        work_match = _WORK_START_RE.match(message)
        if work_match:
            raw_value = work_match.group(1)
            value = _parse_nonnegative(raw_value)
            if value is None:
                warnings.append(
                    f"{source}: invalid UMARM_WORK_START uptime={raw_value}"
                )
            elif work_start is None:
                work_start = (
                    _round_ms(value * 1000.0),
                    f"{source}:work_start_uptime",
                )

        probe_match = _BOOTPROBE_RE.match(message)
        if probe_match:
            raw_value = probe_match.group(1)
            value = _parse_nonnegative(raw_value)
            if value is None:
                warnings.append(
                    f"{source}: invalid BOOTPROBE uptime={raw_value}"
                )
            elif bootprobe is None:
                bootprobe = (
                    _round_ms(value * 1000.0),
                    f"{source}:bootprobe_uptime",
                )

        if _SHELL_READY_RE.match(message):
            meta["shell_ready"] = True

        u1_match = _U1_READY_RE.match(message)
        if u1_match:
            raw_value = u1_match.group(1)
            value = _parse_nonnegative(raw_value)
            if value is None:
                warnings.append(
                    f"{source}: invalid U1_READY uptime={raw_value}"
                )
            elif u1_ready is None:
                u1_ready = (
                    _round_ms(value * 1000.0),
                    f"{source}:u1_ready_uptime",
                )

        t2m_match = _T2_MOUNTS_READY_RE.match(message)
        if t2m_match:
            raw_value = t2m_match.group(1)
            value = _parse_nonnegative(raw_value)
            if value is None:
                warnings.append(
                    f"{source}: invalid T2_MOUNTS_READY uptime={raw_value}"
                )
            elif t2_mounts_ready is None:
                t2_mounts_ready = (
                    _round_ms(value * 1000.0),
                    f"{source}:t2_mounts_ready_uptime",
                )

        if _T3_READY_LINE_RE.match(message):
            # The bare-READY gate only needs the line to exist; the uptime
            # value is validated separately below.
            meta["t3_ready_seen"] = True
        t3r_match = _T3_READY_RE.match(message)
        if t3r_match:
            raw_value = t3r_match.group(1)
            value = _parse_nonnegative(raw_value)
            if value is None:
                warnings.append(
                    f"{source}: invalid T3_READY uptime={raw_value}"
                )
            elif t3_ready is None:
                t3_ready = (
                    _round_ms(value * 1000.0),
                    f"{source}:t3_ready_uptime",
                )

        if _INIT_ZERO_INSTALL_RE.match(message):
            meta["init_zero_install_seen"] = True

        if _BARE_READY_RE.match(message):
            meta["bare_ready_seen"] = True

        if timestamp is not None and t4_marker is None and _T4_MARKER_RE.search(message):
            t4_marker = (
                _round_ms(timestamp * 1000.0),
                f"{source}:kernel_timestamp",
            )

        if timestamp is not None and mount is None:
            for marker in _MOUNT_MARKERS:
                if marker.search(message):
                    mount = (
                        _round_ms(timestamp * 1000.0),
                        f"{source}:kernel_timestamp",
                    )
                    break

        exit_match = _KERNEL_EXITED_RE.search(message)
        if exit_match and meta["exit"] is None:
            duration = _parse_nonnegative(exit_match.group(3))
            if duration is None:
                warnings.append(
                    f"{source}: invalid kernel exited duration_ms={exit_match.group(3)}"
                )
            meta["exit"] = {
                "code": int(exit_match.group(1)),
                "reason": exit_match.group(2),
                "signal": None,
                "duration_ms": _round_ms(duration) if duration is not None else None,
            }

    # A guest-uptime marker is a direct T2 measurement and beats the kernel
    # timestamp on the rootfs mount line. UMARM_BOOT_OK keeps precedence over
    # the U2 T2_MOUNTS_READY alias; both beat the kernel-timestamp fallback.
    if boot_ok is not None:
        found.setdefault("t2_rootfs_mounted_ms", boot_ok)
    elif t2_mounts_ready is not None:
        found.setdefault("t2_rootfs_mounted_ms", t2_mounts_ready)
    elif mount is not None:
        found.setdefault("t2_rootfs_mounted_ms", mount)
    # BENCH is the explicit first-command marker; WORK_START is the next
    # best; the 11-07 bootprobe work script's BOOTPROBE line is the last
    # in-guest fallback among the canonical markers. The U0.1 cross-session
    # aliases (U1_READY, then T3_READY) rank below all of them. A bare READY
    # line never contributes a value (it has no uptime); whether it was seen
    # inside a zero-install run only shapes the missing-field hint in
    # build_run, and it is never attributed to another lane.
    if bench is not None:
        found.setdefault("t3_first_command_ms", bench)
    elif work_start is not None:
        found.setdefault("t3_first_command_ms", work_start)
    elif bootprobe is not None:
        found.setdefault("t3_first_command_ms", bootprobe)
    elif u1_ready is not None:
        found.setdefault("t3_first_command_ms", u1_ready)
    elif t3_ready is not None:
        found.setdefault("t3_first_command_ms", t3_ready)
    if t4_marker is not None:
        found.setdefault("t4_service_ready_ms", t4_marker)
    return found, meta


def _scan_exit(text: str, warnings: list[str]) -> dict[str, Any]:
    data: dict[str, str] = {}
    for raw in _normalize(text).splitlines():
        match = _EXIT_KEY_RE.match(raw.strip())
        if match:
            data[match.group(1)] = match.group(2).strip()
    result: dict[str, Any] = {
        "code": None,
        "reason": None,
        "signal": None,
        "duration_ms": None,
    }
    if "exit_code" in data:
        try:
            result["code"] = int(data["exit_code"], 10)
        except ValueError:
            warnings.append(f"exit: invalid exit_code={data['exit_code']}")
    if "reason" in data:
        result["reason"] = data["reason"] or None
    if "signal_guess" in data:
        try:
            result["signal"] = int(data["signal_guess"], 10)
        except ValueError:
            warnings.append(f"exit: invalid signal_guess={data['signal_guess']}")
    if "duration_ms" in data:
        value = _parse_nonnegative(data["duration_ms"])
        if value is None:
            warnings.append(f"exit: invalid duration_ms={data['duration_ms']}")
        else:
            result["duration_ms"] = _round_ms(value)
    return result


def _extract_fixture(
    console_text: str, dmesg_text: str, timing: dict[str, Any]
) -> dict[str, Any]:
    fixture: dict[str, Any] = {}
    page_size: int | None = None
    nproc: int | None = None
    ncpus: int | None = None
    mem_mb: int | None = None
    kernel_version: str | None = None

    for text in (console_text, dmesg_text):
        for raw in _normalize(text).splitlines():
            line = raw.strip()
            if kernel_version is None:
                match = _LINUX_VERSION_RE.search(line)
                if match:
                    kernel_version = match.group(1)
                else:
                    match = _UNAME_RE.match(line)
                    if match:
                        kernel_version = match.group(1)
            if page_size is None:
                match = _PAGESIZE_LINE_RE.match(line) or _PAGE_RE.search(line)
                if match:
                    page_size = int(match.group(1))
            if nproc is None:
                match = _NPROC_RE.search(line)
                if match:
                    nproc = int(match.group(1))
            if ncpus is None:
                match = _NCPUS_RE.search(line)
                if match:
                    ncpus = int(match.group(1))
            if mem_mb is None:
                match = _MEM_RE.search(line)
                if match:
                    mem_mb = int(match.group(1))

    if kernel_version is not None:
        fixture["kernel_version"] = kernel_version
    if page_size is not None:
        fixture["page_size"] = page_size
    if nproc is not None:
        fixture["ncpus"] = nproc
    elif ncpus is not None:
        fixture["ncpus"] = ncpus
    if mem_mb is not None:
        fixture["mem_mb"] = mem_mb

    for key, value in timing.get("fixture", {}).items():
        fixture[key] = value
    return fixture


# --------------------------------------------------------------------------
# Run / report construction
# --------------------------------------------------------------------------


def build_run(
    lane: str,
    label: str | None = None,
    *,
    console_text: str = "",
    exit_text: str = "",
    dmesg_text: str = "",
    systemd_text: str = "",
    timing_text: str = "",
) -> RunResult:
    if lane not in LANE_METRICS:
        raise ValueError(f"unknown lane: {lane}")
    warnings: list[str] = []
    timing = parse_timing(timing_text, lane) if timing_text.strip() else _empty_timing(lane)
    warnings.extend(timing["warnings"])

    console_lines = list(_iter_lines(console_text))
    dmesg_lines = list(_iter_lines(dmesg_text))

    canonical = _scan_canonical(console_lines, warnings, "console")
    for metric, point in _scan_canonical(dmesg_lines, warnings, "dmesg").items():
        canonical.setdefault(metric, point)

    legacy, legacy_meta = _scan_legacy(console_lines, warnings, "console")
    dmesg_legacy, dmesg_meta = _scan_legacy(dmesg_lines, warnings, "dmesg")
    for metric, point in dmesg_legacy.items():
        legacy.setdefault(metric, point)

    meta = {
        "init_seen": legacy_meta["init_seen"] or dmesg_meta["init_seen"],
        "boot_ok_without_value": (
            legacy_meta["boot_ok_without_value"] or dmesg_meta["boot_ok_without_value"]
        ),
        "shell_ready": legacy_meta["shell_ready"] or dmesg_meta["shell_ready"],
        "init_zero_install_seen": (
            legacy_meta["init_zero_install_seen"] or dmesg_meta["init_zero_install_seen"]
        ),
        "t3_ready_seen": legacy_meta["t3_ready_seen"] or dmesg_meta["t3_ready_seen"],
        "bare_ready_seen": legacy_meta["bare_ready_seen"] or dmesg_meta["bare_ready_seen"],
        "exit": legacy_meta["exit"] or dmesg_meta["exit"],
    }

    values: dict[str, tuple[float, str]] = dict(legacy)
    for metric, point in canonical.items():
        values[metric] = point

    # systemd-analyze: an explicit --systemd-analyze file wins over text
    # embedded in the console; a parsed total wins over a target marker.
    explicit_systemd = parse_systemd_analyze(systemd_text) if systemd_text.strip() else None
    console_systemd = parse_systemd_analyze(console_text)
    if explicit_systemd is not None:
        # Do not warn that T4 is unknown when the console supplied a total.
        if explicit_systemd["total_ms"] is not None or console_systemd["total_ms"] is None:
            warnings.extend(explicit_systemd["warnings"])
    if console_systemd["total_ms"] is None:
        warnings.extend(console_systemd["warnings"])
    used_systemd = None
    if explicit_systemd is not None and explicit_systemd["total_ms"] is not None:
        used_systemd = explicit_systemd
        used_source = "systemd-analyze:startup_total"
    elif console_systemd["total_ms"] is not None:
        used_systemd = console_systemd
        used_source = "console:startup_total"
    if used_systemd is not None:
        values["t4_service_ready_ms"] = (used_systemd["total_ms"], used_source)

    # Host-anchored metrics from the timing log. Never overwrite a value that
    # was measured directly in the guest console.
    for metric, key in _TIMING_DIRECT_METRICS:
        if timing.get(key) is None:
            continue
        if metric not in LANE_METRICS[lane]:
            warnings.append(
                f"timing: {key} is not applicable to lane {lane}; ignored"
            )
            continue
        values.setdefault(metric, (timing[key], f"timing:{key}"))

    # Exit status: the exit file wins over the console mirror.
    exit_scan = _scan_exit(exit_text, warnings) if exit_text.strip() else {}
    console_exit = meta["exit"] or {}

    def pick_exit(key: str) -> Any:
        if exit_scan.get(key) is not None:
            return exit_scan[key]
        return console_exit.get(key)

    exit_info = {
        "code": pick_exit("code"),
        "reason": pick_exit("reason"),
        "signal": pick_exit("signal"),
    }

    duration: float | None = None
    duration_source = ""
    if exit_scan.get("duration_ms") is not None:
        duration = exit_scan["duration_ms"]
        duration_source = "exit:duration_ms"
    elif timing.get("duration_ms") is not None:
        duration = timing["duration_ms"]
        duration_source = "timing:duration_ms"
    elif console_exit.get("duration_ms") is not None:
        duration = console_exit["duration_ms"]
        duration_source = "console:kernel_exited"
    if duration is not None:
        values["duration_ms"] = (duration, duration_source)

    # A log may contain markers from another lane (for example a proot
    # capture that still prints BENCH). Keep the protocol strict: ignore
    # metrics that do not exist for this lane and say so.
    for metric in list(values):
        if metric not in LANE_METRICS[lane]:
            warnings.append(
                f"{lane}: {metric} is not applicable to this lane; ignored"
            )
            values.pop(metric)

    metrics: dict[str, float | None] = {}
    sources: dict[str, str] = {}
    for metric in DURATION_METRICS:
        point = values.get(metric)
        if point is None:
            metrics[metric] = None
            continue
        metrics[metric] = _round_ms(point[0])
        sources[metric] = point[1]

    missing: dict[str, str] = {}
    for metric in DURATION_METRICS:
        if metrics[metric] is not None:
            continue
        if metric in LANE_NOT_APPLICABLE[lane]:
            missing[metric] = LANE_NOT_APPLICABLE[lane][metric]
        elif metric == "t2_rootfs_mounted_ms" and meta["boot_ok_without_value"]:
            missing[metric] = (
                "UMARM_BOOT_OK marker has no guest_uptime value; emit "
                "'UMARM_BOOT_OK guest_uptime=<seconds>' after mounting /proc,/sys,/dev"
            )
        elif metric == "t3_first_command_ms" and meta["shell_ready"]:
            missing[metric] = (
                "UMARM_SHELL_READY has no guest uptime; emit "
                "'BENCH boot_uptime_ms=<ms>' or add uptime=<seconds>"
            )
        elif metric == "t3_first_command_ms" and meta["bare_ready_seen"] and (
            meta["init_zero_install_seen"] or meta["t3_ready_seen"]
        ):
            # Bare READY only counts inside a U1/U2 zero-install run and
            # never carries a value itself; point at the aliases that do.
            missing[metric] = (
                "zero-install run printed READY but no T3 alias carried a "
                "usable uptime; emit 'T3_READY uptime=<seconds>' or "
                "'U1_READY uptime=<seconds>'"
            )
        else:
            missing[metric] = MISSING_HINTS[metric]

    # Same-run consistency. A guest ready point cannot be older than the
    # host process that contains it; if it is, the inputs were likely mixed
    # from different runs. Warn, do not silently rewrite the values.
    duration_value = metrics["duration_ms"]
    if duration_value is not None:
        for metric in (
            "t1_kernel_init_ms",
            "t2_rootfs_mounted_ms",
            "t3_first_command_ms",
            "t4_service_ready_ms",
            "t3_from_spawn_ms",
        ):
            value = metrics[metric]
            if value is not None and value > duration_value + 0.001:
                warnings.append(
                    f"consistency: {metric}={value} ms exceeds "
                    f"duration_ms={duration_value} ms; confirm all inputs "
                    "belong to the same run"
                )

    resolved_label = label or timing.get("label") or "run-1"
    return RunResult(
        label=resolved_label,
        lane=lane,
        metrics=metrics,
        anchors={"t0_spawn_monotonic_ms": timing.get("t0_monotonic_ms")},
        sources=sources,
        missing=missing,
        warnings=_dedupe(warnings),
        fixture=_extract_fixture(console_text, dmesg_text, timing),
        exit=exit_info,
    )


def summarize_runs(runs: Sequence[RunResult]) -> dict[str, Any]:
    summary: dict[str, Any] = {"unit": "ms", "n": {}, "median_ms": {}}
    for metric in DURATION_METRICS:
        values = []
        for run in runs:
            value = run.metrics.get(metric)
            if isinstance(value, (int, float)) and value == value:
                values.append(float(value))
        summary["n"][metric] = len(values)
        summary["median_ms"][metric] = (
            _round_ms(statistics.median(values)) if values else None
        )
    return summary


def build_report(lane: str, runs: Sequence[RunResult]) -> dict[str, Any]:
    return {
        "protocol": PROTOCOL,
        "lane": lane,
        "unit": "ms",
        "notes": [
            "T0 is an absolute host CLOCK_MONOTONIC anchor, not a duration; "
            "it is not aggregated.",
            "T1-T4 are guest kernel-relative elapsed times; duration_ms is the "
            "host spawn-to-exit time. Never subtract across anchors.",
            "unknown/null means the log did not contain the field; it is not "
            "zero and was not estimated.",
            "Median uses statistics.median: middle value for odd n, mean of "
            "the two middle values for even n; unknown values are excluded.",
        ],
        "runs": [run.to_dict() for run in runs],
        "summary": summarize_runs(runs),
    }


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------

_SHORT_METRICS = {
    "t1_kernel_init_ms": "T1 kernel init",
    "t2_rootfs_mounted_ms": "T2 rootfs mounted",
    "t3_first_command_ms": "T3 first command",
    "t4_service_ready_ms": "T4 service ready",
    "t5_warm_resume_ms": "T5 warm resume",
    "p0_proot_exec_ms": "P0 proot exec",
    "q0_qemu_boot_ms": "Q0 QEMU boot",
    "duration_ms": "duration (spawn->exit)",
    "t3_from_spawn_ms": "T3 from spawn",
}


def _fmt_ms(value: float | None) -> str:
    if value is None:
        return "unknown"
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return text or "0"


def render_json(report: dict[str, Any]) -> str:
    return json.dumps(report, indent=2, ensure_ascii=False)


def render_text(report: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append(
        f"# Boot timeline — {report['lane']} lane (protocol {report['protocol']})"
    )
    lines.append("")
    lines.append(
        "All values are milliseconds. `unknown` means the log does not contain "
        "the field; it is not zero and was not estimated."
    )
    lines.append("")
    lines.append(
        "| run | lane | T1 | T2 | T3 | T4 | T5 | P0 | Q0 | duration | T3-from-spawn |"
    )
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for run in report["runs"]:
        metrics = run["metrics"]
        lines.append(
            "| {label} | {lane} | {t1} | {t2} | {t3} | {t4} | {t5} | {p0} | {q0} | {duration} | {t3s} |".format(
                label=run["label"],
                lane=run["lane"],
                t1=_fmt_ms(metrics["t1_kernel_init_ms"]),
                t2=_fmt_ms(metrics["t2_rootfs_mounted_ms"]),
                t3=_fmt_ms(metrics["t3_first_command_ms"]),
                t4=_fmt_ms(metrics["t4_service_ready_ms"]),
                t5=_fmt_ms(metrics["t5_warm_resume_ms"]),
                p0=_fmt_ms(metrics["p0_proot_exec_ms"]),
                q0=_fmt_ms(metrics["q0_qemu_boot_ms"]),
                duration=_fmt_ms(metrics["duration_ms"]),
                t3s=_fmt_ms(metrics["t3_from_spawn_ms"]),
            )
        )
    lines.append("")
    lines.append("## Summary (median)")
    lines.append("")
    lines.append("| metric | n | median (ms) |")
    lines.append("| --- | --- | --- |")
    summary = report["summary"]
    for metric in DURATION_METRICS:
        lines.append(
            f"| {_SHORT_METRICS[metric]} | {summary['n'][metric]} | "
            f"{_fmt_ms(summary['median_ms'][metric])} |"
        )
    lines.append("")
    lines.append("## T0 anchors (absolute host monotonic, not durations)")
    lines.append("")
    lines.append("| run | T0 spawn (ms) |")
    lines.append("| --- | --- |")
    for run in report["runs"]:
        lines.append(
            f"| {run['label']} | {_fmt_ms(run['anchors'].get('t0_spawn_monotonic_ms'))} |"
        )
    lines.append("")
    lines.append("## Fixtures")
    lines.append("")
    lines.append("| run | kernel | page_size | ncpus | mem_mb | rootfs_sha256 |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for run in report["runs"]:
        fixture = run.get("fixture", {})
        lines.append(
            "| {label} | {kernel} | {page} | {ncpus} | {mem} | {sha} |".format(
                label=run["label"],
                kernel=fixture.get("kernel_version", "unknown"),
                page=fixture.get("page_size", "unknown"),
                ncpus=fixture.get("ncpus", "unknown"),
                mem=fixture.get("mem_mb", "unknown"),
                sha=fixture.get("rootfs_sha256", "unknown"),
            )
        )

    missing_lines: list[str] = []
    for run in report["runs"]:
        for metric, reason in run["missing"].items():
            missing_lines.append(
                f"- `{run['label']}` {_SHORT_METRICS[metric]}: {reason}"
            )
    if missing_lines:
        lines.append("")
        lines.append("## Missing fields")
        lines.append("")
        lines.extend(missing_lines)

    warning_lines: list[str] = []
    for run in report["runs"]:
        for warning in run.get("warnings", []):
            warning_lines.append(f"- `{run['label']}`: {warning}")
    if warning_lines:
        lines.append("")
        lines.append("## Warnings")
        lines.append("")
        lines.extend(warning_lines)

    lines.append("")
    lines.append("## Protocol notes")
    lines.append("")
    for note in report.get("notes", []):
        lines.append(f"- {note}")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Self-test
# --------------------------------------------------------------------------


def _fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def self_test() -> int:
    checks = 0
    failures: list[str] = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        nonlocal checks
        checks += 1
        if not condition:
            failures.append(f"{name}{(': ' + detail) if detail else ''}")

    def approx(value: float | None, expected: float, tolerance: float = 1e-6) -> bool:
        return value is not None and abs(value - expected) <= tolerance

    # ---- UML normal -------------------------------------------------------
    uml = build_run(
        "uml",
        "uml-ok",
        console_text=_fixture("uml-console-ok.txt"),
        exit_text=_fixture("uml-exit-ok.txt"),
    )
    check("uml.t1", approx(uml.metrics["t1_kernel_init_ms"], 40.0),
          repr(uml.metrics["t1_kernel_init_ms"]))
    check("uml.t2", approx(uml.metrics["t2_rootfs_mounted_ms"], 120.0),
          repr(uml.metrics["t2_rootfs_mounted_ms"]))
    check("uml.t3", approx(uml.metrics["t3_first_command_ms"], 400.0),
          repr(uml.metrics["t3_first_command_ms"]))
    check("uml.duration", approx(uml.metrics["duration_ms"], 3182.0),
          repr(uml.metrics["duration_ms"]))
    check("uml.t3_from_spawn_unknown", uml.metrics["t3_from_spawn_ms"] is None)
    check("uml.duration_is_not_t3", uml.metrics["duration_ms"] != uml.metrics["t3_first_command_ms"])
    check("uml.exit_code", uml.exit.get("code") == 0, repr(uml.exit))
    check("uml.exit_reason", uml.exit.get("reason") == "exited", repr(uml.exit))
    check("uml.page_size", uml.fixture.get("page_size") == 16384, repr(uml.fixture))
    check("uml.ncpus", uml.fixture.get("ncpus") == 1, repr(uml.fixture))
    check("uml.mem_mb", uml.fixture.get("mem_mb") == 512, repr(uml.fixture))
    check("uml.kernel_version", str(uml.fixture.get("kernel_version", "")).startswith("7.2.0-um"),
          repr(uml.fixture))
    check("uml.source.t1", uml.sources.get("t1_kernel_init_ms") == "console:kernel_timestamp",
          repr(uml.sources))
    check("uml.source.t2", uml.sources.get("t2_rootfs_mounted_ms") == "console:guest_uptime",
          repr(uml.sources))
    check("uml.source.t3", uml.sources.get("t3_first_command_ms") == "console:boot_uptime_ms",
          repr(uml.sources))
    check("uml.source.duration", uml.sources.get("duration_ms") == "exit:duration_ms",
          repr(uml.sources))

    # ---- UML missing T3 + duration ----------------------------------------
    uml_missing = build_run(
        "uml",
        "uml-missing",
        console_text=_fixture("uml-console-missing-duration.txt"),
    )
    check("uml_missing.t1", approx(uml_missing.metrics["t1_kernel_init_ms"], 40.0))
    check("uml_missing.t2", approx(uml_missing.metrics["t2_rootfs_mounted_ms"], 120.0))
    check("uml_missing.t3_none", uml_missing.metrics["t3_first_command_ms"] is None)
    check("uml_missing.t3_reason", "boot_uptime_ms" in uml_missing.missing["t3_first_command_ms"],
          uml_missing.missing["t3_first_command_ms"])
    check("uml_missing.duration_none", uml_missing.metrics["duration_ms"] is None)
    check("uml_missing.duration_reason", "duration_ms" in uml_missing.missing["duration_ms"])
    check("uml_missing.text_unknown", "unknown" in render_text(build_report("uml", [uml_missing])))

    # ---- Sanitised real 2026-10-29 excerpt --------------------------------
    real = build_run(
        "uml",
        "real-20261029",
        console_text=_fixture("uml-console-real-20261029-excerpt.txt"),
        exit_text=_fixture("uml-exit-real-20261029-excerpt.txt"),
    )
    check("real.t3", approx(real.metrics["t3_first_command_ms"], 400.0),
          repr(real.metrics["t3_first_command_ms"]))
    check("real.t3_in_reported_band",
          220.0 <= (real.metrics["t3_first_command_ms"] or 0.0) <= 400.0,
          repr(real.metrics["t3_first_command_ms"]))
    check("real.duration", approx(real.metrics["duration_ms"], 3182.0),
          repr(real.metrics["duration_ms"]))
    check("real.t1_unknown", real.metrics["t1_kernel_init_ms"] is None)
    check("real.t1_reason", "printk.time" in real.missing["t1_kernel_init_ms"],
          real.missing["t1_kernel_init_ms"])
    check("real.t2_unknown", real.metrics["t2_rootfs_mounted_ms"] is None)
    check("real.t2_reason", "guest_uptime" in real.missing["t2_rootfs_mounted_ms"],
          real.missing["t2_rootfs_mounted_ms"])
    check("real.page_size", real.fixture.get("page_size") == 16384, repr(real.fixture))
    check("real.kernel_version", real.fixture.get("kernel_version") == "7.2.0-rc4-g856737c31142",
          repr(real.fixture))

    # Console-only fallback: 'kernel exited ... duration_ms=' is accepted when
    # no exit file exists.
    real_console_only = build_run(
        "uml",
        "real-console-only",
        console_text=_fixture("uml-console-real-20261029-excerpt.txt"),
    )
    check("real_console.duration", approx(real_console_only.metrics["duration_ms"], 3182.0),
          repr(real_console_only.metrics["duration_ms"]))
    check("real_console.source",
          real_console_only.sources.get("duration_ms") == "console:kernel_exited",
          repr(real_console_only.sources))

    # ---- Synthetic 2026-11-07-format sample (documented medians) ----------
    synthetic = build_run(
        "uml",
        "synthetic-20261107",
        console_text=_fixture("uml-console-20261107-synthetic.txt"),
        exit_text=_fixture("uml-exit-20261107-synthetic.txt"),
    )
    check("synthetic.t2", approx(synthetic.metrics["t2_rootfs_mounted_ms"], 340.0))
    check("synthetic.t3", approx(synthetic.metrics["t3_first_command_ms"], 350.0))
    check("synthetic.t3_band", 220.0 <= (synthetic.metrics["t3_first_command_ms"] or 0.0) <= 400.0)
    check("synthetic.duration", approx(synthetic.metrics["duration_ms"], 612.0))
    check("synthetic.source.t3",
          synthetic.sources.get("t3_first_command_ms") == "console:work_start_uptime")

    bootprobe = build_run(
        "uml",
        "bootprobe",
        console_text=_fixture("uml-console-bootprobe.txt"),
    )
    check("bootprobe.t2", approx(bootprobe.metrics["t2_rootfs_mounted_ms"], 340.0),
          repr(bootprobe.metrics["t2_rootfs_mounted_ms"]))
    check("bootprobe.t3", approx(bootprobe.metrics["t3_first_command_ms"], 350.0),
          repr(bootprobe.metrics["t3_first_command_ms"]))
    check("bootprobe.source",
          bootprobe.sources.get("t3_first_command_ms") == "console:bootprobe_uptime",
          repr(bootprobe.sources))

    # ---- U0.1 cross-session alias markers (U1/U2, ticket 05) --------------
    # U1 fast-shell: U1_READY uptime=<s> maps to T3. U1_T2_READY is not yet a
    # protocol marker, so T2 falls back to the VFS kernel timestamp.
    u1_alias = build_run(
        "uml", "u1-alias",
        console_text=_fixture("uml-console-u1-ready-ok.txt"),
    )
    check("u1_alias.t1", approx(u1_alias.metrics["t1_kernel_init_ms"], 10.0),
          repr(u1_alias.metrics["t1_kernel_init_ms"]))
    check("u1_alias.t2_vfs_fallback",
          approx(u1_alias.metrics["t2_rootfs_mounted_ms"], 60.0),
          repr(u1_alias.metrics["t2_rootfs_mounted_ms"]))
    check("u1_alias.t2_source",
          u1_alias.sources.get("t2_rootfs_mounted_ms") == "console:kernel_timestamp",
          repr(u1_alias.sources))
    check("u1_alias.t3", approx(u1_alias.metrics["t3_first_command_ms"], 110.0),
          repr(u1_alias.metrics["t3_first_command_ms"]))
    check("u1_alias.t3_source",
          u1_alias.sources.get("t3_first_command_ms") == "console:u1_ready_uptime",
          repr(u1_alias.sources))
    check("u1_alias.duration", approx(u1_alias.metrics["duration_ms"], 450.0),
          repr(u1_alias.metrics["duration_ms"]))
    check("u1_alias.no_invalid_warning",
          not any("invalid" in warning for warning in u1_alias.warnings),
          repr(u1_alias.warnings))

    # U2 zero-install: T2_MOUNTS_READY is a direct T2 measurement and beats
    # the VFS kernel timestamp; T3_READY maps to T3; the bare READY line is
    # gated by INIT_ZERO_INSTALL/T3_READY in the same run and adds nothing.
    u2_alias = build_run(
        "uml", "u2-alias",
        console_text=_fixture("uml-console-u2-zero-install-ok.txt"),
    )
    check("u2_alias.t1", approx(u2_alias.metrics["t1_kernel_init_ms"], 40.0),
          repr(u2_alias.metrics["t1_kernel_init_ms"]))
    check("u2_alias.t2", approx(u2_alias.metrics["t2_rootfs_mounted_ms"], 90.0),
          repr(u2_alias.metrics["t2_rootfs_mounted_ms"]))
    check("u2_alias.t2_beats_vfs_fallback",
          u2_alias.sources.get("t2_rootfs_mounted_ms") == "console:t2_mounts_ready_uptime",
          repr(u2_alias.sources))
    check("u2_alias.t3", approx(u2_alias.metrics["t3_first_command_ms"], 110.0),
          repr(u2_alias.metrics["t3_first_command_ms"]))
    check("u2_alias.t3_source",
          u2_alias.sources.get("t3_first_command_ms") == "console:t3_ready_uptime",
          repr(u2_alias.sources))
    check("u2_alias.duration", approx(u2_alias.metrics["duration_ms"], 600.0),
          repr(u2_alias.metrics["duration_ms"]))
    check("u2_alias.no_invalid_warning",
          not any("invalid" in warning for warning in u2_alias.warnings),
          repr(u2_alias.warnings))

    # Bare READY without U1/U2 context maps to no lane metric at all.
    bare_ambig = build_run(
        "uml", "bare-ambiguous",
        console_text=_fixture("uml-console-bare-ready-ambiguous.txt"),
    )
    check("bare_ambig.t3_none", bare_ambig.metrics["t3_first_command_ms"] is None,
          repr(bare_ambig.metrics["t3_first_command_ms"]))
    check("bare_ambig.no_t3_source",
          "t3_first_command_ms" not in bare_ambig.sources, repr(bare_ambig.sources))
    check("bare_ambig.generic_hint",
          bare_ambig.missing["t3_first_command_ms"].startswith("no 'BENCH"),
          bare_ambig.missing["t3_first_command_ms"])
    check("bare_ambig.duration", approx(bare_ambig.metrics["duration_ms"], 300.0),
          repr(bare_ambig.metrics["duration_ms"]))

    # U1_READY without an uptime value is never estimated.
    u1_no_uptime = build_run(
        "uml", "u1-no-uptime",
        console_text=_fixture("uml-console-u1-ready-no-uptime.txt"),
    )
    check("u1_no_uptime.t3_none",
          u1_no_uptime.metrics["t3_first_command_ms"] is None,
          repr(u1_no_uptime.metrics["t3_first_command_ms"]))
    check("u1_no_uptime.generic_hint",
          u1_no_uptime.missing["t3_first_command_ms"].startswith("no 'BENCH"),
          u1_no_uptime.missing["t3_first_command_ms"])
    check("u1_no_uptime.t2_vfs_fallback",
          approx(u1_no_uptime.metrics["t2_rootfs_mounted_ms"], 60.0),
          repr(u1_no_uptime.metrics["t2_rootfs_mounted_ms"]))
    check("u1_no_uptime.no_invalid_warning",
          not any("invalid" in warning for warning in u1_no_uptime.warnings),
          repr(u1_no_uptime.warnings))

    # Unit/value errors on the aliases produce warnings, never guesses.
    bad_unit = build_run(
        "uml", "u2-bad-unit",
        console_text=_fixture("uml-console-u2-ready-bad-unit.txt"),
    )
    check("bad_unit.t2_none", bad_unit.metrics["t2_rootfs_mounted_ms"] is None,
          repr(bad_unit.metrics["t2_rootfs_mounted_ms"]))
    check("bad_unit.t3_none", bad_unit.metrics["t3_first_command_ms"] is None,
          repr(bad_unit.metrics["t3_first_command_ms"]))
    check("bad_unit.t2_warning",
          any("invalid T2_MOUNTS_READY uptime=0.09ms" in warning
              for warning in bad_unit.warnings),
          repr(bad_unit.warnings))
    check("bad_unit.t3_warning",
          any("invalid T3_READY uptime=abc" in warning for warning in bad_unit.warnings),
          repr(bad_unit.warnings))
    check("bad_unit.gated_ready_hint",
          "READY" in bad_unit.missing["t3_first_command_ms"],
          bad_unit.missing["t3_first_command_ms"])

    # Alias priority: canonical BENCH/UMARM/BOOTPROBE markers keep precedence.
    alias_priority = build_run(
        "uml", "alias-priority",
        console_text=(
            "INIT_ZERO_INSTALL stage=mounts\n"
            "BENCH boot_uptime_ms=400\n"
            "U1_READY uptime=0.30\n"
            "T3_READY uptime=0.29 action=shell\n"
            "READY\n"
        ),
    )
    check("alias_priority.bench_beats_alias",
          approx(alias_priority.metrics["t3_first_command_ms"], 400.0)
          and alias_priority.sources.get("t3_first_command_ms") == "console:boot_uptime_ms",
          repr(alias_priority.sources.get("t3_first_command_ms")))

    alias_work_start = build_run(
        "uml", "alias-work-start",
        console_text=(
            "UMARM_WORK_START work=w uptime=0.35\n"
            "BOOTPROBE probe=1 uptime=0.33\n"
            "U1_READY uptime=0.30\n"
            "T3_READY uptime=0.29 action=shell\n"
            "READY\n"
        ),
    )
    check("alias_priority.work_start_beats_alias",
          approx(alias_work_start.metrics["t3_first_command_ms"], 350.0)
          and alias_work_start.sources.get("t3_first_command_ms") == "console:work_start_uptime",
          repr(alias_work_start.sources.get("t3_first_command_ms")))

    alias_bootprobe = build_run(
        "uml", "alias-bootprobe",
        console_text=(
            "BOOTPROBE probe=1 uptime=0.33\n"
            "U1_READY uptime=0.30\n"
            "T3_READY uptime=0.29 action=shell\n"
            "READY\n"
        ),
    )
    check("alias_priority.bootprobe_beats_alias",
          approx(alias_bootprobe.metrics["t3_first_command_ms"], 330.0)
          and alias_bootprobe.sources.get("t3_first_command_ms") == "console:bootprobe_uptime",
          repr(alias_bootprobe.sources.get("t3_first_command_ms")))

    alias_u1_first = build_run(
        "uml", "alias-u1-first",
        console_text="U1_READY uptime=0.30\nT3_READY uptime=0.29 action=shell\nREADY\n",
    )
    check("alias_priority.u1_ready_beats_t3_ready",
          approx(alias_u1_first.metrics["t3_first_command_ms"], 300.0)
          and alias_u1_first.sources.get("t3_first_command_ms") == "console:u1_ready_uptime",
          repr(alias_u1_first.sources.get("t3_first_command_ms")))

    alias_t3_only = build_run(
        "uml", "alias-t3-only",
        console_text="T3_READY uptime=0.29 action=shell\nREADY\n",
    )
    check("alias_priority.t3_ready",
          approx(alias_t3_only.metrics["t3_first_command_ms"], 290.0)
          and alias_t3_only.sources.get("t3_first_command_ms") == "console:t3_ready_uptime",
          repr(alias_t3_only.sources.get("t3_first_command_ms")))

    # Bare READY is only trusted inside a zero-install run; even then it has
    # no uptime of its own, so T3 stays unknown unless an alias carried one.
    bare_gated_init = build_run(
        "uml", "bare-gated-init",
        console_text="INIT_ZERO_INSTALL stage=mounts\nREADY\n",
    )
    check("bare_ready.gated_by_init_zero_install",
          bare_gated_init.metrics["t3_first_command_ms"] is None,
          repr(bare_gated_init.metrics["t3_first_command_ms"]))
    check("bare_ready.gated_hint",
          "READY" in bare_gated_init.missing["t3_first_command_ms"],
          bare_gated_init.missing["t3_first_command_ms"])

    bare_gated_t3 = build_run(
        "uml", "bare-gated-t3",
        console_text="T3_READY uptime=abc action=shell\nREADY\n",
    )
    check("bare_ready.gated_by_t3_ready_line",
          bare_gated_t3.metrics["t3_first_command_ms"] is None,
          repr(bare_gated_t3.metrics["t3_first_command_ms"]))
    check("bare_ready.invalid_t3_ready_warning",
          any("invalid T3_READY uptime=abc" in warning for warning in bare_gated_t3.warnings),
          repr(bare_gated_t3.warnings))

    bare_ungated = build_run("uml", "bare-ungated", console_text="READY\n")
    check("bare_ready.ungated_not_t3",
          bare_ungated.metrics["t3_first_command_ms"] is None,
          repr(bare_ungated.metrics["t3_first_command_ms"]))
    check("bare_ready.ungated_generic_hint",
          bare_ungated.missing["t3_first_command_ms"].startswith("no 'BENCH"),
          bare_ungated.missing["t3_first_command_ms"])

    # T2 alias priority: UMARM_BOOT_OK keeps precedence over T2_MOUNTS_READY.
    t2_alias_priority = build_run(
        "uml", "t2-alias-priority",
        console_text=(
            "UMARM_BOOT_OK guest_uptime=0.12\n"
            "T2_MOUNTS_READY uptime=0.09\n"
        ),
    )
    check("t2_alias.boot_ok_beats_alias",
          approx(t2_alias_priority.metrics["t2_rootfs_mounted_ms"], 120.0)
          and t2_alias_priority.sources.get("t2_rootfs_mounted_ms") == "console:guest_uptime",
          repr(t2_alias_priority.sources.get("t2_rootfs_mounted_ms")))

    # ---- QEMU with systemd-analyze ----------------------------------------
    qemu = build_run(
        "qemu",
        "qemu-systemd",
        console_text=_fixture("qemu-serial-systemd.txt"),
    )
    check("qemu.t1", approx(qemu.metrics["t1_kernel_init_ms"], 31.0))
    check("qemu.t2", approx(qemu.metrics["t2_rootfs_mounted_ms"], 510.0))
    check("qemu.t3", approx(qemu.metrics["t3_first_command_ms"], 620.0))
    check("qemu.t4", approx(qemu.metrics["t4_service_ready_ms"], 1730.0),
          repr(qemu.metrics["t4_service_ready_ms"]))
    check("qemu.t4_source",
          qemu.sources.get("t4_service_ready_ms") == "console:startup_total",
          repr(qemu.sources))
    check("qemu.q0_unknown", qemu.metrics["q0_qemu_boot_ms"] is None)
    check("qemu.q0_reason", "Q0" in qemu.missing["q0_qemu_boot_ms"])
    check("qemu.ncpus", qemu.fixture.get("ncpus") == 2, repr(qemu.fixture))
    check("qemu.page_size", qemu.fixture.get("page_size") == 4096, repr(qemu.fixture))

    # ---- QEMU markers only: T4 from multi-user.target timestamp -----------
    qemu_markers = build_run(
        "qemu",
        "qemu-markers",
        console_text=_fixture("qemu-serial-markers-only.txt"),
    )
    check("qemu_markers.t4", approx(qemu_markers.metrics["t4_service_ready_ms"], 900.0),
          repr(qemu_markers.metrics["t4_service_ready_ms"]))
    check("qemu_markers.t4_source",
          qemu_markers.sources.get("t4_service_ready_ms") == "console:kernel_timestamp",
          repr(qemu_markers.sources))

    # ---- QEMU old-style systemd summary overrides marker ------------------
    qemu_old = build_run(
        "qemu",
        "qemu-old-systemd",
        console_text=_fixture("qemu-serial-markers-only.txt"),
        systemd_text=_fixture("systemd-analyze-old-style.txt"),
    )
    check("qemu_old.t4", approx(qemu_old.metrics["t4_service_ready_ms"], 8492.0),
          repr(qemu_old.metrics["t4_service_ready_ms"]))
    check("qemu_old.t4_source",
          qemu_old.sources.get("t4_service_ready_ms") == "systemd-analyze:startup_total",
          repr(qemu_old.sources))

    # ---- QEMU truncated systemd line: never guess the total ---------------
    qemu_no_total = build_run(
        "qemu",
        "qemu-no-total",
        console_text=_fixture("qemu-serial-systemd-no-total.txt"),
    )
    check("qemu_no_total.t4_none", qemu_no_total.metrics["t4_service_ready_ms"] is None,
          repr(qemu_no_total.metrics["t4_service_ready_ms"]))
    check("qemu_no_total.warning",
          any("total" in warning for warning in qemu_no_total.warnings),
          repr(qemu_no_total.warnings))

    # ---- systemd unit parsing ---------------------------------------------
    systemd_min = parse_systemd_analyze(_fixture("systemd-analyze-min-unit.txt"))
    check("systemd_min.total", approx(systemd_min.get("total_ms"), 62750.0),
          repr(systemd_min))
    check("systemd_min.kernel", approx(systemd_min.get("components", {}).get("kernel"), 250.0),
          repr(systemd_min))
    check("systemd_min.userspace",
          approx(systemd_min.get("components", {}).get("userspace"), 62500.0),
          repr(systemd_min))

    # ---- proot ------------------------------------------------------------
    proot = build_run("proot", None, timing_text=_fixture("proot-timing-ok.txt"))
    check("proot.label", proot.label == "proot-alpine", proot.label)
    check("proot.p0", approx(proot.metrics["p0_proot_exec_ms"], 50.101),
          repr(proot.metrics["p0_proot_exec_ms"]))
    check("proot.t0_anchor", approx(proot.anchors["t0_spawn_monotonic_ms"], 100000.0))
    check("proot.t1_none", proot.metrics["t1_kernel_init_ms"] is None)
    check("proot.t1_not_applicable",
          "not applicable" in proot.missing["t1_kernel_init_ms"],
          proot.missing["t1_kernel_init_ms"])
    check("proot.t4_not_applicable",
          "not applicable" in proot.missing["t4_service_ready_ms"],
          proot.missing["t4_service_ready_ms"])

    proot_bad = build_run("proot", "proot-bad", timing_text=_fixture("proot-timing-bad-unit.txt"))
    check("proot_bad.p0_none", proot_bad.metrics["p0_proot_exec_ms"] is None,
          repr(proot_bad.metrics["p0_proot_exec_ms"]))
    check("proot_bad.warning",
          any("ready_monotonic_ms" in warning and "invalid" in warning
              for warning in proot_bad.warnings),
          repr(proot_bad.warnings))

    proot_short = build_run(
        "proot", None, timing_text=_fixture("proot-timing-shorthand.txt")
    )
    check("proot_short.p0", approx(proot_short.metrics["p0_proot_exec_ms"], 50.101),
          repr(proot_short.metrics["p0_proot_exec_ms"]))
    check("proot_short.t0_none", proot_short.anchors["t0_spawn_monotonic_ms"] is None)
    check("proot_short.label", proot_short.label == "proot-shorthand", proot_short.label)

    proot_explicit = build_run(
        "proot", "explicit-label", timing_text=_fixture("proot-timing-shorthand.txt")
    )
    check("proot_explicit.label", proot_explicit.label == "explicit-label",
          proot_explicit.label)

    proot_with_console = build_run(
        "proot", "proot-console", console_text="BENCH boot_uptime_ms=400\n"
    )
    check("proot_console.t3_none",
          proot_with_console.metrics["t3_first_command_ms"] is None,
          repr(proot_with_console.metrics["t3_first_command_ms"]))
    check("proot_console.warning",
          any("not applicable" in warning for warning in proot_with_console.warnings),
          repr(proot_with_console.warnings))

    # ---- Host-anchored timing pairs ---------------------------------------
    qemu_timed = build_run(
        "qemu", "qemu-timed",
        console_text=_fixture("qemu-serial-systemd.txt"),
        timing_text=_fixture("qemu-timing-ok.txt"),
    )
    check("qemu_timed.q0", approx(qemu_timed.metrics["q0_qemu_boot_ms"], 1730.0),
          repr(qemu_timed.metrics["q0_qemu_boot_ms"]))
    check("qemu_timed.t0", approx(qemu_timed.anchors["t0_spawn_monotonic_ms"], 200000.0))

    uml_timed = build_run(
        "uml", "uml-timed",
        console_text=_fixture("uml-console-ok.txt"),
        exit_text=_fixture("uml-exit-ok.txt"),
        timing_text=_fixture("uml-timing-ok.txt"),
    )
    check("uml_timed.t3_from_spawn", approx(uml_timed.metrics["t3_from_spawn_ms"], 400.0),
          repr(uml_timed.metrics["t3_from_spawn_ms"]))
    check("uml_timed.source",
          uml_timed.sources.get("t3_from_spawn_ms") == "timing:t3_from_spawn_ms",
          repr(uml_timed.sources))

    t5_pair = build_run(
        "uml",
        "uml-t5",
        timing_text=(
            "BOOT_TIMING v1 lane=uml\n"
            "t0_monotonic_ms=1000.000\n"
            "ready_monotonic_ms=1100.000\n"
            "ready_point=T5\n"
        ),
    )
    check("t5.pair", approx(t5_pair.metrics["t5_warm_resume_ms"], 100.0),
          repr(t5_pair.metrics["t5_warm_resume_ms"]))
    check("t5.pair_source",
          t5_pair.sources.get("t5_warm_resume_ms") == "timing:t5_ms",
          repr(t5_pair.sources))

    t5_marker = build_run(
        "uml", "uml-t5-marker", console_text="BOOT_TIMELINE T5 elapsed_ms=250\n"
    )
    check("t5.marker", approx(t5_marker.metrics["t5_warm_resume_ms"], 250.0),
          repr(t5_marker.metrics["t5_warm_resume_ms"]))

    t5_guest = build_run(
        "uml", "uml-t5-guest", console_text="BOOT_TIMELINE T5 uptime_s=1.0\n"
    )
    check("t5.reject_guest_clock", t5_guest.metrics["t5_warm_resume_ms"] is None,
          repr(t5_guest.metrics["t5_warm_resume_ms"]))
    check("t5.reject_warning",
          any("elapsed_ms" in warning for warning in t5_guest.warnings),
          repr(t5_guest.warnings))

    # ---- Median and n -----------------------------------------------------
    def t3_run(value: int) -> RunResult:
        return build_run(
            "uml",
            f"t3-{value}",
            console_text=f"[    0.010000] Run /init as init process\n"
                         f"BENCH boot_uptime_ms={value}\n",
        )

    odd = summarize_runs([t3_run(220), t3_run(330), t3_run(400)])
    check("median.odd.n", odd["n"]["t3_first_command_ms"] == 3, repr(odd["n"]))
    check("median.odd.value", approx(odd["median_ms"]["t3_first_command_ms"], 330.0),
          repr(odd["median_ms"]))
    check("median.ignores_duration",
          odd["n"]["duration_ms"] == 0 and odd["median_ms"]["duration_ms"] is None,
          repr(odd))
    check("median.no_t0", "t0_spawn_monotonic_ms" not in odd["n"], repr(odd["n"]))

    even = summarize_runs([t3_run(220), t3_run(400)])
    check("median.even.n", even["n"]["t3_first_command_ms"] == 2)
    check("median.even.value", approx(even["median_ms"]["t3_first_command_ms"], 310.0),
          repr(even["median_ms"]))

    with_missing = summarize_runs([
        t3_run(220),
        build_run("uml", "t3-missing", console_text="[    0.010000] Run /init as init process\n"),
        t3_run(400),
    ])
    check("median.missing.n", with_missing["n"]["t3_first_command_ms"] == 2,
          repr(with_missing["n"]))
    check("median.missing.value",
          approx(with_missing["median_ms"]["t3_first_command_ms"], 310.0),
          repr(with_missing["median_ms"]))

    # ---- dmesg fallback and canonical markers -----------------------------
    dmesg_fallback = build_run(
        "uml",
        "dmesg-fallback",
        console_text="Run /init as init process\n",
        dmesg_text=(
            "<6>[    0.020000] Run /init as init process\n"
            "<6>[    0.026000] VFS: Mounted root (ext4 filesystem) "
            "on device 179:1.\n"
        ),
    )
    check("dmesg.t1", approx(dmesg_fallback.metrics["t1_kernel_init_ms"], 20.0),
          repr(dmesg_fallback.metrics["t1_kernel_init_ms"]))
    check("dmesg.t2", approx(dmesg_fallback.metrics["t2_rootfs_mounted_ms"], 26.0),
          repr(dmesg_fallback.metrics["t2_rootfs_mounted_ms"]))
    check("dmesg.source",
          dmesg_fallback.sources.get("t1_kernel_init_ms") == "dmesg:kernel_timestamp",
          repr(dmesg_fallback.sources))

    canonical = build_run(
        "uml",
        "canonical",
        console_text=(
            "[    0.010000] Run /init as init process\n"
            "BENCH boot_uptime_ms=900\n"
            "BOOT_TIMELINE T3 uptime_ms=880\n"
            "BOOT_TIMELINE T4 uptime_s=1.500\n"
        ),
    )
    check("canonical.t3", approx(canonical.metrics["t3_first_command_ms"], 880.0),
          repr(canonical.metrics["t3_first_command_ms"]))
    check("canonical.t3_source",
          canonical.sources.get("t3_first_command_ms") == "console:boot_timeline",
          repr(canonical.sources))
    check("canonical.t4", approx(canonical.metrics["t4_service_ready_ms"], 1500.0),
          repr(canonical.metrics["t4_service_ready_ms"]))

    target_new = build_run(
        "qemu",
        "target-new",
        console_text="[    0.700000] systemd[1]: Reached target multi-user.target.\n",
    )
    check("target_new.t4", approx(target_new.metrics["t4_service_ready_ms"], 700.0),
          repr(target_new.metrics["t4_service_ready_ms"]))

    systemd_us = parse_systemd_analyze(
        "Startup finished in 500us (kernel) + 1.000s (userspace) = 1.0005s\n"
    )
    check("systemd_us.total", approx(systemd_us.get("total_ms"), 1000.5),
          repr(systemd_us))
    check("systemd_us.kernel", approx(systemd_us.get("components", {}).get("kernel"), 0.5),
          repr(systemd_us))

    # ---- Rendering and schema --------------------------------------------
    report = build_report("uml", [uml, uml_missing])
    rendered_json = render_json(report)
    parsed = json.loads(rendered_json)
    check("json.protocol", parsed.get("protocol") == PROTOCOL)
    check("json.lane", parsed.get("lane") == "uml")
    check("json.runs", len(parsed.get("runs", [])) == 2, repr(len(parsed.get("runs", []))))
    check("json.summary", "median_ms" in parsed.get("summary", {}))
    check("json.unit", parsed.get("unit") == "ms")
    text = render_text(report)
    check("text.table", "| run |" in text)
    check("text.unknown", "unknown" in text)
    check("text.median", "median" in text.lower())

    # ---- Empty / malformed inputs never crash -----------------------------
    empty = build_run("uml", "empty")
    check("empty.all_unknown",
          all(empty.metrics[name] is None for name in DURATION_METRICS),
          repr(empty.metrics))
    check("empty.has_reason", bool(empty.missing.get("t3_first_command_ms")))

    malformed = build_run(
        "uml",
        "malformed",
        console_text=(
            "[   12x.3] Run /init as init process\n"
            "BENCH boot_uptime_ms=abc\n"
            "UMARM_WORK_START work=x uptime=notanumber\n"
        ),
    )
    check("malformed.no_guess", malformed.metrics["t1_kernel_init_ms"] is None
          and malformed.metrics["t3_first_command_ms"] is None,
          repr(malformed.metrics))
    check("malformed.warning",
          any("boot_uptime_ms" in warning for warning in malformed.warnings),
          repr(malformed.warnings))

    inconsistent = build_run(
        "uml",
        "inconsistent",
        console_text="BENCH boot_uptime_ms=5000\n",
        exit_text="exit_code=0\nduration_ms=1000\n",
    )
    check("consistency.warning",
          any("exceeds duration_ms" in warning for warning in inconsistent.warnings),
          repr(inconsistent.warnings))
    check("consistency.no_rewrite",
          approx(inconsistent.metrics["t3_first_command_ms"], 5000.0),
          repr(inconsistent.metrics["t3_first_command_ms"]))

    # ---- Timing validation ------------------------------------------------
    backwards = parse_timing(
        "BOOT_TIMING v1 lane=proot\nt0_monotonic_ms=200.0\n"
        "ready_monotonic_ms=100.0\nready_point=P0\n",
        "proot",
    )
    check("timing.backwards", backwards["p0_ms"] is None, repr(backwards))
    check("timing.backwards_warning",
          any("before" in warning for warning in backwards["warnings"]),
          repr(backwards["warnings"]))

    no_ready = parse_timing("BOOT_TIMING v1 lane=proot\nt0_monotonic_ms=200.0\n", "proot")
    check("timing.no_ready", no_ready["p0_ms"] is None, repr(no_ready))
    check("timing.t0_preserved", approx(no_ready["t0_monotonic_ms"], 200.0))

    if failures:
        print("UML_BOOT_BENCH_SELF_TEST_FAIL")
        for failure in failures:
            print(f"FAIL: {failure}")
        return 1
    print(f"UML_BOOT_BENCH_SELF_TEST_OK checks={checks}")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Parse UML/QEMU/proot boot logs into the T0-T5 protocol.",
    )
    parser.add_argument("--lane", choices=("uml", "qemu", "proot"))
    parser.add_argument("--console", action="append", default=[], metavar="FILE")
    parser.add_argument("--exit-file", action="append", default=[], metavar="FILE")
    parser.add_argument("--dmesg", action="append", default=[], metavar="FILE")
    parser.add_argument("--systemd-analyze", action="append", default=[], metavar="FILE")
    parser.add_argument("--timing", action="append", default=[], metavar="FILE")
    parser.add_argument("--label", action="append", default=[], metavar="NAME")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if not args.lane:
        parser.error("--lane is required unless --self-test is used")

    fillable = {
        "--console": args.console,
        "--exit-file": args.exit_file,
        "--dmesg": args.dmesg,
        "--systemd-analyze": args.systemd_analyze,
        "--timing": args.timing,
    }
    input_count = max([len(values) for values in fillable.values()] + [0])
    if input_count == 0:
        parser.error("no input files; pass --console/--exit-file/--dmesg/"
                     "--systemd-analyze/--timing or --self-test")
    run_count = input_count
    for flag, values in fillable.items():
        if len(values) not in (0, 1, run_count):
            parser.error(f"{flag} expects one value or one per run ({run_count})")
        if len(values) == 1 and run_count > 1:
            parser.error(f"{flag} has one value but {run_count} runs were requested")

    if args.label:
        if len(args.label) == 1 and run_count > 1:
            parser.error("--label has one value but multiple runs were requested")
        if len(args.label) != 1 and len(args.label) != run_count:
            parser.error(f"--label expects one value or one per run ({run_count})")

    texts: dict[str, list[str]] = {}
    for flag, values in fillable.items():
        if len(values) == 1 and run_count > 1:
            parser.error(f"{flag} has one value but {run_count} runs were requested")
        resolved: list[str] = []
        for index in range(run_count):
            if not values:
                resolved.append("")
                continue
            path = Path(values[index] if len(values) > 1 else values[0])
            try:
                resolved.append(path.read_text(encoding="utf-8", errors="replace"))
            except OSError as exc:
                print(f"error: cannot read {path}: {exc}", file=sys.stderr)
                return 2
        texts[flag] = resolved

    runs: list[RunResult] = []
    for index in range(run_count):
        label = args.label[index] if len(args.label) > 1 else (args.label[0] if args.label else None)
        runs.append(
            build_run(
                args.lane,
                label,
                console_text=texts["--console"][index],
                exit_text=texts["--exit-file"][index],
                dmesg_text=texts["--dmesg"][index],
                systemd_text=texts["--systemd-analyze"][index],
                timing_text=texts["--timing"][index],
            )
        )

    report = build_report(args.lane, runs)
    if args.format == "json":
        print(render_json(report))
    else:
        print(render_text(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
