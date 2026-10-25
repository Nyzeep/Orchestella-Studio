#!/usr/bin/env python3
"""DV-PVM-01 probe host driver (QA-only).

Discovers the single QA device, installs the QA APK pair, grants the VM
permissions, runs PvmProbeInstrumentation with one payload name, harvests the
guest's PVM_PROBE observations from logcat, pulls the device-side evidence
file, and prints a fixed-code summary.

Modes:
  compile  01a/01b full probes: N compile iterations, RSS curve, P50/P95.
  load     01b load diagnostic (libpvm_probe_clangload.so): the three
           sonames must resolve; there is no compile loop.

Evidence hygiene: serials never reach stdout or the summary; logcat dumps go
to the operator-provided logcat directory (outside the repo) and only the
PVM_PROBE JSON lines plus fixed codes flow into the report data.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import time
import uuid

INSTRUMENTATION = "io.github.nyzeep.orchestella.PvmProbeInstrumentation"
DEFAULT_PACKAGE = "io.github.nyzeep.orchestella.qa.dvpvm"
PROBE_FIELDS = ("stage", "status", "iter", "compile_ms", "ru_maxrss_kb",
                "obj_bytes", "llvm_version", "vm_cpu_topology")
RSS_GROWTH_LIMIT_PCT = 30.0


def parse_probe_lines(text: str) -> list[dict]:
    """Extract PVM_PROBE JSON observations from raw logcat text.

    Logcat prefixes vary by format; only the marker and the trailing JSON
    object matter. Malformed payload lines are skipped, never fatal.
    """
    lines = []
    for raw in text.splitlines():
        marker = raw.find("PVM_PROBE")
        if marker < 0:
            continue
        # Logcat threadtime appends "(pid): " after the tag; the JSON object
        # starts at the first brace following the marker.
        brace = raw.find("{", marker)
        if brace < 0:
            continue
        try:
            record = json.loads(raw[brace:])
        except json.JSONDecodeError:
            continue
        if not isinstance(record, dict):
            continue
        lines.append({key: record.get(key) for key in PROBE_FIELDS})
    return lines


def _percentile_nearest_rank(sorted_values: list[int], fraction: float) -> int:
    rank = max(0, min(math.ceil(fraction * len(sorted_values)) - 1,
                      len(sorted_values) - 1))
    return sorted_values[rank]


def aggregate_iterations(lines: list[dict], expected_cycles: int) -> dict:
    """Aggregate the compile-stage observations of one probe run."""
    iters = sorted((line for line in lines if line.get("stage") == "compile"),
                   key=lambda line: line.get("iter") or 0)
    stats = {
        "iterations": len(iters),
        "expected_cycles": expected_cycles,
        "all_ok": False,
        "within_rss_budget": False,
        "p50_compile_ms": None,
        "p95_compile_ms": None,
        "rss_first_kb": None,
        "rss_last_kb": None,
        "rss_growth_pct": None,
        "first_failure_status": None,
    }
    if not iters:
        return stats
    durations = sorted(line["compile_ms"] for line in iters
                       if isinstance(line.get("compile_ms"), int))
    if durations:
        stats["p50_compile_ms"] = durations[(len(durations) - 1) // 2]
        stats["p95_compile_ms"] = _percentile_nearest_rank(durations, 0.95)
    rss_values = [line["ru_maxrss_kb"] for line in iters
                  if isinstance(line.get("ru_maxrss_kb"), int)]
    if rss_values:
        stats["rss_first_kb"] = rss_values[0]
        stats["rss_last_kb"] = rss_values[-1]
        if rss_values[0] > 0:
            growth = round((rss_values[-1] - rss_values[0]) / rss_values[0] * 100.0, 1)
            stats["rss_growth_pct"] = growth
            stats["within_rss_budget"] = growth <= RSS_GROWTH_LIMIT_PCT
    first_failure = next((line.get("status") for line in iters
                          if line.get("status") != "OK"), None)
    stats["first_failure_status"] = first_failure
    # The payload always runs its full internal iteration count; --cycles is
    # the minimum the operator asked to see succeed, not the exact count.
    stats["all_ok"] = len(iters) >= expected_cycles and first_failure is None
    return stats


def summarize(evidence: dict, probe_lines: list[dict], expected_cycles: int) -> dict:
    """Fixed-code verdict for one compile probe; no identifiers pass through."""
    stats = aggregate_iterations(probe_lines, expected_cycles)
    failure = evidence.get("failure_code")
    if not isinstance(failure, str):
        failure = None if evidence.get("status_passed", False) else "EVIDENCE_INCOMPLETE"
    if failure is None and not stats["all_ok"]:
        failure = stats["first_failure_status"] or "PROBE_CYCLES_INCOMPLETE"
    if failure is None and not stats["within_rss_budget"]:
        failure = "RSS_GROWTH"
    return {
        "mode": "compile",
        "verdict": "PASS" if failure is None else "FAIL",
        "failure_code": failure,
        "payload_name": evidence.get("payload_name"),
        "memory_mb": evidence.get("memory_mb"),
        "run_token_sha256": evidence.get("run_token_sha256"),
        "payload_ready_ms": evidence.get("payload_ready_ms"),
        "payload_finished_ms": evidence.get("payload_finished_ms"),
        "exit_code": evidence.get("exit_code"),
        "stop_confirmed": evidence.get("stop_confirmed"),
        "deleted": evidence.get("deleted"),
        "device": evidence.get("device"),
        "notes": evidence.get("notes"),
        "cycles": stats,
    }


def summarize_load(evidence: dict, probe_lines: list[dict]) -> dict:
    """Verdict for the 01b load diagnostic: every soname must resolve."""
    loads = [line for line in probe_lines if line.get("stage") == "load"]
    done = [line for line in probe_lines if line.get("stage") == "done"]
    failure = evidence.get("failure_code")
    if not isinstance(failure, str):
        failure = None if evidence.get("status_passed", False) else "EVIDENCE_INCOMPLETE"
    if failure is None and len(loads) != 3:
        failure = "LOAD_STAGES_INCOMPLETE"
    if failure is None and any(line.get("status") != "OK" for line in loads):
        failure = next(line.get("status") for line in loads if line.get("status") != "OK")
    if failure is None and (not done or done[-1].get("status") != "OK"):
        failure = "LOAD_PROBE_NO_DONE_OK"
    return {
        "mode": "load",
        "verdict": "PASS" if failure is None else "FAIL",
        "failure_code": failure,
        "payload_name": evidence.get("payload_name"),
        "memory_mb": evidence.get("memory_mb"),
        "run_token_sha256": evidence.get("run_token_sha256"),
        "payload_ready_ms": evidence.get("payload_ready_ms"),
        "exit_code": evidence.get("exit_code"),
        "stop_confirmed": evidence.get("stop_confirmed"),
        "deleted": evidence.get("deleted"),
        "device": evidence.get("device"),
        "notes": evidence.get("notes"),
        "loads": loads,
        "done": done[-1] if done else None,
    }


def merge_probe_lines(logcat_lines: list, evidence: dict) -> list:
    """Fold evidence-delivered probe lines into the logcat harvest.

    Locked-down guests (vendor debug policy) never export guest logcat, so
    the instrumentation also hands back the lines it read from the payload's
    vsock result channel. vsock lines are authoritative; duplicates across
    the two sources are dropped.
    """
    extra = evidence.get("probe_lines")
    if not isinstance(extra, list):
        return logcat_lines
    combined = logcat_lines + parse_probe_lines("\n".join(str(x) for x in extra))
    seen = set()
    deduped = []
    for line in combined:
        key = tuple(sorted((k, json.dumps(v, sort_keys=True))
                           for k, v in line.items()))
        if key not in seen:
            seen.add(key)
            deduped.append(line)
    return deduped


def parse_device_list(text: str) -> list[str]:
    """Serials in the `device` state only; anything else is not usable."""
    serials = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


class Adb:
    def __init__(self, adb_path: str, serial: str | None):
        self.adb_path = adb_path
        self.serial = serial

    def run(self, *args: str, timeout: int = 120) -> str:
        command = [self.adb_path]
        if self.serial:
            command += ["-s", self.serial]
        command += list(args)
        completed = subprocess.run(command, capture_output=True, text=True,
                                   encoding="utf-8", errors="replace",
                                   timeout=timeout)
        if completed.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: "
                               f"{completed.stderr.strip() or completed.stdout.strip()}")
        return completed.stdout

    def discover(self) -> str:
        devices = parse_device_list(self.run("devices", "-l"))
        if self.serial:
            if self.serial not in devices:
                raise RuntimeError("requested serial is not in the device state")
            return self.serial
        if len(devices) != 1:
            raise RuntimeError(f"expected exactly one usable device, got {len(devices)}")
        return devices[0]


def run_probe_once(adb: Adb, qa_package: str, payload_name: str, memory_mb: int,
                   apk_path: str, test_apk_path: str, logcat_path: str,
                   timeout_s: int) -> tuple[dict, list[dict]]:
    serial = adb.discover()
    adb.run("install", "-r", "-t", apk_path, timeout=900)
    adb.run("install", "-r", "-t", test_apk_path, timeout=900)
    for permission in ("android.permission.MANAGE_VIRTUAL_MACHINE",
                       "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"):
        adb.run("shell", "pm", "grant", qa_package, permission)
    adb.run("shell", "am", "force-stop", qa_package)

    adb.run("logcat", "-c")
    with open(logcat_path, "w", encoding="utf-8", errors="replace") as sink:
        stream = subprocess.Popen(
            [adb.adb_path, "-s", serial, "logcat", "-v", "threadtime"],
            stdout=sink, stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL)
        token = uuid.uuid4().hex
        instrument = [adb.adb_path, "-s", serial, "shell", "am", "instrument",
                      "-w", "-r",
                      "-e", "qaRunToken", token,
                      "-e", "qaPayloadName", payload_name,
                      "-e", "qaMemoryMb", str(memory_mb),
                      f"{qa_package}.test/{INSTRUMENTATION}"]
        try:
            # HyperOS injects non-UTF-8 bytes into the instrument output
            # stream; strict decoding killed the reader thread mid-run.
            subprocess.run(instrument, capture_output=True, text=True,
                           encoding="utf-8", errors="replace",
                           timeout=timeout_s)
        finally:
            stream.terminate()
            try:
                stream.wait(timeout=15)
            except subprocess.TimeoutExpired:
                stream.kill()
            try:
                tail = adb.run("logcat", "-d", "-v", "threadtime", timeout=60)
                if tail:
                    with open(logcat_path, "a", encoding="utf-8",
                              errors="replace") as extra:
                        extra.write(tail)
            except (RuntimeError, subprocess.SubprocessError):
                pass

    evidence_text = adb.run("shell", "run-as", qa_package, "cat",
                            f"files/avf-qa/pvm-probe-{token}.json", timeout=60)
    evidence = json.loads(evidence_text)
    with open(logcat_path, encoding="utf-8", errors="replace") as source:
        probe_lines = merge_probe_lines(parse_probe_lines(source.read()), evidence)
    return evidence, probe_lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, help="path to the platform-tools adb")
    parser.add_argument("--serial", default=None,
                        help="device serial (default: the only usable device)")
    parser.add_argument("--qa-package", default=DEFAULT_PACKAGE)
    parser.add_argument("--payload", required=True,
                        help="payload .so name inside the QA APK (lib/arm64-v8a)")
    parser.add_argument("--mode", choices=("compile", "load"), default="compile")
    parser.add_argument("--memory-mb", type=int, default=2048)
    parser.add_argument("--cycles", type=int, default=10,
                        help="expected compile iteration count (compile mode)")
    parser.add_argument("--apk", required=True, help="QA app APK path")
    parser.add_argument("--test-apk", required=True, help="QA androidTest APK path")
    parser.add_argument("--logcat-dir", required=True,
                        help="directory for raw logcat dumps (keep outside the repo)")
    parser.add_argument("--alias", default="qa-device",
                        help="fixed device alias recorded in the summary")
    parser.add_argument("--output", required=True, help="summary JSON output path")
    parser.add_argument("--timeout-s", type=int, default=2400)
    args = parser.parse_args(argv)

    os.makedirs(args.logcat_dir, exist_ok=True)
    logcat_path = os.path.join(
        args.logcat_dir, f"pvm-probe-{args.payload}-{int(time.time())}.log")
    adb = Adb(args.adb, args.serial)
    evidence, probe_lines = run_probe_once(
        adb, args.qa_package, args.payload, args.memory_mb, args.apk,
        args.test_apk, logcat_path, args.timeout_s)
    if args.mode == "load":
        summary = summarize_load(evidence, probe_lines)
    else:
        summary = summarize(evidence, probe_lines, args.cycles)
    summary["device_alias"] = args.alias
    summary["logcat_path"] = logcat_path
    with open(args.output, "w", encoding="utf-8") as sink:
        json.dump(summary, sink, indent=2, sort_keys=True)

    headline = {
        "mode": summary["mode"],
        "verdict": summary["verdict"],
        "failure_code": summary["failure_code"],
        "payload_name": summary["payload_name"],
        "memory_mb": summary["memory_mb"],
        "device_alias": args.alias,
        "exit_code": summary["exit_code"],
        "payload_ready_ms": summary["payload_ready_ms"],
        "stop_confirmed": summary["stop_confirmed"],
        "deleted": summary["deleted"],
    }
    if summary["mode"] == "compile":
        cycles = summary["cycles"]
        headline["cycles"] = {
            "iterations": cycles["iterations"],
            "all_ok": cycles["all_ok"],
            "p50_compile_ms": cycles["p50_compile_ms"],
            "p95_compile_ms": cycles["p95_compile_ms"],
            "rss_first_kb": cycles["rss_first_kb"],
            "rss_last_kb": cycles["rss_last_kb"],
            "rss_growth_pct": cycles["rss_growth_pct"],
        }
    else:
        headline["loads"] = summary["loads"]
        headline["done"] = summary["done"]
    print(json.dumps(headline, indent=2))
    return 0 if summary["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
